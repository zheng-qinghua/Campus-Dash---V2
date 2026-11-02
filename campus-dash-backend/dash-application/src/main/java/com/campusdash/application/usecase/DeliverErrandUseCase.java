package com.campusdash.application.usecase;
import com.campusdash.domain.errand.model.ErrandStatus;
import org.springframework.stereotype.Service;
@Service
public class DeliverErrandUseCase {
    private final ErrandLifecycleStep step;
    private final ErrandLifecycleEffects effects;
    public DeliverErrandUseCase(ErrandLifecycleStep step,ErrandLifecycleEffects effects) {this.step=step;this.effects=effects;}

    public void deliver(long errandId,long runnerId) {effects.afterCommit(step.change(errandId,runnerId,ErrandStatus.DELIVERED));}
}
