package com.campusdash.application.usecase;
import com.campusdash.domain.errand.model.*;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
@Service
public class PublishErrandUseCase {
    private final PublishErrandStep step;
    private final GrabProjectionSync sync;
    private final ErrandCachePort cache;
    public PublishErrandUseCase(PublishErrandStep step, GrabProjectionSync sync, ErrandCachePort cache) {
        this.step=step; this.sync=sync; this.cache=cache;
    }
    public record Command(long campusId, long publisherId, ErrandType type, String title, long rewardCents, int slotTotal) {}
    public record Result(long errandId, ErrandStatus status, long frozenCents) {}
    public Result publish(Command cmd) {
        Result result=step.publish(cmd);
        sync.bestEffort(result.errandId());
        try { cache.registerExisting(result.errandId()); }
        catch (RuntimeException ex) { LoggerFactory.getLogger(getClass()).warn("发布已提交，缓存登记失败 errandId={}", result.errandId(), ex); }
        return result;
    }
}
