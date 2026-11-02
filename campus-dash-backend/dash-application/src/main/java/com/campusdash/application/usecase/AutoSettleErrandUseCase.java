package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 自动入口统一复核数据库截止时间；手动确认结算仍可在窗口内进行。 */
@Service
public class AutoSettleErrandUseCase {
    public enum Outcome { SETTLED, SKIPPED, CONFLICT }

    private final ErrandRepository errands;
    private final SettleErrandUseCase settlement;
    private final long autoSettleSeconds;

    public AutoSettleErrandUseCase(ErrandRepository errands, SettleErrandUseCase settlement,
            @Value("${dash.settle.auto-settle-seconds:86400}") long autoSettleSeconds) {
        if (autoSettleSeconds < 0) throw new IllegalArgumentException("自动结算窗口不能为负数");
        this.errands = errands;
        this.settlement = settlement;
        this.autoSettleSeconds = autoSettleSeconds;
    }

    public Outcome settleIfDue(long errandId) {
        var errand = errands.findById(errandId).orElse(null);
        if (errand == null || errand.status() != ErrandStatus.DELIVERED
                || !errands.isAutoSettleDue(errandId, errand.version(), autoSettleSeconds)) {
            // 提前消息可 ACK，数据库扫描到期后仍会再次调度；旧终态消息不触碰资金。
            return Outcome.SKIPPED;
        }
        // delivered_at 在送达后不再变化；随后状态竞争继续由结算事务的 CAS 和流水唯一键裁决。
        return switch (settlement.settle(errandId, Errand.SYSTEM_OPERATOR)) {
            case SETTLED -> Outcome.SETTLED;
            case ALREADY_SETTLED -> Outcome.SKIPPED;
            case CONFLICT -> Outcome.CONFLICT;
        };
    }
}
