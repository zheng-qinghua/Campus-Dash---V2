package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.ErrandStatus;
import org.springframework.stereotype.Service;

@Service
public class ConfirmErrandUseCase {
    private final ErrandLifecycleStep step;
    private final ErrandLifecycleEffects effects;
    public ConfirmErrandUseCase(ErrandLifecycleStep step, ErrandLifecycleEffects effects) {
        this.step = step;
        this.effects = effects;
    }

    public record Command(long errandId, long runnerId, Integer expectedRound) {
        /** 兼容内部调用；HTTP 客户端必须显式携带其看见的轮次。 */
        public Command(long errandId, long runnerId) { this(errandId, runnerId, null); }
    }

    public void confirm(Command cmd) {
        effects.afterCommit(step.change(cmd.errandId(), cmd.runnerId(), ErrandStatus.ACCEPTED, cmd.expectedRound()));
    }
}
