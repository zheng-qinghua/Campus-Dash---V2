package com.campusdash.it;

import com.campusdash.application.usecase.*;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.*;
import com.campusdash.domain.errand.ports.*;
import com.campusdash.domain.grab.ports.CandidateQueuePort;
import com.campusdash.shared.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际数据库事务与 Redis 候选队列；屏障固定重复消费者的交错顺序。 */
@SpringBootTest(properties = {"dash.mq.enabled=false", "dash.credit.max-ongoing=10000"})
class TimeoutRaceReliabilityIT {
    @Autowired ApplicationContext context;
    @Autowired ErrandRepository errands;
    @Autowired GrabErrandUseCase grab;
    @Autowired GrabProjectionSync sync;
    @Autowired CandidateQueuePort candidates;
    @Autowired TimeoutTransferUseCase timeout;
    @Autowired TimeoutTransferStep transfer;
    @Autowired ConfirmErrandUseCase confirm;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired JdbcTemplate jdbc;
    private static BenchRun run;

    @BeforeAll static void before() {
        assertTrue(MiddlewareAvailable.check());
        run = BenchRun.start("TIMEOUT-RACE-REVIEW");
    }
    @AfterAll static void after() {
        if (run != null) { run.finish("COMPLETED", "{\"resultSource\":\"surefire XML\"}"); run.close(); }
    }
    private long locked(long owner) {
        long id = ids.nextId();
        errands.insert(Errand.draft(id, 1, 1001, ErrandType.DELIVERY, "bench_超时竞态", Money.ofCents(100), 1));
        assertEquals(1, errands.casPublish(id, 0));
        run.track(id);
        sync.sync(id);
        assertTrue(grab.grab(new GrabErrandUseCase.Command(id, owner, UUID.randomUUID().toString(), 0)).grabbed());
        return id;
    }
    private void due(long id) {
        jdbc.update("UPDATE errand SET confirm_deadline=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?", id);
    }
    private TimeoutTransferUseCase instance(TimeoutTransferStep boundary) {
        return instance(boundary, candidates);
    }
    private TimeoutTransferUseCase instance(TimeoutTransferStep boundary, CandidateQueuePort queue) {
        return new TimeoutTransferUseCase(errands, queue, sync, context.getBean(DelayMessagePort.class),
                context.getBean(LocalMessageRepository.class), boundary, context.getBean(CacheEvictSupport.class),
                context.getBean(CreditRepository.class), ids, 300, 5, context.getBean(ErrandQueryPort.class), 40, 10000);
    }

