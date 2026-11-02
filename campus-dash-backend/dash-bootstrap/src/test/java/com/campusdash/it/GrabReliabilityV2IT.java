package com.campusdash.it;

import com.campusdash.application.usecase.*;
import com.campusdash.domain.errand.model.*;
import com.campusdash.domain.errand.ports.*;
import com.campusdash.domain.grab.model.*;
import com.campusdash.domain.grab.ports.*;
import com.campusdash.shared.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 MySQL/Redis 的应用事务与恢复链路，所有新任务按 run_id 登记。 */
@SpringBootTest(properties={"dash.mq.enabled=false","dash.credit.max-ongoing=10000",
        "dash.grab.runner-limit.rate=10000","dash.grab.runner-limit.burst=10000",
        "dash.grab.runner-limit.errand-rate=10000","dash.grab.runner-limit.errand-burst=10000"})
class GrabReliabilityV2IT {
    @Autowired ErrandRepository errands;
    @Autowired GrabErrandUseCase grab;
    @Autowired GrabRequestRepository requests;
    @Autowired GrabRequestStep requestStep;
    @Autowired GrabTransactionalStep commit;
    @Autowired GrabProjectionPort projection;
    @Autowired GrabProjectionSync sync;
    @Autowired GrabRecoveryUseCase recovery;
    @Autowired GrabRepairTaskRepository repairs;
    @Autowired CandidateQueuePort candidates;
    @Autowired TimeoutTransferUseCase timeout;
    @Autowired ConfirmErrandUseCase confirm;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    private static BenchRun run;
    @BeforeAll static void middleware() {
        assertTrue(MiddlewareAvailable.check(),"此验收必须运行真实 MySQL/Redis");
        run=BenchRun.start("GRAB-RELIABILITY-V2");
    }
    @AfterAll static void finish() {
        if(run!=null){run.finish("COMPLETED","{\"resultSource\":\"surefire XML\"}");run.close();}
    }
    long task() {
        long id=ids.nextId();
        errands.insert(Errand.draft(id,1,1001,ErrandType.DELIVERY,"bench_v2可靠性",Money.ofCents(100),1));
        assertEquals(1,errands.casPublish(id,0));run.track(id);sync.sync(id);return id;
    }
    GrabErrandUseCase.Command cmd(long id,long runner,int round) {
        return new GrabErrandUseCase.Command(id,runner,UUID.randomUUID().toString(),round);
    }
    GrabRequest registered(long id,long runner) {
        var e=errands.findById(id).orElseThrow();String requestId=UUID.randomUUID().toString();
        assertTrue(requestStep.register(new GrabRequest.NewRequest(ids.nextId(),1,id,runner,requestId,
                "a".repeat(64),e.round(),e.version(),UUID.randomUUID().toString(),30)));
        return requests.find(1,runner,requestId).orElseThrow();
    }
    void due(long id){jdbc.update("UPDATE errand SET confirm_deadline=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?",id);}
    void expire(GrabRequest r){jdbc.update("UPDATE grab_request SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?",r.id());}
    GrabProjectionState state(long id){return projection.read(1,id).orElseThrow();}
    @Test void concurrent_same_request_never_reports_uncommitted_success() throws Exception {
        long id=task();var command=cmd(id,76001,0);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);List<Future<GrabErrandUseCase.Result>> calls=new ArrayList<>();
            for(int i=0;i<24;i++) calls.add(pool.submit(()->{gate.await();return grab.grab(command);}));
            gate.countDown();
            for(var call:calls) {
                var r=call.get(30,TimeUnit.SECONDS);
                assertTrue(r.httpStatus()==200 || r.httpStatus()==202 || r.httpStatus()==503,"意外结果 "+r);
                if(r.httpStatus()==503) {
                    assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,r.code());
                    assertEquals(command.requestId(),r.requestId());
                    assertTrue(r.retryAfterMillis()>0);
                }
                assertEquals(r.httpStatus()==200,r.grabbed());
            }
        }
        assertTrue(grab.query(id,76001,command.requestId()).grabbed());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM grab_record WHERE errand_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM grab_request WHERE errand_id=?",Integer.class,id));
    }
    @Test void request_binding_survives_round_change_and_cannot_cross_task_or_actor() {
        long id=task();var command=cmd(id,76002,0);assertTrue(grab.grab(command).grabbed());
        due(id);assertEquals(TimeoutTransferUseCase.Outcome.REVERTED,timeout.handleTimeout(id,0));
        var replay=grab.grab(command);assertTrue(replay.grabbed());assertTrue(replay.replayed());
        assertEquals(0,replay.appliedRound());assertEquals(1,errands.findById(id).orElseThrow().round());
        assertEquals(ErrorCode.REQUEST_CONFLICT,grab.grab(new GrabErrandUseCase.Command(id,76002,command.requestId(),1)).code());
        assertEquals(ErrorCode.REQUEST_CONFLICT,grab.grab(new GrabErrandUseCase.Command(task(),76002,command.requestId(),0)).code());
        assertEquals(404,grab.query(id,76003,command.requestId()).httpStatus());
    }
    @Test void transaction_contains_request_record_timeout_and_projection() {
        long id=task();var r=registered(id,76004);assertEquals(GrabProjectionPort.AcquireResult.ACQUIRED,projection.acquire(r));
        assertTrue(commit.lockAndRecord(r));
        assertEquals(GrabRequestStatus.SUCCEEDED,requests.findById(1,r.id()).orElseThrow().status());
        assertEquals(r.id(),jdbc.queryForObject("SELECT request_record_id FROM grab_record WHERE errand_id=?",Long.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM local_message WHERE msg_key=? AND campus_id=1",Integer.class,DelayTaskPolicy.timeoutKey(id,0)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM grab_repair_task WHERE event_key=?",Integer.class,"projection:"+id+":2"));
        assertNotNull(errands.findById(id).orElseThrow().confirmDeadline());
        // 模拟提交后进程消失，没有执行在线 Redis 同步。
        assertEquals("PUBLISHED",state(id).committed().status());assertNotNull(state(id).pending());
        recovery.repairBatch(10000);
        assertEquals("LOCKED",state(id).committed().status());assertNull(state(id).pending());
    }
    @Test void crash_after_acquire_is_recovered_and_late_thread_cannot_commit() {
        long id=task();var r=registered(id,76005);assertEquals(GrabProjectionPort.AcquireResult.ACQUIRED,projection.acquire(r));
        expire(r);recovery.expireRequests(10000);recovery.repairBatch(10000);
        assertEquals(GrabRequestStatus.EXPIRED,requests.findById(1,r.id()).orElseThrow().status());
        assertNull(state(id).pending());assertFalse(commit.lockAndRecord(r));
        assertEquals(GrabProjectionPort.AcquireResult.REVOKED,projection.acquire(r));
        assertTrue(grab.grab(cmd(id,76006,0)).grabbed());
    }
    @Test void expiration_before_late_acquire_writes_tombstone_even_without_pending() {
        long id=task();var r=registered(id,76007);expire(r);
        requestStep.expire(r);recovery.repairBatch(10000);
        assertEquals(GrabProjectionPort.AcquireResult.REVOKED,projection.acquire(r));assertFalse(commit.lockAndRecord(r));
    }
    @Test void pending_request_query_is_202_and_audit_preserves_it() {
        long id=task();var r=registered(id,76008);projection.acquire(r);
        var response=grab.query(id,76008,r.requestId());assertEquals(202,response.httpStatus());assertFalse(response.grabbed());
        recovery.auditOne(1,id);recovery.repairBatch(10000);
        assertEquals(r.reservationToken(),state(id).pending().reservationToken());
    }
    @Test void transfer_then_revert_then_third_runner_can_grab() {
        long id=task();assertTrue(grab.grab(cmd(id,76009,0)).grabbed());
        assertEquals(ErrorCode.SLOT_FULL,grab.grab(cmd(id,76010,0)).code());
        due(id);assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED,timeout.handleTimeout(id,0));
        assertEquals(76010L,state(id).committed().ownerRunnerId());assertEquals(1,state(id).committed().round());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM grab_record WHERE errand_id=?",Integer.class,id));
        due(id);assertEquals(TimeoutTransferUseCase.Outcome.REVERTED,timeout.handleTimeout(id,1));
        assertEquals("PUBLISHED",state(id).committed().status());assertEquals(0,state(id).committed().slotTaken());
        assertTrue(grab.grab(cmd(id,76011,2)).grabbed());
        assertEquals(TimeoutTransferUseCase.Outcome.SKIPPED,timeout.handleTimeout(id,0));
        assertEquals(76011L,errands.findById(id).orElseThrow().grabberId());
    }
    @Test void early_message_does_not_pop_candidate_and_confirm_wins_once() {
        long id=task();assertTrue(grab.grab(cmd(id,76012,0)).grabbed());candidates.offer(id,76013,1);
        assertEquals(TimeoutTransferUseCase.Outcome.SKIPPED,timeout.handleTimeout(id,0));assertEquals(1,candidates.size(id));
        confirm.confirm(new ConfirmErrandUseCase.Command(id,76012));
        assertEquals(TimeoutTransferUseCase.Outcome.SKIPPED,timeout.handleTimeout(id,0));
        recovery.repairBatch(10000);assertEquals("ACCEPTED",state(id).committed().status());
    }
    @Test void missing_projection_rebuilds_from_current_database_and_old_pending_cannot_win() {
        long id=task();var r=registered(id,76014);projection.acquire(r);assertTrue(commit.lockAndRecord(r));
        redis.delete("dash:grab:v2:{"+id+"}:state");sync.sync(id);
        assertEquals("LOCKED",state(id).committed().status());assertEquals(76014L,state(id).committed().ownerRunnerId());
        assertFalse(grab.grab(cmd(id,76015,0)).grabbed());
    }
    @Test void repeated_same_version_corruption_reopens_completed_repair() {
        long id=task();recovery.repairBatch(10000);
        for(int i=0;i<2;i++) {
            redis.opsForHash().put("dash:grab:v2:{"+id+"}:state","slotTaken","1");
            recovery.auditOne(1,id);recovery.repairBatch(10000);
            assertEquals(0,state(id).committed().slotTaken());
        }
    }
    @Test void cancel_after_reservation_prevents_commit_and_repairs_projection() {
        long id=task();var r=registered(id,76016);projection.acquire(r);
        assertEquals(1,errands.casCancel(id,1));assertFalse(commit.lockAndRecord(r));
        requestStep.reject(r,ErrorCode.ERRAND_NOT_GRABBABLE);recovery.repairBatch(10000);
        assertEquals("CANCELLED",state(id).committed().status());assertNull(state(id).pending());
    }
    @Test void new_tasks_reject_multiple_slots_and_invalid_request_identity() {
        assertThrows(IllegalArgumentException.class,()->Errand.draft(ids.nextId(),1,1001,ErrandType.BUY,"多名额",Money.ofCents(100),2));
        assertEquals(400,grab.grab(new GrabErrandUseCase.Command(123,1,"not-a-uuid",0)).httpStatus());
    }
    @Test void publisher_cannot_bypass_hidden_grab_action_or_transaction_guard() {
        long id=task();var command=cmd(id,1001,0);
        var rejected=grab.grab(command);
        assertEquals(ErrorCode.ERRAND_NOT_GRABBABLE,rejected.code());assertEquals(409,rejected.httpStatus());
        assertEquals("REJECTED",rejected.status());
        var replay=grab.grab(command);assertEquals(rejected.code(),replay.code());assertTrue(replay.replayed());
        assertEquals(0,candidates.size(id),"发单人不能绕过抢单接口进入候选流转");
        var bypass=registered(id,1001);
        assertEquals(GrabProjectionPort.AcquireResult.ACQUIRED,projection.acquire(bypass));
        assertFalse(commit.lockAndRecord(bypass),"直接进入事务步骤也不能抢自己的任务");
        requestStep.reject(bypass,ErrorCode.ERRAND_NOT_GRABBABLE);
        recovery.repairBatch(10000);
        assertEquals(ErrandStatus.PUBLISHED,errands.findById(id).orElseThrow().status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM grab_record WHERE errand_id=?",Integer.class,id));
        assertTrue(grab.grab(cmd(id,76017,0)).grabbed());
    }
}
