package com.campusdash.infrastructure.cache;

import com.campusdash.domain.grab.model.*;
import com.campusdash.domain.grab.ports.GrabProjectionPort;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Redis 原子语义验证；requireMiddleware=true 时缺依赖直接失败，不伪造通过。 */
class RedisGrabProjectionIT {
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private RedisGrabProjectionAdapter adapter;
    private long id;
    private final List<String> keys=new ArrayList<>();
    @BeforeAll static void connect() throws Exception {
        String host=System.getProperty("it.redis.host","127.0.0.1");
        int port=Integer.getInteger("it.redis.port",6380);
        boolean available;
        try(Socket socket=new Socket()) { socket.connect(new InetSocketAddress(host,port),500);available=true; }
        catch(Exception e) { available=false; }
        if(Boolean.getBoolean("requireMiddleware")) assertTrue(available,"必须提供真实 Redis");
        Assumptions.assumeTrue(available,"真实 Redis 不可用");
        connection=redisConnectionFactory(host, port);connection.afterPropertiesSet();connection.start();
        redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
    }
    @AfterAll static void disconnect() { if(connection!=null) connection.destroy(); }
    @BeforeEach void setup() {
        id=8_800_000_000_000_000_000L+Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),1_000_000_000L);
        adapter=new RedisGrabProjectionAdapter(redis);keys.add(RedisGrabProjectionAdapter.stateKey(id));
    }
    @AfterEach void cleanup() { if(redis!=null) redis.delete(keys); }
    private GrabProjection projection(long version,int round,String status,Long owner) {
        return new GrabProjection(1,id,version,round,status,owner==null?0:1,owner);
    }
    private GrabRequest request(long version,int round,long runner) {
        GrabRequest r=new GrabRequest(id+runner,1,id,runner,UUID.randomUUID().toString(),"a".repeat(64),round,version,
                GrabRequestStatus.PROCESSING,UUID.randomUUID().toString(),Instant.now().plusSeconds(30),null,0,null,Instant.now(),null);
        keys.add(RedisGrabProjectionAdapter.revokedKey(r));return r;
    }
    private void ready(GrabProjection p) {
        String token=UUID.randomUUID().toString();assertTrue(adapter.beginRebuild(id,token,30_000));
        assertTrue(adapter.finishRebuild(p,token));
    }
    @Test void concurrentAcquiresHaveOnePendingAndNeverBusinessSuccess() throws Exception {
        ready(projection(1,0,"PUBLISHED",null));
        List<Callable<GrabProjectionPort.AcquireResult>> calls=new ArrayList<>();
        for(long runner=1;runner<=24;runner++) { GrabRequest r=request(1,0,runner); calls.add(()->adapter.acquire(r)); }
        try(var executor=Executors.newFixedThreadPool(8)) {
            long acquired=0;
            for(var future:executor.invokeAll(calls)) if(future.get()==GrabProjectionPort.AcquireResult.ACQUIRED) acquired++;
            assertEquals(1,acquired);
        }
        assertEquals("PUBLISHED",adapter.read(1,id).orElseThrow().committed().status());
    }
    @Test void sameVersionReplayPreservesPendingAndDoesNotRenewLease() {
        var p=projection(1,0,"PUBLISHED",null);ready(p);var r=request(1,0,21);
        assertEquals(GrabProjectionPort.AcquireResult.ACQUIRED,adapter.acquire(r));
        assertEquals(GrabProjectionPort.AcquireResult.PROCESSING,adapter.acquire(r));
        assertEquals(GrabProjectionPort.ApplyResult.UNCHANGED,adapter.apply(p));
        var pending=adapter.read(1,id).orElseThrow().pending();
        assertEquals(r.reservationToken(),pending.reservationToken());
        assertEquals(r.leaseUntil().toEpochMilli(),pending.leaseUntil().toEpochMilli());
    }
    @Test void transferThenReturnAllowsNewRunnerAndOldRevokeCannotClearThem() {
        ready(projection(1,0,"PUBLISHED",null));var a=request(1,0,21);adapter.acquire(a);
        adapter.apply(projection(2,0,"LOCKED",21L));
        adapter.apply(projection(3,1,"LOCKED",22L));
        adapter.apply(projection(4,2,"PUBLISHED",null));var c=request(4,2,23);
        assertEquals(GrabProjectionPort.AcquireResult.ACQUIRED,adapter.acquire(c));adapter.revoke(a);
        assertEquals(c.reservationToken(),adapter.read(1,id).orElseThrow().pending().reservationToken());
        assertEquals(GrabProjectionPort.ApplyResult.STALE,adapter.apply(projection(3,1,"LOCKED",22L)));
    }
    @Test void revokeBeforeAcquireCreatesTombstone() {
        ready(projection(1,0,"PUBLISHED",null));var r=request(1,0,21);adapter.revoke(r);
        assertEquals(GrabProjectionPort.AcquireResult.REVOKED,adapter.acquire(r));
        assertNull(adapter.read(1,id).orElseThrow().pending());
    }
    @Test void expiredPendingRequiresDatabaseRepair() {
        ready(projection(1,0,"PUBLISHED",null));adapter.acquire(request(1,0,21));
        redis.opsForHash().put(RedisGrabProjectionAdapter.stateKey(id),"leaseUntil","1");
        assertEquals(GrabProjectionPort.AcquireResult.REPAIR_REQUIRED,adapter.acquire(request(1,0,22)));
        assertNotNull(adapter.read(1,id).orElseThrow().pending());
    }
    @Test void missingStateCannotBeInitializedFromOldProjectionEvent() {
        assertEquals(GrabProjectionPort.ApplyResult.REBUILD_REQUIRED,adapter.apply(projection(1,0,"PUBLISHED",null)));
        assertTrue(adapter.read(1,id).isEmpty());
    }
    @Test void expiredBuilderCannotPublishAfterNewBuilderTakesOver() {
        assertTrue(adapter.beginRebuild(id,"old",30_000));
        redis.opsForHash().put(RedisGrabProjectionAdapter.stateKey(id),"buildUntil","1");
        assertTrue(adapter.beginRebuild(id,"new",30_000));
        assertFalse(adapter.finishRebuild(projection(1,0,"PUBLISHED",null),"old"));
        assertTrue(adapter.finishRebuild(projection(2,0,"LOCKED",21L),"new"));
    }
    @Test void sameVersionConflictNeedsVerifiedRepairAndPreservesPending() {
        ready(projection(1,0,"PUBLISHED",null));var r=request(1,0,21);adapter.acquire(r);
        redis.opsForHash().put(RedisGrabProjectionAdapter.stateKey(id),"ownerRunnerId","999");
        assertEquals(GrabProjectionPort.ApplyResult.CONFLICT,adapter.apply(projection(1,0,"PUBLISHED",null)));
        assertEquals(GrabProjectionPort.ApplyResult.APPLIED,adapter.repair(projection(1,0,"PUBLISHED",null)));
        assertEquals(r.reservationToken(),adapter.read(1,id).orElseThrow().pending().reservationToken());
    }
    @Test void bigintVersionsAreComparedWithoutLuaDoubleRounding() {
        long huge=9_007_199_254_740_992L;ready(projection(huge,0,"PUBLISHED",null));
        assertEquals(GrabProjectionPort.ApplyResult.APPLIED,adapter.apply(projection(huge+1,0,"LOCKED",21L)));
        assertEquals(huge+1,adapter.read(1,id).orElseThrow().committed().dbVersion());
        assertEquals(GrabProjectionPort.ApplyResult.STALE,adapter.apply(projection(huge,0,"PUBLISHED",null)));
    }

    private static LettuceConnectionFactory redisConnectionFactory(String host, int port) {
        var config = new org.springframework.data.redis.connection.RedisStandaloneConfiguration(host, port);
        String username = System.getenv("SPRING_DATA_REDIS_USERNAME");
        String password = System.getenv("SPRING_DATA_REDIS_PASSWORD");
        if (username != null && !username.isBlank()) config.setUsername(username);
        if (password != null && !password.isBlank()) config.setPassword(password);
        return new LettuceConnectionFactory(config);
    }
}
