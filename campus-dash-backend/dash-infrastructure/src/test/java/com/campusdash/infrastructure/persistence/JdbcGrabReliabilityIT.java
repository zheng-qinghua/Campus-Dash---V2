package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.grab.model.*;
import com.campusdash.domain.wallet.ports.FundEventPort;
import com.campusdash.infrastructure.config.RocketMqFundEventAdapter;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 专属随机测试库隔离：验证真实 MySQL 约束、时间和租约，不领取用户业务补偿。 */
class JdbcGrabReliabilityIT {
    private static JdbcTemplate admin,jdbc;
    private static String schema;
    private static DriverManagerDataSource dataSource;
    private static Path root;
    private static String mysqlHost;
    private static int mysqlPort;
    private JdbcGrabRequestRepository requests;
    private JdbcGrabRepairTaskRepository repairs;
    private TransactionTemplate tx;
    @BeforeAll static void initialize() throws Exception {
        String host=System.getProperty("it.mysql.host","127.0.0.1");
        int port=Integer.getInteger("it.mysql.port",3307);
        mysqlHost=host;mysqlPort=port;
        boolean available;
        try(Socket socket=new Socket()) {socket.connect(new InetSocketAddress(host,port),500);available=true;}
        catch(Exception e){available=false;}
        if(Boolean.getBoolean("requireMiddleware")) assertTrue(available,"必须提供真实 MySQL");
        Assumptions.assumeTrue(available,"真实 MySQL 不可用");
        String user=System.getProperty("it.mysql.user","root");
        String password=com.campusdash.shared.EnvironmentSecrets.required("SPRING_DATASOURCE_PASSWORD");
        String url="jdbc:mysql://"+host+":"+port+"/";
        String options="?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        admin=new JdbcTemplate(new DriverManagerDataSource(url+options,user,password));
        schema="grab_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema);
        dataSource=new DriverManagerDataSource(url+schema+options,user,password);
        jdbc=new JdbcTemplate(dataSource);
        root=Path.of("").toAbsolutePath();
        while(!root.resolve("docker/init.sql").toFile().exists()) root=root.getParent();
        try(Connection c=dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c,new FileSystemResource(root.resolve("docker/init.sql")));
            // 空库初始化后两次增量迁移：必须可重复执行，不重复建索引。
            ScriptUtils.executeSqlScript(c,new FileSystemResource(root.resolve("docker/migrate-grab-reliability.sql")));
            ScriptUtils.executeSqlScript(c,new FileSystemResource(root.resolve("docker/migrate-grab-reliability.sql")));
        }
    }
    @AfterAll static void cleanupSchema() {if(admin!=null && schema!=null) admin.execute("DROP DATABASE "+schema);}
    @BeforeEach void setup() {
        requests=new JdbcGrabRequestRepository(jdbc);repairs=new JdbcGrabRepairTaskRepository(jdbc);
        tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        // 只清理本测试独占的随机库，不接触业务数据库。
        jdbc.update("DELETE FROM grab_repair_task");jdbc.update("DELETE FROM grab_request");
    }
    private GrabRequest create(long id,int seconds) {
        assertTrue(requests.create(new GrabRequest.NewRequest(id,1,99,21,"req-"+id,"a".repeat(64),0,1,
                UUID.randomUUID().toString(),seconds)));
        return requests.findById(1,id).orElseThrow();
    }
    private GrabRepairTask task(long id) {
        return new GrabRepairTask(id,1,99,"apply:99:"+id,GrabRepairTask.APPLY_PROJECTION,id,0,null,null,"{}",0,null);
    }
    @Test void uniqueScopeSeparatesRunnerCampusAndUsesCaseSensitiveIds() {
        var r=create(1,30);
        assertFalse(requests.create(new GrabRequest.NewRequest(2,1,100,21,r.requestId(),"b".repeat(64),1,2,"b",30)));
        assertTrue(requests.create(new GrabRequest.NewRequest(3,2,100,21,r.requestId(),"b".repeat(64),1,2,"c",30)));
        assertTrue(requests.create(new GrabRequest.NewRequest(4,1,100,22,r.requestId(),"b".repeat(64),1,2,"d",30)));
        assertTrue(requests.create(new GrabRequest.NewRequest(5,1,100,21,r.requestId().toUpperCase(),"b".repeat(64),1,2,"e",30)));
        assertEquals(99,requests.find(1,21,r.requestId()).orElseThrow().errandId());
        assertTrue(r.leaseUntil().isAfter(requests.databaseNow()));
    }
    @Test void terminalResultCannotBeOverwrittenAndExpiredRequestCannotSucceed() {
        var r=create(1,30);
        tx.executeWithoutResult(s->{requests.lockById(1,1);assertTrue(requests.finish(1,1,r.reservationToken(),GrabRequestStatus.SUCCEEDED,"OK",200,"{}"));});
        assertFalse(requests.finish(1,1,r.reservationToken(),GrabRequestStatus.REJECTED,"NO",409,"{}"));
        var expired=create(2,30);
        jdbc.update("UPDATE grab_request SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=2");
        assertFalse(requests.finish(1,2,expired.reservationToken(),GrabRequestStatus.SUCCEEDED,"OK",200,"{}"));
        assertEquals(1,requests.findExpired(100).size());
    }
    @Test void expirationAndRevokeShareCommitAndRollback() {
        var r=create(1,30);jdbc.update("UPDATE grab_request SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=1");
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{
            requests.lockById(1,1);assertTrue(requests.finish(1,1,r.reservationToken(),GrabRequestStatus.EXPIRED,"EXPIRED",409,"{}"));
            repairs.ensurePending(task(10));throw new IllegalStateException("模拟事务中断");
        }));
        assertEquals(GrabRequestStatus.PROCESSING,requests.findById(1,1).orElseThrow().status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM grab_repair_task",Integer.class));
        tx.executeWithoutResult(s->{requests.lockById(1,1);assertTrue(requests.finish(1,1,r.reservationToken(),GrabRequestStatus.EXPIRED,"EXPIRED",409,"{}"));repairs.ensurePending(task(10));});
        assertEquals(GrabRequestStatus.EXPIRED,requests.findById(1,1).orElseThrow().status());
        assertEquals(1,repairs.claim(10,30).size());
    }
    @Test void rowLockSerializesCommitAgainstExpiry() throws Exception {
        var r=create(1,30);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->tx.execute(s->{requests.lockById(1,1);locked.countDown();
                try {assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                return requests.finish(1,1,r.reservationToken(),GrabRequestStatus.SUCCEEDED,"OK",200,"{}");}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var second=executor.submit(()->tx.execute(s->requests.lockById(1,1).orElseThrow().status()));
            Thread.sleep(80);assertFalse(second.isDone());release.countDown();assertTrue(first.get());
            assertEquals(GrabRequestStatus.SUCCEEDED,second.get());
        } finally {release.countDown();}
    }
    @Test void doneRepairReopensButActiveLeaseIsNotStolenAndLateWorkerCannotFinish() {
        var t=task(10);repairs.ensurePending(t);var first=repairs.claim(1,30).getFirst();
        repairs.ensurePending(t);assertTrue(repairs.claim(1,30).isEmpty());
        jdbc.update("UPDATE grab_repair_task SET locked_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=10");
        var second=repairs.claim(1,30).getFirst();assertNotEquals(first.workerToken(),second.workerToken());
        assertFalse(repairs.complete(first.id(),first.workerToken()));assertTrue(repairs.complete(second.id(),second.workerToken()));
        repairs.ensurePending(t);assertEquals(1,repairs.claim(1,30).size());
    }
    @Test void deadRepairNeedsExplicitReplayAndRetryCountIsExact() {
        repairs.ensurePending(task(10));var running=repairs.claim(1,30).getFirst();
        assertTrue(repairs.retry(10,running.workerToken(),"test",2));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM grab_repair_task WHERE id=10",String.class));
        jdbc.update("UPDATE grab_repair_task SET next_run_at=CURRENT_TIMESTAMP(3) WHERE id=10");
        running=repairs.claim(1,30).getFirst();assertTrue(repairs.retry(10,running.workerToken(),"test",2));
        assertEquals("DEAD",jdbc.queryForObject("SELECT status FROM grab_repair_task WHERE id=10",String.class));
        repairs.ensurePending(task(10));assertTrue(repairs.claim(1,30).isEmpty());
        assertTrue(repairs.replayDead(10));assertEquals(1,repairs.claim(1,30).size());
    }
    @Test void legacyMigrationBackfillsOnlyVerifiedHistoryAndCanRepeat() throws Exception {
        String legacySchema=schema+"_old";admin.execute("CREATE DATABASE "+legacySchema);
        try {
            var legacySource=new DriverManagerDataSource(dataSource.getUrl().replace("/"+schema+"?","/"+legacySchema+"?"),
                    dataSource.getUsername(),dataSource.getPassword());
            var legacyJdbc=new JdbcTemplate(legacySource);
            String original=java.nio.file.Files.readString(root.resolve("docker/init.sql"));
            String oldSchema=original.substring(0,original.indexOf("-- ==================== 抢单可靠性 v2 新增"));
            try(Connection c=legacySource.getConnection()) {
                ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ByteArrayResource(oldSchema.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                legacyJdbc.update("INSERT INTO errand(id,campus_id,publisher_id,grabber_id,type,title,reward_amount,status,slot_taken,locked_at) VALUES(1000,77,1,21,'DELIVERY','历史任务',1,'LOCKED',1,'2026-10-20 10:00:00.000')");
                legacyJdbc.update("INSERT INTO grab_record(id,campus_id,errand_id,runner_id,seq,round,result) VALUES(1010,77,1000,21,1,0,'GRABBED')");
                legacyJdbc.update("INSERT INTO local_message(id,msg_key,topic,payload,deliver_at,status,next_retry_at) VALUES(1020,'legacy','timeout','{\"errandId\":\"1000\"}',CURRENT_TIMESTAMP(3),'PENDING',CURRENT_TIMESTAMP(3))");
                legacyJdbc.update("INSERT INTO local_message(id,msg_key,topic,payload,deliver_at,status,next_retry_at) VALUES(1021,'poison','timeout','{\"errandId\":\"bad-value\"}',CURRENT_TIMESTAMP(3),'PENDING',CURRENT_TIMESTAMP(3))");
                ScriptUtils.executeSqlScript(c,new FileSystemResource(root.resolve("docker/migrate-grab-reliability.sql")));
                assertNull(legacyJdbc.queryForObject("SELECT confirm_deadline FROM errand WHERE id=1000",java.sql.Timestamp.class));
                try(var statement=c.createStatement()) {statement.execute("SET @legacy_confirm_timeout_seconds=300");}
                ScriptUtils.executeSqlScript(c,new FileSystemResource(root.resolve("docker/migrate-grab-reliability.sql")));
                ScriptUtils.executeSqlScript(c,new FileSystemResource(root.resolve("docker/migrate-grab-reliability.sql")));
            }
            assertEquals(300,legacyJdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,locked_at,confirm_deadline) FROM errand WHERE id=1000",Integer.class));
            assertEquals(77,legacyJdbc.queryForObject("SELECT campus_id FROM local_message WHERE id=1020",Integer.class));
            assertNull(legacyJdbc.queryForObject("SELECT campus_id FROM local_message WHERE id=1021",Long.class));
            assertNull(legacyJdbc.queryForObject("SELECT request_record_id FROM grab_record WHERE id=1010",Long.class));
            assertEquals(0,legacyJdbc.queryForObject("SELECT COUNT(*) FROM grab_request",Integer.class));
        } finally {admin.execute("DROP DATABASE "+legacySchema);}
    }

    @Test void lostCommitAcknowledgementLeavesDurableSuccessAndRepair() throws Exception {
        var r=create(1,30);
        jdbc.update("INSERT INTO errand(id,campus_id,publisher_id,type,title,reward_amount,status) VALUES(99,1,1,'DELIVERY','故障注入',1,'PUBLISHED')");
        try(var proxy=new LostCommitAckProxy(mysqlHost,mysqlPort)) {
            String proxyUrl="jdbc:mysql://127.0.0.1:"+proxy.port()+"/"+schema
                    +"?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=3000";
            var proxiedSource=new DriverManagerDataSource(proxyUrl,dataSource.getUsername(),dataSource.getPassword());
            var proxiedJdbc=new JdbcTemplate(proxiedSource);
            var proxiedRequests=new JdbcGrabRequestRepository(proxiedJdbc);
            var proxiedRepairs=new JdbcGrabRepairTaskRepository(proxiedJdbc);
            var proxiedTransaction=new TransactionTemplate(new DataSourceTransactionManager(proxiedSource));
            // 代理在 MySQL 真正返回 COMMIT 成功后断网，调用方必须感知到异常。
            assertThrows(org.springframework.transaction.TransactionException.class,()->proxiedTransaction.executeWithoutResult(status->{
                proxiedRequests.lockById(1,1);
                proxiedJdbc.update("UPDATE errand SET status='LOCKED',grabber_id=21,slot_taken=1,version=version+1 WHERE id=99 AND status='PUBLISHED'");
                proxiedJdbc.update("INSERT INTO grab_record(id,campus_id,errand_id,runner_id,seq,round,result,request_record_id) VALUES(1,1,99,21,1,0,'GRABBED',1)");
                assertTrue(proxiedRequests.finish(1,1,r.reservationToken(),GrabRequestStatus.SUCCEEDED,"OK",200,"{}"));
                proxiedRepairs.ensurePending(task(10));
            }));
            assertTrue(proxy.dropped.await(3,TimeUnit.SECONDS),"必须确实截断 COMMIT OK 响应");
        }
        assertEquals(GrabRequestStatus.SUCCEEDED,requests.findById(1,1).orElseThrow().status());
        assertEquals(1,jdbc.queryForObject("SELECT slot_taken FROM errand WHERE id=99",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM grab_record WHERE request_record_id=1",Integer.class));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM grab_repair_task WHERE id=10",String.class));
    }

    @Test void lostFundCommitAcknowledgementMustNotRollbackCommittedHalfMessage() throws Exception {
        String bizNo="settle:lost-commit:"+UUID.randomUUID();
        long sourceBefore=jdbc.queryForObject("SELECT available FROM wallet_account WHERE id=1001",Long.class);
        long targetBefore=jdbc.queryForObject("SELECT available FROM wallet_account WHERE id=2001",Long.class);
        var producer=mock(Producer.class);
        var halfTransaction=mock(Transaction.class);
        when(producer.beginTransaction()).thenReturn(halfTransaction);
        var adapter=new RocketMqFundEventAdapter(producer,ClientServiceProvider.loadService(),new JdbcWalletRepository(jdbc));
        var event=new FundEventPort.FundEvent(bizNo,"SETTLED",9099,1001,2001,100,0);
        try(var proxy=new LostCommitAckProxy(mysqlHost,mysqlPort)) {
            String proxyUrl="jdbc:mysql://127.0.0.1:"+proxy.port()+"/"+schema
                    +"?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=3000";
            var proxiedSource=new DriverManagerDataSource(proxyUrl,dataSource.getUsername(),dataSource.getPassword());
            var proxiedJdbc=new JdbcTemplate(proxiedSource);
            var proxiedTransaction=new TransactionTemplate(new DataSourceTransactionManager(proxiedSource));
            assertThrows(org.springframework.transaction.TransactionException.class,()->adapter.publishInTransaction(event,
                    ()->Boolean.TRUE.equals(proxiedTransaction.execute(status->{
                        assertEquals(1,proxiedJdbc.update("UPDATE wallet_account SET available=available-100,version=version+1 WHERE id=1001 AND available>=100"));
                        assertEquals(1,proxiedJdbc.update("UPDATE wallet_account SET available=available+100,version=version+1 WHERE id=2001"));
                        proxiedJdbc.update("""
                            INSERT INTO wallet_ledger(id,biz_no,account_id,user_id,direction,amount,balance_after,ref_type,ref_id)
                            VALUES(800001,?,1001,1001,'DEBIT',100,?,'SETTLE',9099),
                                  (800002,?,2001,2001,'CREDIT',100,?,'SETTLE',9099)
                            """,bizNo,sourceBefore-100,bizNo,targetBefore+100);
                        return true;
                    }))));
            assertTrue(proxy.dropped.await(3,TimeUnit.SECONDS),"必须截断真实 MySQL COMMIT OK");
        }
        // MySQL 已真实记账，但本地工作抛出提交异常；不能向 MQ 发出不可逆的 rollback。
        verify(producer).send(any(org.apache.rocketmq.client.apis.message.Message.class),same(halfTransaction));
        verifyNoInteractions(halfTransaction);
        assertEquals(sourceBefore-100,jdbc.queryForObject("SELECT available FROM wallet_account WHERE id=1001",Long.class));
        assertEquals(targetBefore+100,jdbc.queryForObject("SELECT available FROM wallet_account WHERE id=2001",Long.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger WHERE biz_no=?",Integer.class,bizNo));
        var view=mock(MessageView.class);
        when(view.getKeys()).thenReturn(java.util.List.of(bizNo));
        assertEquals(TransactionResolution.COMMIT,adapter.checker().check(view),"回查应以真实主库流水恢复资金事件");
    }

    /** 仅用于本测试的 MySQL 帧代理，禁止 TLS 后可精确丢弃 COMMIT 的服务端 OK 包。 */
    private static final class LostCommitAckProxy implements AutoCloseable {
        private final java.net.ServerSocket listener;
        private final java.util.concurrent.ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
        private final java.util.List<Socket> sockets=new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch dropped=new CountDownLatch(1);
        LostCommitAckProxy(String host,int port) throws java.io.IOException {
            listener=new java.net.ServerSocket(0,10,java.net.InetAddress.getLoopbackAddress());
            workers.submit(()->{
                try {
                    Socket client=listener.accept();Socket server=new Socket(host,port);
                    sockets.add(client);sockets.add(server);
                    var commit=new java.util.concurrent.atomic.AtomicBoolean();
                    workers.submit(()->forward(client,server,true,commit));
                    workers.submit(()->forward(server,client,false,commit));
                } catch(java.io.IOException closed) { /* 测试结束关闭监听。 */ }
            });
        }
        int port(){return listener.getLocalPort();}
        private void forward(Socket source,Socket target,boolean outbound,java.util.concurrent.atomic.AtomicBoolean commit) {
            try {
                var input=source.getInputStream();var output=target.getOutputStream();
                while(true) {
                    byte[] header=input.readNBytes(4);if(header.length!=4) return;
                    int length=(header[0]&255)|((header[1]&255)<<8)|((header[2]&255)<<16);
                    byte[] payload=input.readNBytes(length);if(payload.length!=length) return;
                    if(outbound && payload.length>1 && payload[0]==3) {
                        String sql=new String(payload,1,payload.length-1,java.nio.charset.StandardCharsets.UTF_8);
                        if("COMMIT".equalsIgnoreCase(sql.trim())) commit.set(true);
                    }
                    if(!outbound && commit.get() && payload.length>0 && payload[0]==0) {
                        dropped.countDown();source.close();target.close();return;
                    }
                    output.write(header);output.write(payload);output.flush();
                }
            } catch(java.io.IOException closed) { /* 断网就是本用例注入的故障。 */ }
        }
        @Override public void close() throws Exception {
            listener.close();for(Socket socket:sockets)socket.close();workers.shutdownNow();
            assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));
        }
    }

    @Test void auditCursorHasFixedUpperBoundAndOnlyOneWorkerAdvances() {
        jdbc.update("INSERT INTO errand(id,campus_id,publisher_id,type,title,reward_amount,status) VALUES(901,77,1,'DELIVERY','test',1,'PUBLISHED')");
        var cursors=new JdbcGrabAuditCursorRepository(jdbc);var c=cursors.loadOrStart(77,"PUBLISHED");
        jdbc.update("INSERT INTO errand(id,campus_id,publisher_id,type,title,reward_amount,status) VALUES(902,77,1,'DELIVERY','test',1,'PUBLISHED')");
        assertEquals(java.util.List.of(901L),cursors.nextIds(c,10));
        assertTrue(cursors.advance(c,901,true));assertFalse(cursors.advance(c,901,true));
        var next=cursors.loadOrStart(77,"PUBLISHED");assertEquals(902,next.upperId());
    }
}
