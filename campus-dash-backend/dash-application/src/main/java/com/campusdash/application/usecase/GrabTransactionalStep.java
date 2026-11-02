package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.model.*;
import com.campusdash.domain.grab.ports.*;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 请求行锁、最终 CAS、占用事实、超时消息和请求终态在同一事务提交。 */
@Service
public class GrabTransactionalStep {
    private final ErrandRepository errands;
    private final GrabRecordRepository records;
    private final GrabRequestRepository requests;
    private final TimeoutTransferStep timeout;
    private final SnowflakeIdGenerator ids;
    private final com.campusdash.domain.credit.ports.CreditRepository credit;
    private final com.campusdash.domain.errand.ports.ErrandQueryPort queries;
    private final int minCredit, maxOngoing;
    public GrabTransactionalStep(ErrandRepository errands, GrabRecordRepository records,
            GrabRequestRepository requests, TimeoutTransferStep timeout, SnowflakeIdGenerator ids,
            com.campusdash.domain.credit.ports.CreditRepository credit,
            com.campusdash.domain.errand.ports.ErrandQueryPort queries,
            @org.springframework.beans.factory.annotation.Value("${dash.credit.min-score:40}") int minCredit,
            @org.springframework.beans.factory.annotation.Value("${dash.credit.max-ongoing:5}") int maxOngoing) {
        this.errands = errands; this.records = records; this.requests = requests; this.timeout = timeout; this.ids = ids;
        this.credit=credit;this.queries=queries;this.minCredit=minCredit;this.maxOngoing=maxOngoing;
    }
    @Transactional(rollbackFor = Exception.class)
    public boolean lockAndRecord(GrabRequest request) {
        GrabRequest locked = requests.lockById(request.campusId(), request.id()).orElseThrow();
        // 取到锁之后再看数据库时钟，禁止旧线程在租约过期后复活。
        if (!locked.executable(request.reservationToken(), requests.databaseNow())) return false;
        var errand = errands.findById(request.errandId()).orElse(null);
        if (errand == null || errand.publisherId() == request.runnerId()) return false;
        if (credit.scoreOf(request.runnerId()) < minCredit || queries.countOngoingByRunner(request.runnerId()) >= maxOngoing)
            return false;
        if (errands.casLockForRunner(request.errandId(), request.runnerId(), request.baseErrandVersion(), request.requestRound()) != 1)
            return false;
        records.insert(new GrabRecord(ids.nextId(), request.campusId(), request.errandId(), request.runnerId(),
                1, request.requestRound(), GrabRecord.GrabResultType.GRABBED, requests.databaseNow(), request.id()));
        errands.appendStatusLog(request.errandId(), ErrandStatus.PUBLISHED, ErrandStatus.LOCKED,
                request.requestRound(), request.runnerId());
        timeout.enqueueCurrentTimeout(request.errandId());
        if (!requests.finish(request.campusId(), request.id(), request.reservationToken(), GrabRequestStatus.SUCCEEDED,
                ErrorCode.OK.name(), 200, GrabRequestStep.resultJson(request, ErrorCode.OK, true))) {
            throw new IllegalStateException("抢单提交前租约已失效，回滚全部业务写入");
        }
        return true;
    }
}
