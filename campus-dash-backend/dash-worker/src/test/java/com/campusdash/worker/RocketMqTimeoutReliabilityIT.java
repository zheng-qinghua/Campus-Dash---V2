package com.campusdash.worker;

import com.campusdash.application.usecase.*;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.*;
import com.campusdash.domain.grab.ports.GrabRepairTaskRepository;
import com.campusdash.infrastructure.cache.*;
import com.campusdash.infrastructure.config.RocketMqDelayMessageAdapter;
import com.campusdash.infrastructure.persistence.*;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.apache.rocketmq.client.apis.*;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.*;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** 真 RocketMQ + MySQL + Redis；独占 topic/group/数据库，不启动常驻 Worker 或定时扫描。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RocketMqTimeoutReliabilityIT {
    private JdbcTemplate admin,jdbc;
    private String schema,topic,group;
    private AnnotationConfigApplicationContext context;
    private LettuceConnectionFactory redisConnection;
    private StringRedisTemplate redis;
    private ErrandRepository errands;
    private JdbcLocalMessageRepository localMessages;
    private TimeoutTransferStep step;
    private RedisCandidateQueueAdapter candidates;
    private RedisGrabProjectionAdapter projection;
    private GrabProjectionSync sync;
    private TransactionTemplate transaction;
    private ClientServiceProvider provider;
    private ClientConfiguration configuration;
    private Producer producer;
    private RocketMqDelayMessageAdapter realDelay;
    private TimeoutTransferConsumer consumer;
    private ObservedUseCase useCase;
    private final SnowflakeIdGenerator ids=new SnowflakeIdGenerator(1020);
    private final List<Long> taskIds=new ArrayList<>();
    private final Queue<Observation> observations=new ConcurrentLinkedQueue<>();
    private boolean topicCreated,groupCreated;
    @Configuration @EnableTransactionManagement(proxyTargetClass=true) static class Transactions {}

    @BeforeAll void start() throws Exception {
        boolean available=portOpen("127.0.0.1",3307)&&portOpen("127.0.0.1",6380)&&portOpen("127.0.0.1",8081);
        if(Boolean.getBoolean("requireMQ")) assertTrue(available,"真实 MQ/MySQL/Redis 为本项验收必需");
        Assumptions.assumeTrue(available,"真实 MQ/MySQL/Redis 不可用");
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,16);
        topic="grab-timeout-it-"+suffix;group="grab-timeout-it-group-"+suffix;
        mqadmin("updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t "+topic+" -a +message.type=DELAY");topicCreated=true;
        mqadmin("updateSubGroup -n rmqnamesrv:9876 -c DefaultCluster -g "+group);groupCreated=true;
        String user=System.getProperty("it.mysql.user","root"),password=com.campusdash.shared.EnvironmentSecrets.required("SPRING_DATASOURCE_PASSWORD");
        String prefix="jdbc:mysql://127.0.0.1:3307/",options="?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        admin=new JdbcTemplate(new DriverManagerDataSource(prefix+options,user,password));
        schema="mq_grab_it_"+suffix;admin.execute("CREATE DATABASE "+schema);
        var source=new DriverManagerDataSource(prefix+schema+options,user,password);jdbc=new JdbcTemplate(source);
        Path root=Path.of("").toAbsolutePath();while(!root.resolve("docker/init.sql").toFile().exists())root=root.getParent();
        try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(root.resolve("docker/init.sql")));}
        redisConnection=redisConnectionFactory("127.0.0.1", 6380);redisConnection.afterPropertiesSet();redisConnection.start();
        redis=new StringRedisTemplate(redisConnection);redis.afterPropertiesSet();
        candidates=new RedisCandidateQueueAdapter(redis);projection=new RedisGrabProjectionAdapter(redis);
        localMessages=new JdbcLocalMessageRepository(jdbc);
        var manager=new DataSourceTransactionManager(source);transaction=new TransactionTemplate(manager);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean("transactionManager",DataSourceTransactionManager.class,()->manager);
        context.registerBean(GrabRepairTaskRepository.class,()->new JdbcGrabRepairTaskRepository(jdbc));
        context.registerBean(ErrandRepository.class,()->new JdbcErrandRepository(jdbc,context.getBean(GrabRepairTaskRepository.class),ids,3));
        context.registerBean(TimeoutTransferStep.class,()->new TimeoutTransferStep(context.getBean(ErrandRepository.class),localMessages,ids,new JdbcGrabRecordRepository(jdbc),topic));
        context.refresh();errands=context.getBean(ErrandRepository.class);step=context.getBean(TimeoutTransferStep.class);
        sync=new GrabProjectionSync(errands,projection);
        provider=ClientServiceProvider.loadService();configuration=ClientConfiguration.newBuilder()
                .setEndpoints("127.0.0.1:8081").setRequestTimeout(Duration.ofSeconds(5)).build();
        producer=provider.newProducerBuilder().setClientConfiguration(configuration).setTopics(topic).build();
        realDelay=new RocketMqDelayMessageAdapter(provider,producer);useCase=new ObservedUseCase(realDelay);
        consumer=new TimeoutTransferConsumer(provider,configuration,useCase,topic,group);consumer.start();
    }
    @AfterAll void stop() throws Exception {
        // 先停止所有消息线程，再清理本测试专属资源，避免后台回调写进已删除库。
        if(consumer!=null)consumer.stop();if(producer!=null)producer.close();
        if(context!=null)context.close();
        if(redis!=null)for(long id:taskIds)redis.delete(List.of(RedisGrabProjectionAdapter.stateKey(id),
                "errand:candidates:{"+id+"}","errand:candidate-meta:{"+id+"}"));
        if(redisConnection!=null)redisConnection.destroy();
        if(admin!=null && schema!=null)admin.execute("DROP DATABASE "+schema);
        if(groupCreated)mqadmin("deleteSubGroup -n rmqnamesrv:9876 -c DefaultCluster -g "+group);
        if(topicCreated)mqadmin("deleteTopic -n rmqnamesrv:9876 -c DefaultCluster -t "+topic);
    }
    @Test void realDelayedMessagesRejectEarlyThenTransferAndReturnExactlyOnce() throws Exception {
        long id=seedLocked(10_000);candidates.offer(id,2002,1);
        var send=step.currentTimeout(id);assertEquals(topic,send.topic());
        realDelay.send(topic,"early-"+id,send.payload(),Instant.now().plusSeconds(1));
        realDelay.send(topic,send.msgKey(),send.payload(),send.deliverAt());
        await(8_000,()->observed(id,0,TimeoutTransferUseCase.Outcome.SKIPPED));
        assertEquals(0,current(id).round());assertEquals(2001L,current(id).grabberId());
        await(20_000,()->current(id).round()==1);
        assertEquals(2002L,current(id).grabberId());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM grab_record WHERE errand_id=? AND round=1 AND runner_id=2002",Integer.class,id));
        var next=step.currentTimeout(id);assertEquals(current(id).confirmDeadline(),next.deliverAt());
        realDelay.send(topic,"duplicate-old-"+id,send.payload(),Instant.now().plusSeconds(1));
        await(20_000,()->current(id).status()==ErrandStatus.PUBLISHED);
        assertEquals(2,current(id).round());assertEquals(0,current(id).slotTaken());assertNull(current(id).grabberId());
        await(5_000,()->observed(id,1,TimeoutTransferUseCase.Outcome.REVERTED));
        var state=projection.read(77,id).orElseThrow();assertEquals("PUBLISHED",state.committed().status());
        assertEquals(0,state.committed().slotTaken());assertNull(state.committed().ownerRunnerId());
        realDelay.send(topic,"duplicate-new-"+id,next.payload(),Instant.now().plusSeconds(1));
        await(8_000,()->observed(id,1,TimeoutTransferUseCase.Outcome.SKIPPED));
        assertEquals(3,current(id).version());assertEquals(2,current(id).round());
        assertTrue(observed(id,0,TimeoutTransferUseCase.Outcome.TRANSFERRED));
    }
    @Test void legacyPayloadWithoutCampusVersionOrDeadlineStillUsesDatabaseDeadline() throws Exception {
        long id=seedLocked(2_000);
        String legacy="{\"errandId\":"+id+",\"round\":0}";
        realDelay.send(topic,"legacy-"+id,legacy,current(id).confirmDeadline());
        await(15_000,()->observed(id,0,TimeoutTransferUseCase.Outcome.REVERTED));
        assertEquals(ErrandStatus.PUBLISHED,current(id).status());assertEquals(1,current(id).round());
    }
    @Test void unavailableProducerLeavesPendingMessageAndDatabaseScanRecovers() throws Exception {
        long id=seedLocked(0);
        Producer unavailable=provider.newProducerBuilder().setClientConfiguration(configuration).build();unavailable.close();
        var fallback=new ObservedUseCase(new RocketMqDelayMessageAdapter(provider,unavailable));
        fallback.scheduleFirstTimeout(id,0,1);
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM local_message WHERE msg_key=?",String.class,TimeoutPolicy.msgKey(id,0)));
        assertEquals(ErrandStatus.LOCKED,current(id).status());
        new TimeoutScanJob(errands,fallback,0).scan();
        assertEquals(ErrandStatus.PUBLISHED,current(id).status());assertEquals(1,current(id).round());
        new TimeoutScanJob(errands,fallback,0).scan();assertEquals(1,current(id).round());
    }
    private long seedLocked(long delayMillis) {
        long id=ids.nextId();taskIds.add(id);
        transaction.executeWithoutResult(s->{
            jdbc.update("""
                    INSERT INTO errand(id,campus_id,publisher_id,grabber_id,type,title,reward_amount,
                    slot_total,slot_taken,status,round,version,locked_at,confirm_deadline)
                    VALUES(?,77,1001,2001,'DELIVERY','MQ隔离验收',100,1,1,'LOCKED',0,1,NOW(3),TIMESTAMPADD(MICROSECOND,?,NOW(3)))
                    """,id,delayMillis*1000);
            jdbc.update("INSERT INTO grab_record(id,campus_id,errand_id,runner_id,seq,round,result) VALUES(?,77,?,2001,1,0,'GRABBED')",ids.nextId(),id);
            step.enqueueCurrentTimeout(id);
        });
        sync.sync(id);return id;
    }
    private Errand current(long id){return errands.findById(id).orElseThrow();}
    private boolean observed(long id,int round,TimeoutTransferUseCase.Outcome outcome){
        return observations.stream().anyMatch(o->o.id==id && o.round==round && o.outcome==outcome);
    }
    private static void await(long timeoutMillis,BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(!condition.getAsBoolean() && System.nanoTime()<until)Thread.sleep(50);
        assertTrue(condition.getAsBoolean(),"等待真实消息或状态超时："+timeoutMillis+"ms");
    }
    private static boolean portOpen(String host,int port){
        try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),500);return true;}catch(Exception e){return false;}
    }
    private static void mqadmin(String args) throws Exception {
        String docker=System.getProperty("it.docker",new java.io.File("/usr/local/bin/docker").exists()?"/usr/local/bin/docker":"docker");
        Process process=new ProcessBuilder(docker,"exec","dash-rmqbroker","sh","-c",
                "cd /home/rocketmq/rocketmq-*/bin && sh mqadmin "+args).redirectErrorStream(true).start();
        var output=CompletableFuture.supplyAsync(()->{try{return new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){return e.toString();}});
        if(!process.waitFor(45,TimeUnit.SECONDS)){process.destroyForcibly();fail("MQ管理命令超时");}
        assertEquals(0,process.exitValue(),output.get(2,TimeUnit.SECONDS));
    }
    private record Observation(long id,int round,TimeoutTransferUseCase.Outcome outcome){}
    private final class ObservedUseCase extends TimeoutTransferUseCase {
        ObservedUseCase(DelayMessagePort delay) {
            super(RocketMqTimeoutReliabilityIT.this.errands,RocketMqTimeoutReliabilityIT.this.candidates,
                    RocketMqTimeoutReliabilityIT.this.sync,delay,RocketMqTimeoutReliabilityIT.this.localMessages,
                    RocketMqTimeoutReliabilityIT.this.step,
                    new CacheEvictSupport(new NoCache(),(id,time)->{},500,false,"AFTER_COMMIT"),
                    new JdbcCreditRepository(jdbc),ids,3,5,new JdbcErrandQueryAdapter(jdbc),40,5);
        }
        @Override public Outcome handleTimeout(long id,int round){
            Outcome outcome=super.handleTimeout(id,round);observations.add(new Observation(id,round,outcome));return outcome;
        }
    }
    private static final class NoCache implements ErrandCachePort {
        public Optional<CachedErrand> get(long id){return Optional.empty();}
        public void put(long id,String json){}public void putEmpty(long id){}public void evict(long id){}
        public boolean tryAcquireRebuild(long id){return false;}public void releaseRebuild(long id){}
        public boolean mightExist(long id){return true;}public void registerExisting(long id){}
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
