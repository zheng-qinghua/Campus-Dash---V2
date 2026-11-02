package com.campusdash.it;
import com.campusdash.application.usecase.*;
import com.campusdash.domain.errand.model.*;
import com.campusdash.domain.errand.ports.*;
import com.campusdash.domain.grab.model.*;
import com.campusdash.domain.grab.ports.*;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.shared.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"dash.mq.enabled=false","dash.credit.max-ongoing=10000"})
class GrabResponseRecoveryIT {
    @Autowired ApplicationContext context;
    @Autowired ErrandRepository errands;
    @Autowired GrabProjectionSync sync;
    @Autowired GrabProjectionPort projection;
    @Autowired GrabRequestRepository requests;
    @Autowired GrabTransactionalStep commit;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired JdbcTemplate jdbc;
    private static BenchRun run;
    @BeforeAll static void before(){assertTrue(MiddlewareAvailable.check());run=BenchRun.start("GRAB-RESPONSE-RECOVERY");}
    @AfterAll static void after(){if(run!=null){run.finish("COMPLETED","{\"resultSource\":\"surefire XML\"}");run.close();}}
    GrabErrandUseCase instance(GrabTransactionalStep step,GrabProjectionPort redis,RealtimeNotifier notifier) {
        return instance(step,redis,notifier,requests);
    }
    GrabErrandUseCase instance(GrabTransactionalStep step,GrabProjectionPort redis,RealtimeNotifier notifier,
                               GrabRequestRepository requestReads) {
        return new GrabErrandUseCase(errands,requestReads,context.getBean(GrabRequestStep.class),step,redis,sync,
                context.getBean(CandidateQueuePort.class),context.getBean(CreditRepository.class),context.getBean(ErrandQueryPort.class),
                context.getBean(GrabRateLimiterPort.class),context.getBean(RunnerRateLimiterPort.class),ids,
                context.getBean(CacheEvictSupport.class),notifier,context.getBean(TimeoutTransferUseCase.class),40,10000);
    }
    GrabErrandUseCase.Command command(long runner){
        long id=ids.nextId();errands.insert(Errand.draft(id,1,1001,ErrandType.DELIVERY,"bench_响应恢复",Money.ofCents(100),1));
        errands.casPublish(id,0);sync.sync(id);run.track(id);
        return new GrabErrandUseCase.Command(id,runner,UUID.randomUUID().toString(),0);
    }
    @Test void committed_transaction_response_error_is_recovered_as_success() {
        var boundary=mock(GrabTransactionalStep.class);
        when(boundary.lockAndRecord(any())).thenAnswer(invocation->{assertTrue(commit.lockAndRecord(invocation.getArgument(0)));throw new IllegalStateException("模拟事务代理已提交但调用方丢失响应");});
        var cmd=command(77001);var result=instance(boundary,projection,mock(RealtimeNotifier.class)).grab(cmd);
        assertTrue(result.grabbed());assertEquals(200,result.httpStatus());
        assertEquals(1,errands.findById(cmd.errandId()).orElseThrow().slotTaken());
        context.getBean(GrabRecoveryUseCase.class).repairBatch(10000);
        assertEquals("LOCKED",projection.read(1,cmd.errandId()).orElseThrow().committed().status());
    }
    @Test void notification_failure_cannot_revoke_committed_owner() {
        var notifier=mock(RealtimeNotifier.class);doThrow(new IllegalStateException("通知不可用")).when(notifier).errandStatusChanged(anyLong(),anyLong(),any(),anyString(),anyInt());
        var cmd=command(77002);var result=instance(commit,projection,notifier).grab(cmd);
        assertTrue(result.grabbed());assertEquals(GrabRequestStatus.SUCCEEDED,requests.find(1,77002,cmd.requestId()).orElseThrow().status());
        assertEquals(77002L,projection.read(1,cmd.errandId()).orElseThrow().committed().ownerRunnerId());
    }
    @Test void redis_failure_after_registration_is_503_and_original_identity_is_preserved() {
        var broken=mock(GrabProjectionPort.class);when(broken.acquire(any())).thenThrow(new IllegalStateException("Redis不可用"));
        var cmd=command(77003);var service=instance(commit,broken,mock(RealtimeNotifier.class));
        assertEquals(503,service.grab(cmd).httpStatus());
        var saved=requests.find(1,77003,cmd.requestId()).orElseThrow();assertEquals(GrabRequestStatus.PROCESSING,saved.status());
        assertEquals(202,service.grab(cmd).httpStatus());assertEquals(saved.id(),requests.find(1,77003,cmd.requestId()).orElseThrow().id());
    }
    @Test void two_service_instances_share_one_execution_and_never_fake_early_success() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var paused=mock(GrabTransactionalStep.class);
        when(paused.lockAndRecord(any())).thenAnswer(invocation->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return commit.lockAndRecord(invocation.getArgument(0));});
        var first=instance(paused,projection,mock(RealtimeNotifier.class));var second=instance(commit,projection,mock(RealtimeNotifier.class));
        var cmd=command(77004);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var executing=pool.submit(()->first.grab(cmd));assertTrue(entered.await(10,TimeUnit.SECONDS));
            var duplicate=second.grab(cmd);assertEquals(202,duplicate.httpStatus());assertFalse(duplicate.grabbed());
            release.countDown();assertTrue(executing.get(10,TimeUnit.SECONDS).grabbed());assertTrue(second.grab(cmd).grabbed());
        } finally {release.countDown();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM grab_record WHERE errand_id=?",Integer.class,cmd.errandId()));
    }
    @Test void committed_registration_with_lost_read_does_not_allow_retry_to_reexecute_or_extend_lease() {
        var cmd=command(77005);
        var brokenRead=mock(GrabRequestRepository.class,delegatesTo(requests));
        doReturn(Optional.empty()).doThrow(new IllegalStateException("登记已提交，但首次回读失败"))
                .when(brokenRead).find(1,cmd.runnerId(),cmd.requestId());
        var uncertain=instance(commit,projection,mock(RealtimeNotifier.class),brokenRead);
        assertEquals(503,uncertain.grab(cmd).httpStatus());
        var saved=requests.find(1,cmd.runnerId(),cmd.requestId()).orElseThrow();
        assertEquals(GrabRequestStatus.PROCESSING,saved.status());
        var retry=instance(commit,projection,mock(RealtimeNotifier.class));
        assertEquals(202,retry.grab(cmd).httpStatus());
        assertEquals(saved.leaseUntil(),requests.findById(1,saved.id()).orElseThrow().leaseUntil());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM grab_record WHERE errand_id=?",Integer.class,cmd.errandId()));
        jdbc.update("UPDATE grab_request SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?",saved.id());
        assertEquals(ErrorCode.REQUEST_EXPIRED,retry.query(cmd.errandId(),cmd.runnerId(),cmd.requestId()).code());
        assertEquals(ErrorCode.REQUEST_EXPIRED,retry.grab(cmd).code());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM grab_request WHERE errand_id=?",Integer.class,cmd.errandId()));
    }
    @Test void lease_expiring_while_waiting_for_order_lock_rolls_back_every_business_fact() throws Exception {
        var cmd=command(77006);var e=errands.findById(cmd.errandId()).orElseThrow();
        var requestStep=context.getBean(GrabRequestStep.class);
        assertTrue(requestStep.register(new GrabRequest.NewRequest(ids.nextId(),e.campusId(),e.id(),cmd.runnerId(),
                cmd.requestId(),"a".repeat(64),e.round(),e.version(),UUID.randomUUID().toString(),30)));
        var initial=requests.find(1,cmd.runnerId(),cmd.requestId()).orElseThrow();
        var enteringCas=new CountDownLatch(1);
        var waitingRepository=mock(ErrandRepository.class,delegatesTo(errands));
        doAnswer(invocation->{
            enteringCas.countDown();
            return errands.casLockForRunner(invocation.getArgument(0),invocation.getArgument(1),
                    invocation.getArgument(2),invocation.getArgument(3));
        }).when(waitingRepository).casLockForRunner(anyLong(),anyLong(),anyLong(),anyInt());
        var step=new GrabTransactionalStep(waitingRepository,context.getBean(GrabRecordRepository.class),requests,
                context.getBean(TimeoutTransferStep.class),ids,context.getBean(CreditRepository.class),
                context.getBean(ErrandQueryPort.class),40,10000);
        var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var execution=new AtomicReference<Future<Boolean>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            jdbc.update("UPDATE grab_request SET lease_until=TIMESTAMPADD(SECOND,3,NOW(3)) WHERE id=?",initial.id());
            var leased=requests.findById(1,initial.id()).orElseThrow();
            assertEquals(GrabProjectionPort.AcquireResult.ACQUIRED,projection.acquire(leased));
            tx.executeWithoutResult(status->{
                // 另一个真实事务锁住订单；请求事务已持有请求锁后，只能在订单 CAS 上等待。
                jdbc.queryForObject("SELECT id FROM errand WHERE id=? FOR UPDATE",Long.class,e.id());
                execution.set(pool.submit(()->tx.execute(ignored->step.lockAndRecord(leased))));
                try {
                    assertTrue(enteringCas.await(5,TimeUnit.SECONDS),"必须确认请求进入 CAS 后再推进到租约截止");
                    assertFalse(execution.get().isDone(),"订单行锁应阻塞真实抢单 CAS");
                    long bound=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                    while(requests.databaseNow().isBefore(leased.leaseUntil())) {
                        assertTrue(System.nanoTime()<bound,"数据库时间应越过租约");
                        Thread.sleep(20);
                    }
                } catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
            });
            var failure=assertThrows(ExecutionException.class,()->execution.get().get(10,TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,failure.getCause());
        }
        var unchanged=errands.findById(e.id()).orElseThrow();
        assertEquals(ErrandStatus.PUBLISHED,unchanged.status());assertEquals(e.version(),unchanged.version());
        assertEquals(0,unchanged.slotTaken());assertNull(unchanged.grabberId());
        assertEquals(GrabRequestStatus.PROCESSING,requests.findById(1,initial.id()).orElseThrow().status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM grab_record WHERE errand_id=?",Integer.class,e.id()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM errand_status_log WHERE errand_id=?",Integer.class,e.id()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM local_message WHERE msg_key=?",Integer.class,
                DelayTaskPolicy.timeoutKey(e.id(),0)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM grab_repair_task WHERE event_key=?",Integer.class,
                "projection:"+e.id()+":"+(e.version()+1)));
        assertEquals(GrabRequestStatus.EXPIRED,requestStep.expire(initial).status());
        context.getBean(GrabRecoveryUseCase.class).repairBatch(10000);
        assertNull(projection.read(1,e.id()).orElseThrow().pending());
        var successor=new GrabErrandUseCase.Command(e.id(),77007,UUID.randomUUID().toString(),0);
        assertTrue(instance(commit,projection,mock(RealtimeNotifier.class)).grab(successor).grabbed());
    }
}
