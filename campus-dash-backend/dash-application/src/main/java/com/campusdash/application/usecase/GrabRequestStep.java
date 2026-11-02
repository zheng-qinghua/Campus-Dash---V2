package com.campusdash.application.usecase;

import com.campusdash.domain.grab.model.*;
import com.campusdash.domain.grab.ports.*;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 请求登记、终结和回收分别提交；不在事务内访问 Redis。 */
@Service
public class GrabRequestStep {
    private final GrabRequestRepository requests;
    private final GrabRepairTaskRepository repairs;
    private final SnowflakeIdGenerator ids;
    public GrabRequestStep(GrabRequestRepository requests, GrabRepairTaskRepository repairs, SnowflakeIdGenerator ids) {
        this.requests = requests; this.repairs = repairs; this.ids = ids;
    }
    @Transactional(rollbackFor = Exception.class)
    public boolean register(GrabRequest.NewRequest request) { return requests.create(request); }

    @Transactional(rollbackFor = Exception.class)
    public GrabRequest reject(GrabRequest request, ErrorCode code) {
        GrabRequest current = requests.lockById(request.campusId(), request.id()).orElseThrow();
        if (current.status().terminal()) return current;
        if (!current.executable(request.reservationToken(), requests.databaseNow())) {
            return expireLocked(current);
        }
        if (!requests.finish(current.campusId(), current.id(), current.reservationToken(),
                GrabRequestStatus.REJECTED, code.name(), 409, resultJson(current, code, false))) {
            // 租约可能在持锁期间刚好到期，不允许提交没有终态/补偿的结果。
            throw new IllegalStateException("请求终结条件已失效");
        }
        enqueueRevoke(current);
        return requests.findById(current.campusId(), current.id()).orElseThrow();
    }
    @Transactional(rollbackFor = Exception.class)
    public GrabRequest expire(GrabRequest request) {
        GrabRequest current = requests.lockById(request.campusId(), request.id()).orElseThrow();
        if (current.status().terminal() || current.leaseUntil().isAfter(requests.databaseNow())) return current;
        return expireLocked(current);
    }
    private GrabRequest expireLocked(GrabRequest current) {
        if (requests.finish(current.campusId(), current.id(), current.reservationToken(),
                GrabRequestStatus.EXPIRED, ErrorCode.REQUEST_EXPIRED.name(), 409,
                resultJson(current, ErrorCode.REQUEST_EXPIRED, false))) enqueueRevoke(current);
        return requests.findById(current.campusId(), current.id()).orElseThrow();
    }
    private void enqueueRevoke(GrabRequest r) {
        repairs.ensurePending(new GrabRepairTask(ids.nextId(), r.campusId(), r.errandId(),
                "revoke:" + r.id() + ":" + r.reservationToken(), GrabRepairTask.REVOKE_PENDING,
                r.baseErrandVersion(), r.requestRound(), r.id(), r.reservationToken(), "{}", 0, null));
    }
    // 只有固定枚举与已校验 UUID，无业务自由文本，保持持久结果与首次响应一致。
    public static String resultJson(GrabRequest r, ErrorCode code, boolean grabbed) {
        return String.format("{\"code\":\"%s\",\"grabbed\":%s,\"requestId\":\"%s\",\"appliedRound\":%d}",
                code.name(), grabbed, r.requestId(), r.requestRound());
    }
}
