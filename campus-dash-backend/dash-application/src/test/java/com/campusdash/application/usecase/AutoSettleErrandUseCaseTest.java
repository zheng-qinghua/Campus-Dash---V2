package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.*;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.shared.Money;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class AutoSettleErrandUseCaseTest {
    @Test void earlyMessageDoesNotEnterTheMoneyTransaction() {
        var fixture = new Fixture();
        assertEquals(AutoSettleErrandUseCase.Outcome.SKIPPED, fixture.useCase.settleIfDue(99));
        assertEquals(1, fixture.dueChecks);
        assertEquals(0, fixture.settlement.calls);
    }

    @Test void dueMessageDelegatesWithSystemIdentity() {
        var fixture = new Fixture();
        fixture.due = true;
        assertEquals(AutoSettleErrandUseCase.Outcome.SETTLED, fixture.useCase.settleIfDue(99));
        assertEquals(1, fixture.settlement.calls);
        assertEquals(99, fixture.settlement.errandId);
        assertEquals(Errand.SYSTEM_OPERATOR, fixture.settlement.operator);
    }

    @Test void missingAndNonDeliveredOrdersAreAcknowledgedWithoutMoneyEffects() {
        var fixture = new Fixture();
        fixture.due = true;
        for (var status : new ErrandStatus[]{ErrandStatus.SETTLED, ErrandStatus.DISPUTED, ErrandStatus.PICKED_UP}) {
            fixture.current = fixture.errand(status);
            assertEquals(AutoSettleErrandUseCase.Outcome.SKIPPED, fixture.useCase.settleIfDue(99));
        }
        fixture.current = null;
        assertEquals(AutoSettleErrandUseCase.Outcome.SKIPPED, fixture.useCase.settleIfDue(99));
        assertEquals(0, fixture.dueChecks);
        assertEquals(0, fixture.settlement.calls);
    }

    @Test void concurrentManualSettlementKeepsExistingIdempotentOutcome() {
        var fixture = new Fixture();
        fixture.due = true;
        fixture.settlement.result = SettleErrandUseCase.Result.ALREADY_SETTLED;
        assertEquals(AutoSettleErrandUseCase.Outcome.SKIPPED, fixture.useCase.settleIfDue(99));
        fixture.settlement.result = SettleErrandUseCase.Result.CONFLICT;
        assertEquals(AutoSettleErrandUseCase.Outcome.CONFLICT, fixture.useCase.settleIfDue(99));
    }

    private static final class Fixture {
        Errand current = errand(ErrandStatus.DELIVERED);
        boolean due;
        int dueChecks;
        final RecordingSettlement settlement = new RecordingSettlement();
        final AutoSettleErrandUseCase useCase;
        Fixture() {
            ErrandRepository errands = (ErrandRepository) Proxy.newProxyInstance(
                    ErrandRepository.class.getClassLoader(), new Class<?>[]{ErrandRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findById" -> Optional.ofNullable(current);
                        case "isAutoSettleDue" -> {
                            assertEquals(99L, args[0]);
                            assertEquals(21L, args[1], "到期判断必须绑定本次读取的版本");
                            assertEquals(3600L, args[2]);
                            dueChecks++;
                            yield due;
                        }
                        default -> throw new AssertionError(method.getName());
                    });
            useCase = new AutoSettleErrandUseCase(errands, settlement, 3600);
        }
        Errand errand(ErrandStatus status) {
            return Errand.rehydrate(99, 1, 11, ErrandType.DELIVERY, "自动结算窗口",
                    Money.ofCents(100), 1, 22L, status, 1, 0, 21, null);
        }
    }

    /** 只记录是否越过自动入口；真实金额/事务断言在 AutoSettleDeadlineIT。 */
    private static final class RecordingSettlement extends SettleErrandUseCase {
        int calls;
        long errandId, operator;
        Result result = Result.SETTLED;
        RecordingSettlement() { super(null, null, null, null, null, null, null, null, null, null, 0.05); }
        @Override public Result settle(long id, long actor) {
            calls++;
            errandId = id;
            operator = actor;
            return result;
        }
    }
}
