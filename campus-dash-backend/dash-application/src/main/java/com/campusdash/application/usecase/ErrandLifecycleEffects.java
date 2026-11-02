package com.campusdash.application.usecase;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
@Service
public class ErrandLifecycleEffects {
    private final GrabProjectionSync sync;
    private final CacheEvictSupport cache;
    private final RealtimeNotifier notifier;
    public ErrandLifecycleEffects(GrabProjectionSync sync, CacheEvictSupport cache, RealtimeNotifier notifier) {
        this.sync=sync;this.cache=cache;this.notifier=notifier;
    }
    public void afterCommit(Errand e) {
        sync.bestEffort(e.id());cache.evictAfterCommit(e.id());
        try {notifier.errandStatusChanged(e.id(),e.publisherId(),e.grabberId(),e.status().name(),e.round());}
        catch(RuntimeException ex){LoggerFactory.getLogger(getClass()).warn("状态已提交，通知失败 errandId={}",e.id(),ex);}
    }
}
