package com.campusdash.application.usecase;

import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.*;
import com.campusdash.domain.grab.model.*;
import com.campusdash.domain.grab.ports.*;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** 持久幂等先登记；Redis 只挡流量；最终结果以数据库提交事实为准。 */
@Service
public class GrabErrandUseCase {
    private static final Logger log = LoggerFactory.getLogger(GrabErrandUseCase.class);
    private final ErrandRepository errands;
    private final GrabRequestRepository requests;
    private final GrabRequestStep requestStep;
    private final GrabTransactionalStep commitStep;
    private final GrabProjectionPort projection;
    private final GrabProjectionSync sync;
    private final CandidateQueuePort candidates;
    private final CreditRepository credit;
    private final ErrandQueryPort queries;
    private final GrabRateLimiterPort hotLimiter;
    private final RunnerRateLimiterPort runnerLimiter;
    private final SnowflakeIdGenerator ids;
    private final CacheEvictSupport cache;
    private final RealtimeNotifier notifier;
    private final TimeoutTransferUseCase timeout;
    private final int minCredit, maxOngoing;
    private final java.util.concurrent.Semaphore grabCapacity;
    private final java.util.concurrent.Semaphore queryCapacity = new java.util.concurrent.Semaphore(10);
    /** 保留手工装配入口；与生产默认容量一致。 */
    public GrabErrandUseCase(ErrandRepository errands, GrabRequestRepository requests, GrabRequestStep requestStep,
            GrabTransactionalStep commitStep, GrabProjectionPort projection, GrabProjectionSync sync,
            CandidateQueuePort candidates, CreditRepository credit, ErrandQueryPort queries,
            GrabRateLimiterPort hotLimiter, RunnerRateLimiterPort runnerLimiter, SnowflakeIdGenerator ids,
            CacheEvictSupport cache, RealtimeNotifier notifier, TimeoutTransferUseCase timeout,
            int minCredit, int maxOngoing) {
        this(errands, requests, requestStep, commitStep, projection, sync, candidates, credit, queries,
                hotLimiter, runnerLimiter, ids, cache, notifier, timeout, minCredit, maxOngoing, 10);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public GrabErrandUseCase(ErrandRepository errands, GrabRequestRepository requests, GrabRequestStep requestStep,
            GrabTransactionalStep commitStep, GrabProjectionPort projection, GrabProjectionSync sync,
            CandidateQueuePort candidates, CreditRepository credit, ErrandQueryPort queries,
            GrabRateLimiterPort hotLimiter, RunnerRateLimiterPort runnerLimiter, SnowflakeIdGenerator ids,
            CacheEvictSupport cache, RealtimeNotifier notifier, TimeoutTransferUseCase timeout,
            @Value("${dash.credit.min-score:40}") int minCredit,
            @Value("${dash.credit.max-ongoing:5}") int maxOngoing,
            @Value("${dash.grab.max-in-flight:10}") int maxInFlight) {
        if (maxInFlight < 1) throw new IllegalArgumentException("抢单在途容量必须大于零");
        this.errands=errands; this.requests=requests; this.requestStep=requestStep; this.commitStep=commitStep;
        this.projection=projection; this.sync=sync; this.candidates=candidates; this.credit=credit;
        this.queries=queries; this.hotLimiter=hotLimiter; this.runnerLimiter=runnerLimiter; this.ids=ids;
        this.cache=cache; this.notifier=notifier; this.timeout=timeout; this.minCredit=minCredit; this.maxOngoing=maxOngoing;
        this.grabCapacity = new java.util.concurrent.Semaphore(maxInFlight);
    }
    public record Command(long errandId, long runnerId, String requestId, int expectedRound) {
        public Command(long errandId, long runnerId, String requestId) { this(errandId, runnerId, requestId, 0); }
    }
    public record Result(ErrorCode code, boolean grabbed, Long candidateRank, String requestId,
            String status, Integer appliedRound, boolean replayed, long retryAfterMillis, int httpStatus) {}

    public Result grab(Command cmd) {
        if (!valid(cmd)) return response(ErrorCode.INVALID_REQUEST, cmd.requestId(), 400, 0);
        var limit = runnerLimiter.checkGrab(cmd.runnerId(), cmd.errandId());
        if (limit.status() != RunnerRateLimiterPort.Status.ALLOWED) return limited(cmd.requestId(), limit);
        // 持久登记发生在 Redis 占位前，必须先约束整条 DB 编排的并发量，给赢家和恢复查询留出连接。
        // 不等待、不占用请求执行租约；拒绝时仍可使用原 ID 重试。此容量不替代跨实例跑腿限流。
        if (!grabCapacity.tryAcquire()) return response(ErrorCode.DEPENDENCY_UNAVAILABLE, cmd.requestId(), 503, 1000);
        GrabRequest registered = null;
        boolean commitAttempted = false;
        try {
            Errand errand = errands.findById(cmd.errandId()).orElse(null);
            if (errand == null) return response(ErrorCode.ERRAND_NOT_FOUND, cmd.requestId(), 404, 0);
            String hash = fingerprint(cmd);
            var previous = requests.find(errand.campusId(), cmd.runnerId(), cmd.requestId());
            if (previous.isPresent()) return replayChecked(previous.get(), cmd, hash);
            if (!hotLimiter.tryPass(cmd.errandId(), cmd.runnerId()))
                return response(ErrorCode.GRAB_RATE_LIMITED, cmd.requestId(), 429, 1000);
            boolean owner = requestStep.register(new GrabRequest.NewRequest(ids.nextId(), errand.campusId(),
                    cmd.errandId(), cmd.runnerId(), cmd.requestId(), hash, cmd.expectedRound(), errand.version(),
                    UUID.randomUUID().toString(), 30));
            registered = requests.find(errand.campusId(), cmd.runnerId(), cmd.requestId()).orElseThrow();
            if (!owner) return replayChecked(registered, cmd, hash);
            if (errand.round() != cmd.expectedRound()) return reject(registered, ErrorCode.STALE_VERSION, false);
            if (errand.slotTotal() != 1) return reject(registered, ErrorCode.ERRAND_NOT_GRABBABLE, false);
            // availableActions 的隐藏只是展示规则，接口也必须阻止发单人抢自己的任务。
            if (errand.publisherId() == cmd.runnerId()) return reject(registered, ErrorCode.ERRAND_NOT_GRABBABLE, false);
            if (Long.valueOf(cmd.runnerId()).equals(errand.grabberId())) return reject(registered, ErrorCode.ALREADY_GRABBED, false);
            if (credit.scoreOf(cmd.runnerId()) < minCredit) return reject(registered, ErrorCode.CREDIT_TOO_LOW, false);
            if (queries.countOngoingByRunner(cmd.runnerId()) >= maxOngoing) return reject(registered, ErrorCode.TOO_MANY_ONGOING, false);
            if (errand.status() != ErrandStatus.PUBLISHED)
                return reject(registered, errand.status() == ErrandStatus.LOCKED ? ErrorCode.SLOT_FULL : ErrorCode.ERRAND_NOT_GRABBABLE,
                        errand.status() == ErrandStatus.LOCKED);
            sync.sync(cmd.errandId());
            var acquired = projection.acquire(registered);
            if (acquired == GrabProjectionPort.AcquireResult.ACQUIRED) {
                commitAttempted = true;
                if (!commitStep.lockAndRecord(registered)) return reject(registered, ErrorCode.GRAB_CONFLICT, true);
                // 这里可能收到 COMMIT 成功但响应丢失，catch 只能回查，不能释放。
                GrabRequest committed = requests.findById(registered.campusId(), registered.id()).orElseThrow();
                afterCommit(cmd.errandId());
                return result(committed, false);
            }
            return switch (acquired) {
                case BUSY -> reject(registered, ErrorCode.SLOT_FULL, true);
                case STALE -> reject(registered, ErrorCode.STALE_VERSION, false);
                case EXPIRED, REVOKED -> result(requestStep.expire(registered), false);
                // 同 token 仍处于处理态或需要修复：不猜测业务成败，也不续租。
                default -> result(registered, false);
            };
        } catch (RuntimeException ex) {
            log.warn("抢单响应不确定，回查持久结果 errandId={} requestId={}", cmd.errandId(), cmd.requestId(), ex);
            if (registered != null) {
                try {
                    var authoritative = requests.findById(registered.campusId(), registered.id()).orElseThrow();
                    if (authoritative.status().terminal() || commitAttempted) return result(authoritative, true);
                    return response(ErrorCode.DEPENDENCY_UNAVAILABLE, cmd.requestId(), 503, 1000);
                }
                catch (RuntimeException unavailable) { /* 保留原 ID，由查询/扫描恢复。 */ }
                return response(ErrorCode.DEPENDENCY_UNAVAILABLE, cmd.requestId(), 503, 1000);
            }
            return response(ErrorCode.DEPENDENCY_UNAVAILABLE, cmd.requestId(), 503, 1000);
        } finally { grabCapacity.release(); }
    }
    public Result query(long errandId, long runnerId, String requestId) {
        if (!valid(new Command(errandId, runnerId, requestId))) return response(ErrorCode.INVALID_REQUEST, requestId, 400, 0);
        var limit = runnerLimiter.checkQuery(runnerId);
        if (limit.status() != RunnerRateLimiterPort.Status.ALLOWED) return limited(requestId, limit);
        if (!queryCapacity.tryAcquire()) return response(ErrorCode.DEPENDENCY_UNAVAILABLE, requestId, 503, 1000);
        try {
            var errand = errands.findById(errandId).orElse(null);
            if (errand == null) return response(ErrorCode.ERRAND_NOT_FOUND, requestId, 404, 0);
            var found = requests.find(errand.campusId(), runnerId, requestId);
            if (found.isEmpty() || found.get().errandId() != errandId)
                return response(ErrorCode.REQUEST_NOT_FOUND, requestId, 404, 500);
            var r = found.get();
            if (!r.status().terminal() && !r.leaseUntil().isAfter(requests.databaseNow())) r = requestStep.expire(r);
            return result(r, true);
        } catch (RuntimeException ex) { return response(ErrorCode.DEPENDENCY_UNAVAILABLE, requestId, 503, 1000); }
        finally { queryCapacity.release(); }
    }
    private Result replayChecked(GrabRequest r, Command cmd, String hash) {
        if (r.errandId() != cmd.errandId() || !hash.equals(r.payloadHash()))
            return response(ErrorCode.REQUEST_CONFLICT, cmd.requestId(), 409, 0);
        if (!r.status().terminal() && !r.leaseUntil().isAfter(requests.databaseNow())) r = requestStep.expire(r);
        return result(r, true);
    }
    private Result reject(GrabRequest request, ErrorCode code, boolean candidate) {
        GrabRequest terminal = requestStep.reject(request, code);
        // 确定失败才精确撤销；撤销失败已有同事务持久补偿任务。
        if (terminal.status() == GrabRequestStatus.REJECTED || terminal.status() == GrabRequestStatus.EXPIRED) {
            try { projection.revoke(terminal); } catch (RuntimeException ex) { log.debug("撤销留待补偿", ex); }
            if (candidate && terminal.status() == GrabRequestStatus.REJECTED) {
                try { candidates.offer(request.errandId(), request.runnerId(),
                        System.currentTimeMillis() - Math.min(credit.scoreOf(request.runnerId()),100) * 10.0,
                        request.requestRound()); }
                catch (RuntimeException ex) { log.warn("候选入队失败 errandId={}", request.errandId(), ex); }
            }
        }
        return result(terminal, false);
    }
    private void afterCommit(long errandId) {
        sync.bestEffort(errandId);
        try { timeout.scheduleFirstTimeout(errandId, 0, 0); } catch (RuntimeException ex) { log.warn("超时消息留待重试 errandId={}",errandId,ex); }
        try {
            cache.evictAfterCommit(errandId);
            errands.findById(errandId).ifPresent(e -> notifier.errandStatusChanged(e.id(),e.publisherId(),e.grabberId(),e.status().name(),e.round()));
        } catch (RuntimeException ex) { log.warn("抢单已提交，附属通知失败 errandId={}",errandId,ex); }
    }
    private Result result(GrabRequest r, boolean replayed) {
        boolean pending = r.status() == GrabRequestStatus.PROCESSING;
        return new Result(pending ? ErrorCode.REQUEST_PROCESSING : ErrorCode.valueOf(r.resultCode()),
                r.status() == GrabRequestStatus.SUCCEEDED, null, r.requestId(), r.status().name(),
                r.requestRound(), replayed, pending ? 1000 : 0, pending ? 202 : r.httpStatus());
    }
    private Result limited(String requestId, RunnerRateLimiterPort.Decision d) {
        return response(d.status() == RunnerRateLimiterPort.Status.LIMITED ? ErrorCode.GRAB_RATE_LIMITED : ErrorCode.DEPENDENCY_UNAVAILABLE,
                requestId, d.status() == RunnerRateLimiterPort.Status.LIMITED ? 429 : 503, Math.max(d.retryAfterMillis(), 1000));
    }
    private Result response(ErrorCode code, String id, int http, long retry) {
        return new Result(code,false,null,id,http==202?"PROCESSING":null,null,false,retry,http);
    }
    private boolean valid(Command cmd) {
        if (cmd.errandId() <= 0 || cmd.runnerId() <= 0 || cmd.expectedRound() < 0 || cmd.requestId() == null) return false;
        try { return UUID.fromString(cmd.requestId()).toString().equals(cmd.requestId()); }
        catch (IllegalArgumentException ex) { return false; }
    }
    private String fingerprint(Command cmd) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    ("GRAB:" + cmd.errandId() + ":" + cmd.expectedRound()).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
