package com.campusdash.application.usecase;

import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.model.GrabProjection;
import com.campusdash.domain.grab.ports.GrabProjectionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Redis 状态缺失必须先占 INIT，再读主库；不能用旧消息快照初始化。 */
@Service
public class GrabProjectionSync {
    private static final Logger log = LoggerFactory.getLogger(GrabProjectionSync.class);
    private final ErrandRepository errands;
    private final GrabProjectionPort projection;
    public GrabProjectionSync(ErrandRepository errands, GrabProjectionPort projection) {
        this.errands = errands; this.projection = projection;
    }
    public void sync(long errandId) {
        var current = errands.findById(errandId).orElseThrow();
        var result = projection.apply(GrabProjection.from(current));
        if (result == GrabProjectionPort.ApplyResult.REBUILD_REQUIRED) {
            String token = UUID.randomUUID().toString();
            if (!projection.beginRebuild(errandId, token, 5000)) throw new IllegalStateException("占位投影正在重建");
            var fresh = errands.findById(errandId).orElseThrow();
            if (!projection.finishRebuild(GrabProjection.from(fresh), token)) throw new IllegalStateException("投影重建租约失效");
        } else if (result == GrabProjectionPort.ApplyResult.CONFLICT) {
            var verified = errands.findById(errandId).orElseThrow();
            var repaired = projection.repair(GrabProjection.from(verified));
            if (repaired == GrabProjectionPort.ApplyResult.REBUILD_REQUIRED || repaired == GrabProjectionPort.ApplyResult.CONFLICT)
                throw new IllegalStateException("主库复核后投影仍不一致");
        }
    }
    public void bestEffort(long errandId) {
        try { sync(errandId); }
        catch (RuntimeException ex) { log.warn("投影同步留待持久任务重试 errandId={}", errandId, ex); }
    }
}
