package com.campusdash.domain.grab.ports;

import com.campusdash.domain.grab.model.GrabRequest;
import com.campusdash.domain.grab.model.GrabProjection;
import com.campusdash.domain.grab.model.GrabProjectionState;
import java.util.Optional;

public interface GrabProjectionPort {
    enum AcquireResult { ACQUIRED, PROCESSING, BUSY, STALE, REPAIR_REQUIRED, EXPIRED, REVOKED }
    enum ApplyResult { APPLIED, UNCHANGED, STALE, REBUILD_REQUIRED, CONFLICT }
    AcquireResult acquire(GrabRequest request);
    ApplyResult apply(GrabProjection projection);
    /** 主库复核后的专用操作；相同版本仅校准 committed，保留 pending。 */
    ApplyResult repair(GrabProjection verifiedProjection);
    void revoke(GrabRequest request);
    /** 成功建立或接管超时 INIT 才返回 true；不会覆盖 READY。 */
    boolean beginRebuild(long errandId, String buildToken, long leaseMillis);
    /** 只有当前有效 buildToken 可以发布 READY。 */
    boolean finishRebuild(GrabProjection projection, String buildToken);
    Optional<GrabProjectionState> read(long campusId, long errandId);
}
