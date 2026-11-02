package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.*;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.model.*;
import com.campusdash.domain.grab.ports.*;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GrabRecoveryUseCaseTest {
    @Test void succeededPendingIsRevokedEvenWhenCommittedVersionAlreadyMatches() {
        var fixture=new Fixture(GrabRequestStatus.SUCCEEDED);
        fixture.useCase.auditOne(1,99);
        assertTrue(fixture.repairs.stream().anyMatch(t->t.eventType().equals(GrabRepairTask.REVOKE_PENDING)));
        fixture.useCase.repairBatch(10);
        assertEquals(List.of(fixture.request),fixture.revoked);
        assertEquals(GrabProjection.from(fixture.errand),fixture.state.committed());
        assertEquals(2,fixture.completed.get());
    }
    @Test void validProcessingPendingIsLeftUntouched() {
        var fixture=new Fixture(GrabRequestStatus.PROCESSING);
        fixture.useCase.auditOne(1,99);
        assertTrue(fixture.repairs.isEmpty());assertTrue(fixture.revoked.isEmpty());
    }
    @Test void commitBetweenAuditReadAndStaleRequestRejectionPreservesSuccess() {
        var fixture=new Fixture(GrabRequestStatus.PROCESSING);
        // 审计先看见旧订单和 PROCESSING，请求事务随后提交；行锁读取必须以成功终态为准。
        fixture.latestErrand=Fixture.errand(99,ErrandStatus.LOCKED,2);
        fixture.lockedRequest=fixture.succeeded();
        fixture.useCase.auditOne(1,99);
        assertEquals(1,fixture.requestLocks.get());
        assertTrue(fixture.repairs.isEmpty(),"成功事务负责投影，陈旧审计不能另行终结请求");
        assertTrue(fixture.revoked.isEmpty(),"不能把审计的旧 PROCESSING 快照当作失败");
    }
    @Test void commitBetweenAuditReadAndExpiryPreservesSuccess() {
        var fixture=new Fixture(GrabRequestStatus.PROCESSING);
        // 成功在租约内提交；审计随后因旧快照且时钟已越过截止时间尝试过期。
        fixture.lockedRequest=fixture.succeeded();
        fixture.databaseClock=fixture.request.leaseUntil().plusMillis(1);
        fixture.useCase.auditOne(1,99);
        assertEquals(1,fixture.requestLocks.get());
        assertTrue(fixture.repairs.isEmpty(),"已成功请求不得被改写为 EXPIRED");
        assertTrue(fixture.revoked.isEmpty());
    }
    @Test void corruptedPendingIdentityOrDeadlineIsNotBlindlyRevoked() {
        var fixture=new Fixture(GrabRequestStatus.SUCCEEDED);
        var r=fixture.request;
        var invalid=List.of(
            new GrabProjectionState.Pending(r.id(),"wrong-token",r.baseErrandVersion(),r.requestRound(),r.runnerId(),r.leaseUntil()),
            new GrabProjectionState.Pending(r.id(),r.reservationToken(),r.baseErrandVersion()+1,r.requestRound(),r.runnerId(),r.leaseUntil()),
            new GrabProjectionState.Pending(r.id(),r.reservationToken(),r.baseErrandVersion(),r.requestRound()+1,r.runnerId(),r.leaseUntil()),
            new GrabProjectionState.Pending(r.id(),r.reservationToken(),r.baseErrandVersion(),r.requestRound(),r.runnerId()+1,r.leaseUntil()),
            new GrabProjectionState.Pending(r.id(),r.reservationToken(),r.baseErrandVersion(),r.requestRound(),r.runnerId(),r.leaseUntil().plusMillis(1)));
        for(var pending:invalid) {
            fixture.state=new GrabProjectionState("READY",GrabProjection.from(fixture.errand),pending);
            assertThrows(IllegalStateException.class,()->fixture.useCase.auditOne(1,99));
        }
        assertTrue(fixture.repairs.isEmpty());assertTrue(fixture.revoked.isEmpty());
    }
    @Test void poisonPendingIsDurablyIsolatedAndDoesNotBlockTheNextPage() {
        var fixture=new Fixture(GrabRequestStatus.SUCCEEDED);
        fixture.missingRequest=true;fixture.twoIds=true;
        fixture.useCase.audit(1);
        assertTrue(fixture.repairs.stream().anyMatch(t->t.errandId()==99
                && t.eventType().equals(GrabRepairTask.AUDIT_PROJECTION)));
        assertEquals(99,fixture.publishedLastId);
        fixture.useCase.audit(1);
        assertTrue(fixture.repairs.stream().anyMatch(t->t.errandId()==100));
        assertEquals(2,fixture.advancedPublished.get());
        fixture.useCase.repairBatch(10);
        assertEquals(1,fixture.retried.get(),"未知占位仅重试诊断，不盲目撤销");
        assertTrue(fixture.revoked.isEmpty());
    }
    @Test void auditPersistenceFailureRetainsPageForRetry() {
        var fixture=new Fixture(GrabRequestStatus.SUCCEEDED);
        fixture.missingRequest=true;fixture.twoIds=true;fixture.failAuditPersistence=true;
        fixture.useCase.audit(1);
        assertEquals(0,fixture.advancedPublished.get());
        assertEquals(0,fixture.publishedLastId);
    }
    @Test void staleRepairPayloadCannotRevokeAnotherTask() {
        var fixture=new Fixture(GrabRequestStatus.SUCCEEDED);
        var r=fixture.request;
        fixture.repairs.add(new GrabRepairTask(1,1,100,"wrong",GrabRepairTask.REVOKE_PENDING,
                r.baseErrandVersion(),r.requestRound(),r.id(),r.reservationToken(),"{}",0,"worker"));
        fixture.useCase.repairBatch(10);
        assertTrue(fixture.revoked.isEmpty());assertEquals(1,fixture.retried.get());assertEquals(0,fixture.completed.get());
    }

    /** 假端口只记录编排动作；Lua 原子性由 RedisGrabProjectionIT 运行真实 Redis 验证。 */
    private static final class Fixture {
        final GrabRequest request;
        final Errand errand;
        Errand latestErrand;
        GrabRequest lockedRequest;
        Instant databaseClock;
        GrabProjectionState state;
        final List<GrabRepairTask> repairs=new ArrayList<>();
        final List<GrabRequest> revoked=new ArrayList<>();
        final AtomicInteger completed=new AtomicInteger(),retried=new AtomicInteger(),advancedPublished=new AtomicInteger();
        final AtomicInteger errandReads=new AtomicInteger(),requestLocks=new AtomicInteger();
        boolean missingRequest,twoIds,failAuditPersistence;
        long publishedLastId;
        final GrabRecoveryUseCase useCase;
        Fixture(GrabRequestStatus requestStatus) {
            Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            boolean succeeded=requestStatus==GrabRequestStatus.SUCCEEDED;
            errand=errand(99,succeeded?ErrandStatus.LOCKED:ErrandStatus.PUBLISHED,succeeded?2:1);
            request=new GrabRequest(55,1,99,21,UUID.randomUUID().toString(),"a".repeat(64),0,1,
                    requestStatus,UUID.randomUUID().toString(),now.plusSeconds(30),succeeded?"OK":null,
                    succeeded?200:0,succeeded?"{}":null,now,succeeded?now:null);
            lockedRequest=request;databaseClock=now;
            state=new GrabProjectionState("READY",GrabProjection.from(errand),new GrabProjectionState.Pending(
                    request.id(),request.reservationToken(),1,0,21,request.leaseUntil()));
            ErrandRepository errands=port(ErrandRepository.class,(method,args)->{
                if(method.equals("findById")) {
                    if((long)args[0]!=99) return Optional.of(errand(100,ErrandStatus.PUBLISHED,1));
                    return Optional.of(errandReads.incrementAndGet()>1 && latestErrand!=null?latestErrand:errand);
                }
                throw new AssertionError(method);
            });
            GrabRequestRepository requests=port(GrabRequestRepository.class,(method,args)->switch(method) {
                case "findById" -> missingRequest?Optional.empty():Optional.of(request);
                case "lockById" -> {requestLocks.incrementAndGet();yield Optional.of(lockedRequest);}
                case "databaseNow" -> databaseClock;
                default -> throw new AssertionError(method);
            });
            GrabRepairTaskRepository taskRepository=port(GrabRepairTaskRepository.class,(method,args)->{
                switch(method) {
                    case "ensurePending":
                        var task=(GrabRepairTask)args[0];
                        if(failAuditPersistence && task.eventType().equals(GrabRepairTask.AUDIT_PROJECTION))
                            throw new IllegalStateException("模拟审计任务写入失败");
                        repairs.add(task);return null;
                    case "claim":return List.copyOf(repairs);
                    case "complete":completed.incrementAndGet();return true;
                    case "retry":retried.incrementAndGet();return true;
                    default:throw new AssertionError(method);
                }
            });
            GrabProjectionPort projection=port(GrabProjectionPort.class,(method,args)->{
                switch(method) {
                    case "read":return (long)args[1]==99?Optional.of(state):Optional.empty();
                    case "apply":return GrabProjectionPort.ApplyResult.UNCHANGED;
                    case "revoke":revoked.add((GrabRequest)args[0]);return null;
                    default:throw new AssertionError(method);
                }
            });
            GrabAuditCursorRepository cursors=port(GrabAuditCursorRepository.class,(method,args)->{
                switch(method) {
                    case "campuses":return List.of(1L);
                    case "loadOrStart":return new GrabAuditCursorRepository.Cursor(1,(String)args[1],
                            args[1].equals("PUBLISHED")?publishedLastId:0,100,0);
                    case "nextIds":
                        var cursor=(GrabAuditCursorRepository.Cursor)args[0];
                        return twoIds && cursor.taskStatus().equals("PUBLISHED")
                                ?List.of(99L,100L).stream().filter(id->id>cursor.lastId()).limit((int)args[1]).toList():List.of();
                    case "advance":
                        if(((GrabAuditCursorRepository.Cursor)args[0]).taskStatus().equals("PUBLISHED")) {
                            advancedPublished.incrementAndGet();publishedLastId=(boolean)args[2]?0:(long)args[1];
                        }
                        return true;
                    default:throw new AssertionError(method);
                }
            });
            useCase=new GrabRecoveryUseCase(requests,
                    new GrabRequestStep(requests,taskRepository,new SnowflakeIdGenerator(30)),taskRepository,projection,
                    new GrabProjectionSync(errands,projection),cursors,errands,new SnowflakeIdGenerator(31));
        }
        private GrabRequest succeeded() {
            return new GrabRequest(request.id(),request.campusId(),request.errandId(),request.runnerId(),
                    request.requestId(),request.payloadHash(),request.requestRound(),request.baseErrandVersion(),
                    GrabRequestStatus.SUCCEEDED,request.reservationToken(),request.leaseUntil(),"OK",200,"{}",
                    request.createdAt(),request.leaseUntil().minusMillis(1));
        }
        private static Errand errand(long id,ErrandStatus status,long version) {
            return Errand.rehydrate(id,1,11,ErrandType.DELIVERY,"test",Money.ofCents(100),1,
                    status==ErrandStatus.LOCKED?21L:null,status,status==ErrandStatus.LOCKED?1:0,0,version,null);
        }
    }
    @FunctionalInterface private interface Call {Object invoke(String method,Object[] arguments);}
    @SuppressWarnings("unchecked") private static <T>T port(Class<T> type,Call call) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},
                (proxy,method,args)->call.invoke(method.getName(),args));
    }
}
