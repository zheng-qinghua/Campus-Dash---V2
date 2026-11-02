package com.campusdash.it;

import com.campusdash.application.usecase.*;
import com.campusdash.domain.credit.ports.CreditRankingPort;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.*;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.domain.wallet.ports.FundAuditPort;
import com.campusdash.domain.wallet.ports.FundEventPort;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 DB 资金链路：自动结算遵守窗口，手动确认保持可提前结算。 */
@SpringBootTest(properties = {"dash.mq.enabled=false", "dash.settle.auto-settle-seconds=3600",
        "dash.grab.runner-limit.rate=10000", "dash.grab.runner-limit.burst=10000",
        "dash.grab.runner-limit.errand-rate=10000", "dash.grab.runner-limit.errand-burst=10000"})
class AutoSettleDeadlineIT {
    @Autowired PublishErrandUseCase publish;
    @Autowired GrabErrandUseCase grab;
    @Autowired ConfirmErrandUseCase confirm;
    @Autowired PickUpErrandUseCase pickup;
    @Autowired DeliverErrandUseCase deliver;
    @Autowired DisputeErrandUseCase dispute;
    @Autowired SettleErrandUseCase settle;
    @Autowired AutoSettleErrandUseCase automatic;
    @Autowired ErrandRepository errands;
    @Autowired JdbcTemplate jdbc;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired ApplicationContext context;
    private static BenchRun run;
    @org.junit.jupiter.api.extension.RegisterExtension
    static final BenchTestOutcome outcome = new BenchTestOutcome();

    @BeforeAll static void requireMiddleware() {
        assertTrue(MiddlewareAvailable.check(), "必须运行真实 MySQL/Redis");
        run = BenchRun.start("IT-AUTO-DEADLINE");
    }
    @AfterAll static void finish() {
        if (run != null) {
            run.finish(outcome.status(), outcome.summary("IT-AUTO-DEADLINE"));
            run.close();
        }
    }

    private record Delivered(long id, long publisher, long runner) {}
    private Delivered delivered() {
        long publisher = ids.nextId(), runner = ids.nextId();
        jdbc.update("INSERT INTO wallet_account(id,owner_id,owner_type,available,frozen,version) VALUES(?,?,'USER',10000,0,0)",
                ids.nextId(), publisher);
        jdbc.update("INSERT INTO wallet_account(id,owner_id,owner_type,available,frozen,version) VALUES(?,?,'USER',0,0,0)",
                ids.nextId(), runner);
        long id = publish.publish(new PublishErrandUseCase.Command(1, publisher, ErrandType.DELIVERY,
                "bench_自动结算截止时间", 1000, 1)).errandId();
        run.track(id);
        assertTrue(grab.grab(new GrabErrandUseCase.Command(id, runner, UUID.randomUUID().toString(), 0)).grabbed());
        confirm.confirm(new ConfirmErrandUseCase.Command(id, runner, 0));
        pickup.pickUp(id, runner);
        deliver.deliver(id, runner);
        return new Delivered(id, publisher, runner);
    }
    private long settlementEntries(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger WHERE biz_no=?", Long.class, "settle:" + id);
    }
    private long runnerBalance(long runner) {
        return jdbc.queryForObject("SELECT available FROM wallet_account WHERE owner_id=? AND owner_type='USER'", Long.class, runner);
    }

