package com.campusdash.domain.grab.model;

/** 租约领取后的持久补偿任务。payload 保存业务快照；缺失重建必须重新读取主库。 */
public record GrabRepairTask(long id, long campusId, long errandId, String eventKey,
        String eventType, long targetVersion, int round, Long requestDbId,
        String reservationToken, String payload, int retryCount, String workerToken) {
    public static final String APPLY_PROJECTION = "APPLY_PROJECTION";
    public static final String REVOKE_PENDING = "REVOKE_PENDING";
    public static final String AUDIT_PROJECTION = "AUDIT_PROJECTION";
}
