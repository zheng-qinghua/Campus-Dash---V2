package com.campusdash.domain.grab.model;

import java.time.Instant;

/** 持久请求事实；leaseUntil 由数据库签发，不允许重试续期。 */
public record GrabRequest(long id, long campusId, long errandId, long runnerId,
        String requestId, String payloadHash, int requestRound, long baseErrandVersion,
        GrabRequestStatus status, String reservationToken, Instant leaseUntil,
        String resultCode, int httpStatus, String resultJson, Instant createdAt, Instant completedAt) {
    public static final String OPERATION = "GRAB";
    public boolean executable(String token, Instant databaseNow) {
        return status == GrabRequestStatus.PROCESSING && reservationToken.equals(token)
                && leaseUntil.isAfter(databaseNow);
    }
    public record NewRequest(long id, long campusId, long errandId, long runnerId,
            String requestId, String payloadHash, int requestRound, long baseErrandVersion,
            String reservationToken, int leaseSeconds) {}
}