    @Test void duplicateConsumerCannotRepoolWhileAnotherConsumerSelectedTheOnlyCandidate() throws Exception {
        long id = locked(78101);
        candidates.offer(id, 78102, 1, 0);
        due(id);
        var selected = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var paused = mock(TimeoutTransferStep.class);
        when(paused.transfer(any(), anyLong(), anyLong())).thenAnswer(call -> {
            selected.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return transfer.transfer(call.getArgument(0), call.getArgument(1), call.getArgument(2));
        });
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> instance(paused).handleTimeout(id, 0));
            try {
                assertTrue(selected.await(10, TimeUnit.SECONDS));
                assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED, timeout.handleTimeout(id, 0),
                        "另一消费者必须仍看见候选，不得因第一消费者尚未提交就回池");
            } finally { release.countDown(); }
            assertEquals(TimeoutTransferUseCase.Outcome.SKIPPED, first.get(10, TimeUnit.SECONDS));
        }
        var saved = errands.findById(id).orElseThrow();
        assertEquals(78102L, saved.grabberId());
        assertEquals(1, saved.round());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM grab_record WHERE errand_id=? AND round=1", Integer.class, id));
        assertEquals(0, candidates.size(id));
    }

    @Test void failedDatabaseTransferLeavesSelectedCandidateAvailableForRetry() {
        long id = locked(78111);
        candidates.offer(id, 78112, 1, 0);
        var original = candidates.peekBest(id).orElseThrow();
        due(id);
        var failed = mock(TimeoutTransferStep.class);
        when(failed.transfer(any(), anyLong(), anyLong())).thenThrow(new IllegalStateException("提交前故障"));
        assertThrows(IllegalStateException.class, () -> instance(failed).handleTimeout(id, 0));
        assertEquals(original, candidates.peekBest(id).orElseThrow(), "失败不得消费资格或重置原始排队顺序");
        assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED, timeout.handleTimeout(id, 0));
        assertEquals(78112L, errands.findById(id).orElseThrow().grabberId());
    }

    @Test void delayedOldCycleCandidateCannotTakeTheNewlyReopenedOrder() {
        long id = locked(78121);
        due(id);
        assertEquals(TimeoutTransferUseCase.Outcome.REVERTED, timeout.handleTimeout(id, 0));
        assertEquals(1, errands.latestPublishedRound(1, id));
        assertTrue(grab.grab(new GrabErrandUseCase.Command(id, 78122, UUID.randomUUID().toString(), 1)).grabbed());
        // 第零轮失败请求直到回池后才写入候选，新周期不得接受其旧意图。
        candidates.offer(id, 78123, 1, 0);
        candidates.offer(id, 78124, 2, 1);
        due(id);
        assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED, timeout.handleTimeout(id, 1));
        assertEquals(78124L, errands.findById(id).orElseThrow().grabberId());
        assertEquals(0, candidates.size(id));
    }

    @Test void confirmationFromEarlierOwnershipCannotConfirmTheSameRunnerInANewRound() {
        long id = locked(78131);
        candidates.offer(id, 78132, 1, 0);
        due(id);
        assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED, timeout.handleTimeout(id, 0));
        due(id);
        assertEquals(TimeoutTransferUseCase.Outcome.REVERTED, timeout.handleTimeout(id, 1));
        assertTrue(grab.grab(new GrabErrandUseCase.Command(id, 78131, UUID.randomUUID().toString(), 2)).grabbed());
        BizException old = assertThrows(BizException.class,
                () -> confirm.confirm(new ConfirmErrandUseCase.Command(id, 78131, 0)));
        assertEquals(ErrorCode.STALE_VERSION, old.code());
        assertEquals(ErrandStatus.LOCKED, errands.findById(id).orElseThrow().status());
        confirm.confirm(new ConfirmErrandUseCase.Command(id, 78131, 2));
        assertThrows(BizException.class, () -> confirm.confirm(new ConfirmErrandUseCase.Command(id, 78131, 0)),
                "已确认的幂等返回也必须校验轮次");
        assertEquals(ErrandStatus.ACCEPTED, errands.findById(id).orElseThrow().status());
    }

    @Test void historicalPublisherCandidateIsRejectedBeforeTransfer() {
        long id = locked(78141);
        candidates.offer(id, 1001, 1, 0);
        candidates.offer(id, 78142, 2, 0);
        due(id);
        assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED, timeout.handleTimeout(id, 0));
        assertEquals(78142L, errands.findById(id).orElseThrow().grabberId());
    }
    @Test void repoolingResetsTransferBudgetWithoutResettingTheGlobalRound() {
        long id = locked(78200);
        for (int i = 1; i <= 6; i++) candidates.offer(id, 78200 + i, i, 0);
        for (int round = 0; round < 5; round++) {
            due(id);
            assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED, timeout.handleTimeout(id, round));
        }
        due(id);
        assertEquals(TimeoutTransferUseCase.Outcome.REVERTED, timeout.handleTimeout(id, 5));
        assertEquals(6, errands.latestPublishedRound(1, id));
        assertTrue(grab.grab(new GrabErrandUseCase.Command(id, 78210, UUID.randomUUID().toString(), 6)).grabbed());
        candidates.offer(id, 78211, 10, 6);
        due(id);
        assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED, timeout.handleTimeout(id, 6));
        assertEquals(78211L, errands.findById(id).orElseThrow().grabberId());
        assertEquals(7, errands.findById(id).orElseThrow().round());
    }

    @Test void delayedCleanupAfterTwoTransfersCannotDeleteTheRunnersNewCandidateIntent() throws Exception {
        long id = locked(78300);
        candidates.offer(id, 78301, 1, 0);
        candidates.offer(id, 78302, 2, 0);
        due(id);
        var original = candidates.peekBest(id).orElseThrow();
        var committedBeforeCleanup = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var delayedQueue = mock(CandidateQueuePort.class);
        when(delayedQueue.peekBest(id)).thenAnswer(call -> candidates.peekBest(id));
        when(delayedQueue.remove(eq(id), any())).thenAnswer(call -> {
            CandidateQueuePort.Candidate selected = call.getArgument(1);
            if (selected.equals(original)) {
                committedBeforeCleanup.countDown();
                assertTrue(releaseCleanup.await(10, TimeUnit.SECONDS));
            }
            return candidates.remove(id, selected);
        });
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> instance(transfer, delayedQueue).handleTimeout(id, 0));
            try {
                assertTrue(committedBeforeCleanup.await(10, TimeUnit.SECONDS));
                assertEquals(78301L, errands.findById(id).orElseThrow().grabberId(), "事务已提交、旧队列清理尚未执行");
                due(id);
                assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED, timeout.handleTimeout(id, 1));
                // 第一位候选已超时离开，显式申请当前轮的新候选资格。
                candidates.offer(id, 78301, 3, 2);
                var fresh = candidates.peekBest(id).orElseThrow();
                assertNotEquals(original.token(), fresh.token());
                releaseCleanup.countDown();
                assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED, first.get(10, TimeUnit.SECONDS));
                assertEquals(fresh, candidates.peekBest(id).orElseThrow(), "迟到清理不能误删新轮资格");
                assertEquals(78302L, errands.findById(id).orElseThrow().grabberId());
                assertEquals(2, errands.findById(id).orElseThrow().round());
                var committed = context.getBean(com.campusdash.domain.grab.ports.GrabProjectionPort.class)
                        .read(1, id).orElseThrow().committed();
                assertEquals(78302L, committed.ownerRunnerId(), "迟到提交后回调只能投影当前数据库归属");
                assertEquals(2, committed.round());
            } finally { releaseCleanup.countDown(); }
        }
    }

    @Test void invalidCandidateCleanupBudgetDefersWithoutRepoolingAndEventuallyTransfers() {
        long id = locked(78311);
        due(id);
        assertEquals(TimeoutTransferUseCase.Outcome.REVERTED, timeout.handleTimeout(id, 0));
        assertTrue(grab.grab(new GrabErrandUseCase.Command(id, 78312, UUID.randomUUID().toString(), 1)).grabbed());
        for (int i = 0; i < 34; i++) candidates.offer(id, 78320 + i, i, 0);
        candidates.offer(id, 78360, 100, 1);
        due(id);
        assertEquals(TimeoutTransferUseCase.Outcome.SKIPPED, timeout.handleTimeout(id, 1));
        assertEquals(78312L, errands.findById(id).orElseThrow().grabberId(), "清理预算耗尽不等于候选为空");
        assertEquals(1, errands.findById(id).orElseThrow().round());
        assertEquals(3, candidates.size(id));
        assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED, timeout.handleTimeout(id, 1));
        assertEquals(78360L, errands.findById(id).orElseThrow().grabberId());
        assertEquals(0, candidates.size(id));
    }

}