    @Test void earlyThenDueAndDuplicateMessagesNeverShortenTheWindowOrPayTwice() {
        var task = delivered();
        var e = errands.findById(task.id()).orElseThrow();
        assertFalse(errands.isAutoSettleDue(task.id(), e.version(), 3600));
        assertEquals(AutoSettleErrandUseCase.Outcome.SKIPPED, automatic.settleIfDue(task.id()));
        assertEquals(AutoSettleErrandUseCase.Outcome.SKIPPED, automatic.settleIfDue(task.id()));
        assertEquals(ErrandStatus.DELIVERED, errands.findById(task.id()).orElseThrow().status());
        assertEquals("HELD", jdbc.queryForObject("SELECT status FROM escrow_order WHERE errand_id=?", String.class, task.id()));
        assertEquals(0, settlementEntries(task.id()));
        assertEquals(0, runnerBalance(task.runner()));

        // 只调整本用例订单的到期事实，不依赖机器时钟或长时间等待。
        assertEquals(1, jdbc.update("UPDATE errand SET delivered_at=DATE_SUB(NOW(3),INTERVAL 3601 SECOND) WHERE id=?", task.id()));
        assertTrue(errands.isAutoSettleDue(task.id(), e.version(), 3600));
        assertFalse(errands.isAutoSettleDue(task.id(), e.version() - 1, 3600));
        assertEquals(AutoSettleErrandUseCase.Outcome.SETTLED, automatic.settleIfDue(task.id()));
        assertEquals(AutoSettleErrandUseCase.Outcome.SKIPPED, automatic.settleIfDue(task.id()));
        assertEquals(ErrandStatus.SETTLED, errands.findById(task.id()).orElseThrow().status());
        assertEquals("RELEASED", jdbc.queryForObject("SELECT status FROM escrow_order WHERE errand_id=?", String.class, task.id()));
        assertEquals(3, settlementEntries(task.id()));
        assertEquals(950, runnerBalance(task.runner()));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount ELSE -amount END),0)
                  FROM wallet_ledger WHERE biz_no=?
                """, Long.class, "settle:" + task.id()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM credit_event WHERE biz_no=?",
                Long.class, "settle:" + task.id()));
    }

    @Test void publisherCanStillConfirmSettlementBeforeTheAutomaticDeadline() {
        var task = delivered();
        var e = errands.findById(task.id()).orElseThrow();
        assertFalse(errands.isAutoSettleDue(task.id(), e.version(), 3600));
        assertEquals(SettleErrandUseCase.Result.SETTLED, settle.settle(task.id(), task.publisher()));
        assertEquals(AutoSettleErrandUseCase.Outcome.SKIPPED, automatic.settleIfDue(task.id()));
        assertEquals(SettleErrandUseCase.Result.ALREADY_SETTLED, settle.settle(task.id(), task.publisher()));
        assertEquals(3, settlementEntries(task.id()));
        assertEquals(950, runnerBalance(task.runner()));
    }

    @Test void disputeCommittedAfterDueCheckRollsBackTheStaleSettlementTransaction() throws Exception {
        var task = dueDelivered();
        var before = errands.findById(task.id()).orElseThrow();
        var snapshotReady = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var paused = new AutoSettleErrandUseCase(errands,
                settlementPausedBeforeTransaction(snapshotReady, resume), 3600);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AutoSettleErrandUseCase.Outcome> result = pool.submit(() -> paused.settleIfDue(task.id()));
            assertTrue(snapshotReady.await(10, TimeUnit.SECONDS), "自动结算必须先通过到期检查并读到 DELIVERED");
            // 自动入口和结算编排均已读到旧快照；争议独立提交后才允许资金事务执行。
            dispute.raise(task.id(), task.publisher());
            var disputed = errands.findById(task.id()).orElseThrow();
            assertEquals(ErrandStatus.DISPUTED, disputed.status());
            assertEquals(before.version() + 1, disputed.version());
            resume.countDown();
            assertEquals(AutoSettleErrandUseCase.Outcome.CONFLICT, result.get(10, TimeUnit.SECONDS));
            assertEquals(AutoSettleErrandUseCase.Outcome.SKIPPED, automatic.settleIfDue(task.id()));
            assertEquals(ErrandStatus.DISPUTED, errands.findById(task.id()).orElseThrow().status());
            assertEquals("HELD", jdbc.queryForObject("SELECT status FROM escrow_order WHERE errand_id=?",
                    String.class, task.id()));
            assertEquals(0, settlementEntries(task.id()));
            assertEquals(0, runnerBalance(task.runner()));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM credit_event WHERE biz_no=?",
                    Long.class, "settle:" + task.id()));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM errand_status_log WHERE errand_id=? AND to_status='SETTLED'",
                    Long.class, task.id()));
        } finally {
            resume.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "并发结算线程必须结束");
        }
    }

    @Test void automaticAndManualSettlementWithTheSameDeliveredSnapshotPayExactlyOnce() throws Exception {
        var task = dueDelivered();
        var before = errands.findById(task.id()).orElseThrow();
        var snapshotsReady = new CountDownLatch(2);
        var resume = new CountDownLatch(1);
        var pausedSettlement = settlementPausedBeforeTransaction(snapshotsReady, resume);
        var pausedAutomatic = new AutoSettleErrandUseCase(errands, pausedSettlement, 3600);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AutoSettleErrandUseCase.Outcome> autoResult =
                    pool.submit(() -> pausedAutomatic.settleIfDue(task.id()));
            Future<SettleErrandUseCase.Result> manualResult =
                    pool.submit(() -> pausedSettlement.settle(task.id(), task.publisher()));
            assertTrue(snapshotsReady.await(10, TimeUnit.SECONDS), "双方必须在资金事务前读到同一 DELIVERED 快照");
            assertEquals(before.version(), errands.findById(task.id()).orElseThrow().version());
            resume.countDown();
            var auto = autoResult.get(10, TimeUnit.SECONDS);
            var manual = manualResult.get(10, TimeUnit.SECONDS);
            assertTrue((auto == AutoSettleErrandUseCase.Outcome.SETTLED && manual == SettleErrandUseCase.Result.CONFLICT)
                    || (auto == AutoSettleErrandUseCase.Outcome.CONFLICT && manual == SettleErrandUseCase.Result.SETTLED),
                    "同快照的两个资金事务必须恰好一个成功，另一个整体回滚");
            var settled = errands.findById(task.id()).orElseThrow();
            assertEquals(ErrandStatus.SETTLED, settled.status());
            assertEquals(before.version() + 1, settled.version());
            assertEquals("RELEASED", jdbc.queryForObject("SELECT status FROM escrow_order WHERE errand_id=?",
                    String.class, task.id()));
            assertEquals(3, settlementEntries(task.id()));
            assertEquals(950, runnerBalance(task.runner()));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM credit_event WHERE biz_no=? AND delta=2",
                    Long.class, "settle:" + task.id()));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM errand_status_log WHERE errand_id=? AND to_status='SETTLED'",
                    Long.class, task.id()));
            assertEquals(3L, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM wallet_ledger l JOIN wallet_account a ON a.id=l.account_id
                     WHERE l.biz_no=? AND (
                           (a.owner_type='ESCROW' AND a.owner_id=-1 AND l.direction='DEBIT' AND l.amount=1000)
                        OR (a.owner_type='USER' AND a.owner_id=? AND l.direction='CREDIT' AND l.amount=950)
                        OR (a.owner_type='COMMISSION' AND a.owner_id=-2 AND l.direction='CREDIT' AND l.amount=50))
                    """, Long.class, "settle:" + task.id(), task.runner()));
            assertEquals(AutoSettleErrandUseCase.Outcome.SKIPPED, automatic.settleIfDue(task.id()));
            assertEquals(SettleErrandUseCase.Result.ALREADY_SETTLED, settle.settle(task.id(), task.publisher()));
            assertEquals(3, settlementEntries(task.id()));
            assertEquals(950, runnerBalance(task.runner()));
        } finally {
            resume.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "并发结算线程必须结束");
        }
    }

    private Delivered dueDelivered() {
        var task = delivered();
        assertEquals(1, jdbc.update(
                "UPDATE errand SET delivered_at=DATE_SUB(NOW(3),INTERVAL 3601 SECOND) WHERE id=?", task.id()));
        return task;
    }

    /** 只在事务入口插入有界暂停；仓储、资金事务、账务和提交后的处理全部使用实际 Bean。 */
    private SettleErrandUseCase settlementPausedBeforeTransaction(CountDownLatch ready, CountDownLatch resume) {
        FundEventPort events = context.getBean(FundEventPort.class);
        FundEventPort pausedEvents = (event, localWork) -> {
            ready.countDown();
            try {
                if (!resume.await(15, TimeUnit.SECONDS)) throw new AssertionError("资金事务等待恢复超时");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("资金事务等待被中断", interrupted);
            }
            return events.publishInTransaction(event, localWork);
        };
        return new SettleErrandUseCase(errands, context.getBean(WalletRepository.class),
                context.getBean(FundAuditPort.class), pausedEvents, ids, context.getBean(CacheEvictSupport.class),
                context.getBean(CreditRepository.class), context.getBean(CreditRankingPort.class),
                context.getBean(TransactionTemplate.class), context.getBean(RealtimeNotifier.class),
                context.getEnvironment().getProperty("dash.settle.commission-rate", Double.class, 0.05));
    }
}
