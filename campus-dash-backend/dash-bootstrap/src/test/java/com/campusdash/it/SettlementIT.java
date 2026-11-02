package com.campusdash.it;

import com.campusdash.application.usecase.*;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.domain.recon.ports.ReconRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;


/**
 * P3 结算端到端集成测试。
 *
 * 验证链路：发布→抢单→确认→取货→送达→结算→对账全绿。
 * 以及退款与幂等。
 */
@SpringBootTest(properties = {
        "dash.mq.enabled=false",
        "dash.settle.commission-rate=0.05",
        "dash.settle.auto-settle-seconds=2"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettlementIT {

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired ConfirmErrandUseCase confirmUseCase;
    @Autowired PickUpErrandUseCase pickUpUseCase;
    @Autowired DeliverErrandUseCase deliverUseCase;
    @Autowired SettleErrandUseCase settleUseCase;
    @Autowired RefundErrandUseCase refundUseCase;
    @Autowired ReconRepository reconRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired JdbcTemplate jdbc;
    private static BenchRun run;
    @org.junit.jupiter.api.extension.RegisterExtension
    static final BenchTestOutcome outcome = new BenchTestOutcome();
    private Map<Long, Long> accountBefore;
    private long runnerBefore, commissionBefore, escrowBefore, publisherBefore;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
        run = BenchRun.start("IT-SETTLEMENT");
    }

    @AfterAll
    static void finishRun() {
        if (run != null) { run.finish(outcome.status(), outcome.summary("IT-SETTLEMENT")); run.close(); }
    }

    @BeforeEach
    void captureAccountBaseline() {
        // 按本次任务核对快照增量与流水，保留其他轮次的托管与账本。
        accountBefore = new HashMap<>();
        jdbc.query("SELECT id, available + frozen AS total FROM wallet_account",
                rs -> { accountBefore.put(rs.getLong("id"), rs.getLong("total")); });
        runnerBefore = available(2001L, AccountType.USER);
        publisherBefore = available(1001L, AccountType.USER);
        commissionBefore = available(-2L, AccountType.COMMISSION);
        escrowBefore = available(-1L, AccountType.ESCROW);
    }

    private long available(long owner, AccountType type) {
        return walletRepository.findByOwner(owner, type).orElseThrow().available().cents();
    }

    private void assertTaskReconciled(long errandId) {
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount ELSE -amount END),0)
                FROM wallet_ledger WHERE ref_id=?
                """, Long.class, errandId), "本任务 L1 借贷必须平衡");
        var deltas = jdbc.queryForList("""
                SELECT account_id, SUM(CASE WHEN direction='CREDIT' THEN amount ELSE -amount END) AS net
                FROM wallet_ledger WHERE ref_id=? GROUP BY account_id
                """, errandId);
        assertFalse(deltas.isEmpty(), "任务必须留下真实资金流水");
        for (var delta : deltas) {
            long accountId = ((Number) delta.get("account_id")).longValue();
            long ledgerNet = ((Number) delta.get("net")).longValue();
            long snapshot = walletRepository.findById(accountId).orElseThrow().total().cents();
            assertEquals(ledgerNet, snapshot - accountBefore.get(accountId), "本任务 L2 快照增量必须等于流水净额");
        }
        assertTrue(reconRepository.findEscrowClosureDiffs().stream().noneMatch(diff -> diff.errandId() == errandId),
                "本任务 L3 托管必须闭环");
    }

    @Test
    @Order(1)
    @DisplayName("端到端结算：跑腿+佣金==托管额，对账零差异")
    void full_lifecycle_settle() {
        // 发布（托管 2000 分）
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, "settle_test", 2000L, 1));
        long errandId = pub.errandId();
        run.track(errandId);

        // 抢单+确认
        grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 2001L, UUID.randomUUID().toString(), 0));
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, 2001L));

        // 取货：P4 补上用例后走真实链路，不再用 SQL 绕过
        pickUpUseCase.pickUp(errandId, 2001L);
        // 送达
        deliverUseCase.deliver(errandId, 2001L);

        // 结算
        var result = settleUseCase.settle(errandId, 1001L);
        assertEquals(SettleErrandUseCase.Result.SETTLED, result);

        // 验证余额
        long runnerAvailable = walletRepository.findByOwner(2001L, AccountType.USER).orElseThrow().available().cents();
        long commissionAvailable = walletRepository.findByOwner(-2L, AccountType.COMMISSION).orElseThrow().available().cents();
        long escrowAvailable = walletRepository.findByOwner(-1L, AccountType.ESCROW).orElseThrow().available().cents();

        // 佣金 = 2000 * 0.05 = 100，跑腿 = 2000 - 100 = 1900
        assertEquals(runnerBefore + 1900, runnerAvailable, "跑腿余额");
        assertEquals(commissionBefore + 100, commissionAvailable, "佣金余额");
        assertEquals(escrowBefore, escrowAvailable, "本任务托管应清零，其他任务不受影响");

        // 跑腿所得 + 佣金 == 原托管金额（不变式）
        assertEquals(2000, 1900 + 100);

        // 对账零差异：L1 借贷平衡
        assertTaskReconciled(errandId);
    }

    @Test
    @Order(2)
    @DisplayName("重复结算幂等：不会重复打钱")
    void settle_idempotent() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, "settle_idem", 1000L, 1));
        long errandId = pub.errandId();
        run.track(errandId);
        grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 2001L, UUID.randomUUID().toString(), 0));
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, 2001L));
        pickUpUseCase.pickUp(errandId, 2001L);
        deliverUseCase.deliver(errandId, 2001L);

        assertEquals(SettleErrandUseCase.Result.SETTLED, settleUseCase.settle(errandId, 1001L));
        // 第二次
        assertEquals(SettleErrandUseCase.Result.ALREADY_SETTLED, settleUseCase.settle(errandId, 1001L));

        // 跑腿只加了一次
        long runnerAvailable = walletRepository.findByOwner(2001L, AccountType.USER).orElseThrow().available().cents();
        assertEquals(runnerBefore + 950, runnerAvailable); // 1000 * 0.95 = 950
        assertTaskReconciled(errandId);
    }

    @Test
    @Order(3)
    @DisplayName("退款闭环：发布后取消，余额回到初始值")
    void cancel_and_refund() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.BUY, "refund_test", 3000L, 1));
        long errandId = pub.errandId();
        run.track(errandId);

        var result = refundUseCase.cancelAndRefund(errandId, 1001L);
        assertEquals(RefundErrandUseCase.Result.REFUNDED, result);

        // 发单人余额恢复
        long available = walletRepository.findByOwner(1001L, AccountType.USER).orElseThrow().available().cents();
        assertEquals(publisherBefore, available, "退款后余额应恢复到初始值");

        // 托管账户清零
        long escrow = walletRepository.findByOwner(-1L, AccountType.ESCROW).orElseThrow().available().cents();
        assertEquals(escrowBefore, escrow);

        // 对账零差异
        assertTaskReconciled(errandId);
    }
}
