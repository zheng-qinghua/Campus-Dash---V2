package com.campusdash.application.usecase;

import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.model.*;
import com.campusdash.domain.grab.ports.*;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;

/** 以数据库为入口的恢复：不依赖 Redis 扫 key，也不持 DB 事务执行 Redis IO。 */
@Service
public class GrabRecoveryUseCase {
    private static final Logger log=LoggerFactory.getLogger(GrabRecoveryUseCase.class);
    private final GrabRequestRepository requests;
    private final GrabRequestStep requestStep;
    private final GrabRepairTaskRepository repairs;
    private final GrabProjectionPort projection;
    private final GrabProjectionSync sync;
    private final GrabAuditCursorRepository cursors;
    private final ErrandRepository errands;
    private final SnowflakeIdGenerator ids;
    public GrabRecoveryUseCase(GrabRequestRepository requests, GrabRequestStep requestStep, GrabRepairTaskRepository repairs,
            GrabProjectionPort projection, GrabProjectionSync sync, GrabAuditCursorRepository cursors,
            ErrandRepository errands, SnowflakeIdGenerator ids) {
        this.requests=requests;this.requestStep=requestStep;this.repairs=repairs;this.projection=projection;
        this.sync=sync;this.cursors=cursors;this.errands=errands;this.ids=ids;
    }
    public void expireRequests(int limit) {
        for(var r:requests.findExpired(limit)) {
            try { requestStep.expire(r); }
            catch(RuntimeException ex) { log.warn("请求过期回收失败 requestId={}",r.id(),ex); }
        }
    }
    public void repairBatch(int limit) {
        for(var task:repairs.claim(limit,30)) {
            try {
                switch(task.eventType()) {
                    case GrabRepairTask.APPLY_PROJECTION -> sync.sync(task.errandId());
                    case GrabRepairTask.AUDIT_PROJECTION -> auditOne(task.campusId(),task.errandId());
                    case GrabRepairTask.REVOKE_PENDING -> {
                        var request=requests.findById(task.campusId(),task.requestDbId()).orElseThrow();
                        if(!request.reservationToken().equals(task.reservationToken()) || !request.status().terminal())
                            throw new IllegalStateException("撤销任务与请求事实不匹配");
                        if(request.errandId()!=task.errandId() || request.requestRound()!=task.round()
                                || request.baseErrandVersion()!=task.targetVersion())
                            throw new IllegalStateException("撤销任务与请求路由或版本不匹配");
                        if(request.status()==GrabRequestStatus.SUCCEEDED) sync.sync(task.errandId());
                        // SUCCEEDED 也可能残留同版本 pending。只撤销精确 token，不触碰 committed。
                        projection.revoke(request);
                    }
                    default -> throw new IllegalArgumentException("未知补偿类型 " + task.eventType());
                }
                repairs.complete(task.id(),task.workerToken());
            } catch(RuntimeException ex) {
                repairs.retry(task.id(),task.workerToken(),ex.getClass().getSimpleName()+": "+ex.getMessage(),10);
                log.error("占位修复失败 taskId={} attempt={}，达到10次转DEAD需人工重放",task.id(),task.retryCount()+1,ex);
            }
        }
    }
    /** 检查完成或已持久登记独立重试后推进；毒数据不得饿死后续页面。 */
    public void audit(int pageSize) {
        for(long campus:cursors.campuses()) for(String status:List.of("PUBLISHED","LOCKED","ACCEPTED","PICKED_UP","DELIVERED","DISPUTED")) {
            var cursor=cursors.loadOrStart(campus,status);
            var batch=cursors.nextIds(cursor,pageSize);
            boolean failed=false;
            for(long id:batch) {
                try { auditOne(campus,id); }
                catch(RuntimeException ex) {
                    try {
                        // 复查独立重试，达到上限转 DEAD；周期巡检不重开 DEAD，也不猜测清理未知占位。
                        ensureAudit(campus,id);
                        log.error("占位异常已持久隔离复查 campus={} status={} errandId={}",campus,status,id,ex);
                    } catch(RuntimeException persistFailure) {
                        failed=true;
                        persistFailure.addSuppressed(ex);
                        // 只有持久化复查任务也失败时才保留水位，避免跳过尚未可靠接管的异常。
                        log.error("占位复查任务未能落库，巡检页保留游标 campus={} status={} errandId={}",
                                campus,status,id,persistFailure);
                    }
                }
            }
            if(!failed) {
                long last=batch.isEmpty()?cursor.lastId():batch.get(batch.size()-1);
                cursors.advance(cursor,last,batch.size()<pageSize || last==cursor.upperId());
            }
        }
    }
    public void auditOne(long campus,long id) {
        var e=errands.findById(id).orElse(null);
        if(e==null || e.campusId()!=campus) return;
        var state=projection.read(campus,id);
        if(state.isEmpty() || !"READY".equals(state.get().mode()) || !GrabProjection.from(e).equals(state.get().committed()))
            ensureProjection(e);
        if(state.isPresent() && state.get().pending()!=null) {
            var pending=state.get().pending();
            var r=requests.findById(campus,pending.requestDbId()).orElseThrow();
            if(r.id()!=pending.requestDbId() || r.campusId()!=campus || r.errandId()!=id
                    || r.runnerId()!=pending.runnerId() || r.baseErrandVersion()!=pending.baseVersion()
                    || r.requestRound()!=pending.pendingRound()
                    || !r.reservationToken().equals(pending.reservationToken())
                    || r.leaseUntil().toEpochMilli()!=pending.leaseUntil().toEpochMilli())
                throw new IllegalStateException("占位身份、版本或截止时间与持久请求不一致，禁止猜测清理");
            if(r.status()==GrabRequestStatus.SUCCEEDED) {
                ensureProjection(e);
                ensureRevoke(r);
            } else if(r.status().terminal()) ensureRevoke(r);
            else if(!r.leaseUntil().isAfter(requests.databaseNow())) requestStep.expire(r);
            else {
                // 主库再次复核，防止第一次任务快照早于请求登记。旧版本请求经行锁裁决后才撤销。
                var latest=errands.findById(id).orElseThrow();
                if(latest.version()>r.baseErrandVersion() || latest.round()!=r.requestRound())
                    requestStep.reject(r,com.campusdash.shared.ErrorCode.STALE_VERSION);
            }
            // PROCESSING 且版本/轮次/租约有效时完整保留，绝不根据“暂无 grab_record”推定失败。
        }
    }
    private void ensureRevoke(GrabRequest r) {
        repairs.ensurePending(new GrabRepairTask(ids.nextId(),r.campusId(),r.errandId(),
                "revoke:"+r.id()+":"+r.reservationToken(),GrabRepairTask.REVOKE_PENDING,
                r.baseErrandVersion(),r.requestRound(),r.id(),r.reservationToken(),"{}",0,null));
    }
    private void ensureAudit(long campus,long errandId) {
        repairs.ensurePending(new GrabRepairTask(ids.nextId(),campus,errandId,
                "audit:"+campus+":"+errandId,GrabRepairTask.AUDIT_PROJECTION,
                0,0,null,null,"{}",0,null));
    }
    private void ensureProjection(com.campusdash.domain.errand.model.Errand e) {
        repairs.ensurePending(new GrabRepairTask(ids.nextId(),e.campusId(),e.id(),"projection:"+e.id()+":"+e.version(),
                GrabRepairTask.APPLY_PROJECTION,e.version(),e.round(),null,null,"{}",0,null));
    }
}
