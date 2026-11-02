package com.campusdash.domain.grab.model;

import java.time.Instant;

/** 对账读取视图；missing 使用 Optional.empty() 表达。 */
public record GrabProjectionState(String mode, GrabProjection committed, Pending pending) {
    public record Pending(long requestDbId, String reservationToken, long baseVersion,
            int pendingRound, long runnerId, Instant leaseUntil) {}
}
