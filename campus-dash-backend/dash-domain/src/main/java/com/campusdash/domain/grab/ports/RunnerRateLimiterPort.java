package com.campusdash.domain.grab.ports;

/** 可信跑腿身份的共享准入预算；每次 HTTP 重试仍须计费。 */
public interface RunnerRateLimiterPort {
    Decision checkGrab(long runnerId, long errandId);
    Decision checkQuery(long runnerId);

    enum Status { ALLOWED, LIMITED, UNAVAILABLE }
    record Decision(Status status, long retryAfterMillis) {
        public static Decision allowed() { return new Decision(Status.ALLOWED, 0); }
        public static Decision limited(long millis) { return new Decision(Status.LIMITED, Math.max(1, millis)); }
        public static Decision unavailable() { return new Decision(Status.UNAVAILABLE, 1000); }
    }
}
