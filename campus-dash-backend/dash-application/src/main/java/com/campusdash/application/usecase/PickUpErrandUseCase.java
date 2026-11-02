package com.campusdash.application.usecase;
import com.campusdash.domain.errand.model.ErrandStatus;
import org.springframework.stereotype.Service;
@Service
public class PickUpErrandUseCase {
    private final ErrandLifecycleStep step;
    private final ErrandLifecycleEffects effects;
    public PickUpErrandUseCase(ErrandLifecycleStep step,ErrandLifecycleEffects effects) {this.step=step;this.effects=effects;}

    public void pickUp(long errandId,long runnerId) {effects.afterCommit(step.change(errandId,runnerId,ErrandStatus.PICKED_UP));}
}
