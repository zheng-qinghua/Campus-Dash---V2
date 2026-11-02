package com.campusdash.infrastructure.cache;

import com.campusdash.domain.grab.model.GrabProjection;
import com.campusdash.domain.grab.model.GrabProjectionState;
import com.campusdash.domain.grab.model.GrabRequest;
import com.campusdash.domain.grab.ports.GrabProjectionPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** v2 单任务 Hash 协议；不调用旧名额 INCR/DECR。 */
@Component
public class RedisGrabProjectionAdapter implements GrabProjectionPort {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> acquire=script("acquire_pending.lua");
    private final DefaultRedisScript<Long> apply=script("apply_projection.lua");
    private final DefaultRedisScript<Long> revoke=script("revoke_pending.lua");
    private final DefaultRedisScript<Long> begin=script("begin_projection_rebuild.lua");
    private final DefaultRedisScript<Long> finish=script("finish_projection_rebuild.lua");
    public RedisGrabProjectionAdapter(StringRedisTemplate redis) { this.redis=redis; }
    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script=new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/"+name));
        script.setResultType(Long.class);
        return script;
    }
    public static String stateKey(long errandId) { return "dash:grab:v2:{"+errandId+"}:state"; }
    public static String revokedKey(GrabRequest r) {
        return "dash:grab:v2:{"+r.errandId()+"}:revoked:"+r.id()+":"+r.reservationToken();
    }
    @Override public AcquireResult acquire(GrabRequest r) {
        long code=required(redis.execute(acquire,List.of(stateKey(r.errandId()),revokedKey(r)),
                Long.toString(r.id()),r.reservationToken(),Long.toString(r.baseErrandVersion()),
                Integer.toString(r.requestRound()),Long.toString(r.runnerId()),Long.toString(r.leaseUntil().toEpochMilli())));
        return AcquireResult.values()[Math.toIntExact(code)];
    }
    @Override public ApplyResult apply(GrabProjection p) { return update(p,false); }
    @Override public ApplyResult repair(GrabProjection p) { return update(p,true); }
    private ApplyResult update(GrabProjection p,boolean verified) {
        long code=required(redis.execute(apply,List.of(stateKey(p.errandId())),Long.toString(p.dbVersion()),
                Integer.toString(p.round()),p.status(),Integer.toString(p.slotTaken()),
                p.ownerRunnerId()==null?"":p.ownerRunnerId().toString(),verified?"1":"0"));
        return ApplyResult.values()[Math.toIntExact(code)];
    }
    @Override public void revoke(GrabRequest r) {
        required(redis.execute(revoke,List.of(stateKey(r.errandId()),revokedKey(r)),Long.toString(r.id()),
                r.reservationToken(),Long.toString(r.baseErrandVersion()),Integer.toString(r.requestRound()),
                Long.toString(r.leaseUntil().toEpochMilli()),"60000"));
    }
    @Override public boolean beginRebuild(long errandId,String token,long leaseMillis) {
        if(leaseMillis<1) throw new IllegalArgumentException("重建租约必须大于零");
        return required(redis.execute(begin,List.of(stateKey(errandId)),token,Long.toString(leaseMillis)))==1;
    }
    @Override public boolean finishRebuild(GrabProjection p,String token) {
        return required(redis.execute(finish,List.of(stateKey(p.errandId())),token,Long.toString(p.dbVersion()),
                Integer.toString(p.round()),p.status(),Integer.toString(p.slotTaken()),
                p.ownerRunnerId()==null?"":p.ownerRunnerId().toString()))==1;
    }
    @Override public Optional<GrabProjectionState> read(long campusId,long errandId) {
        Map<Object,Object> hash=redis.opsForHash().entries(stateKey(errandId));
        if(hash.isEmpty()) return Optional.empty();
        String mode=(String)hash.get("mode");
        if(!"READY".equals(mode)) return Optional.of(new GrabProjectionState(mode,null,null));
        String owner=(String)hash.get("ownerRunnerId");
        GrabProjection committed=new GrabProjection(campusId,errandId,number(hash,"dbVersion"),
                (int)number(hash,"round"),(String)hash.get("status"),(int)number(hash,"slotTaken"),
                owner==null || owner.isEmpty()?null:Long.parseLong(owner));
        GrabProjectionState.Pending pending=null;
        if(hash.containsKey("reservationToken")) pending=new GrabProjectionState.Pending(number(hash,"requestDbId"),
                (String)hash.get("reservationToken"),number(hash,"baseVersion"),(int)number(hash,"pendingRound"),
                number(hash,"runnerId"),Instant.ofEpochMilli(number(hash,"leaseUntil")));
        return Optional.of(new GrabProjectionState(mode,committed,pending));
    }
    private static long number(Map<Object,Object> hash,String field) { return Long.parseLong((String)hash.get(field)); }
    private static long required(Long code) {
        if(code==null) throw new IllegalStateException("Redis 投影脚本未返回结果");
        return code;
    }
}
