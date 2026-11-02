package com.campusdash.application.usecase;
import com.campusdash.domain.errand.model.ErrandStatus;
import org.springframework.stereotype.Service;
@Service
public class DisputeErrandUseCase {
    private final ErrandLifecycleStep step;
    private final ErrandLifecycleEffects effects;
    public DisputeErrandUseCase(ErrandLifecycleStep step,ErrandLifecycleEffects effects) {this.step=step;this.effects=effects;}

    public void raise(long errandId,long operatorId) {effects.afterCommit(step.change(errandId,operatorId,ErrandStatus.DISPUTED));}
}
