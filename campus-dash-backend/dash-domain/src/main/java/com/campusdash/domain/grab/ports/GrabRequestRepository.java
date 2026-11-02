package com.campusdash.domain.grab.ports;

import com.campusdash.domain.grab.model.GrabRequest;
import com.campusdash.domain.grab.model.GrabRequestStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 与业务、补偿表共用同一个本地事务。所有读写定位校区。 */
public interface GrabRequestRepository {
    boolean create(GrabRequest.NewRequest request);
    Optional<GrabRequest> find(long campusId, long runnerId, String requestId);
    Optional<GrabRequest> findById(long campusId, long id);
    /** 调用方必须已经进入事务，并在获取行锁后读取 databaseNow。 */
    Optional<GrabRequest> lockById(long campusId, long id);
    Instant databaseNow();
    /** 带 PROCESSING + token 条件；成功要求未过期，EXPIRED 要求已经到期。 */
    boolean finish(long campusId, long id, String token, GrabRequestStatus status,
                   String resultCode, int httpStatus, String resultJson);
    List<GrabRequest> findExpired(int limit);
}
