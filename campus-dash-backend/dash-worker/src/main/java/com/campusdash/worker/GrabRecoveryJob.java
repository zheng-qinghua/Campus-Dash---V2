package com.campusdash.worker;
import com.campusdash.application.usecase.GrabRecoveryUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class GrabRecoveryJob {
    private final GrabRecoveryUseCase recovery;
    public GrabRecoveryJob(GrabRecoveryUseCase recovery) { this.recovery=recovery; }
    @Scheduled(fixedDelayString="${dash.grab.recovery.expire-interval-ms:5000}")
    public void expire() { recovery.expireRequests(200); }
    @Scheduled(fixedDelayString="${dash.grab.recovery.repair-interval-ms:1000}")
    public void repair() { recovery.repairBatch(100); }
    @Scheduled(fixedDelayString="${dash.grab.recovery.audit-interval-ms:30000}")
    public void audit() { recovery.audit(100); }
}
