package com.campusdash.presentation;

import com.campusdash.application.usecase.*;
import com.campusdash.application.usecase.query.ErrandActionResolver;
import com.campusdash.application.usecase.query.GetErrandDetailUseCase;
import com.campusdash.application.usecase.query.QueryErrandListUseCase;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务接口：发布 / 列表 / 详情 / 全部生命周期操作。
 *
 * 身份不再从 X-User-Id 裸取，而是从 AuthInterceptor 解析出的 CurrentUser 拿。
 * 前端渲染的操作按钮由详情接口的 availableActions 驱动（见 ErrandActionResolver），
 * 前端不实现第二份状态机。
 */
@RestController
@RequestMapping("/api/errands")
public class ErrandController {

    private final PublishErrandUseCase publishUseCase;
    private final GrabErrandUseCase grabUseCase;
    private final ConfirmErrandUseCase confirmUseCase;
    private final PickUpErrandUseCase pickUpUseCase;
    private final DeliverErrandUseCase deliverUseCase;
    private final SettleErrandUseCase settleUseCase;
    private final RefundErrandUseCase refundUseCase;
    private final DisputeErrandUseCase disputeUseCase;
    private final ArbitrateErrandUseCase arbitrateUseCase;
    private final QueryErrandListUseCase queryUseCase;
    private final GetErrandDetailUseCase detailUseCase;
    private final ErrandActionResolver actionResolver;
    private final ErrandRepository errandRepository;

    public ErrandController(PublishErrandUseCase publishUseCase,
                            GrabErrandUseCase grabUseCase,
                            ConfirmErrandUseCase confirmUseCase,
                            PickUpErrandUseCase pickUpUseCase,
                            DeliverErrandUseCase deliverUseCase,
                            SettleErrandUseCase settleUseCase,
                            RefundErrandUseCase refundUseCase,
                            DisputeErrandUseCase disputeUseCase,
                            ArbitrateErrandUseCase arbitrateUseCase,
                            QueryErrandListUseCase queryUseCase,
                            GetErrandDetailUseCase detailUseCase,
                            ErrandActionResolver actionResolver,
                            ErrandRepository errandRepository) {
        this.publishUseCase = publishUseCase;
        this.grabUseCase = grabUseCase;
        this.confirmUseCase = confirmUseCase;
        this.pickUpUseCase = pickUpUseCase;
        this.deliverUseCase = deliverUseCase;
        this.settleUseCase = settleUseCase;
        this.refundUseCase = refundUseCase;
        this.disputeUseCase = disputeUseCase;
        this.arbitrateUseCase = arbitrateUseCase;
        this.queryUseCase = queryUseCase;
        this.detailUseCase = detailUseCase;
        this.actionResolver = actionResolver;
        this.errandRepository = errandRepository;
    }

    public record PublishRequest(Long campusId, ErrandType type, String title,
                                 Long rewardCents, Integer slotTotal) {}

    @PostMapping
    public Result<PublishErrandUseCase.Result> publish(@RequestBody PublishRequest req) {
        long userId = CurrentUser.get();
        var cmd = new PublishErrandUseCase.Command(
                req.campusId() == null ? 1L : req.campusId(),
                userId,
                req.type() == null ? ErrandType.DELIVERY : req.type(),
                req.title(),
                req.rewardCents(),
                req.slotTotal() == null ? 1 : req.slotTotal());
        return Result.ok(publishUseCase.publish(cmd));
    }

    /** 任务广场：默认只看 PUBLISHED */
    @GetMapping
    public Result<Object> list(@RequestParam(defaultValue = "1") long campusId,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(required = false) String cursor) {
        long viewer = CurrentUser.get();
        if (cursor != null) {
            var cursorPage = queryUseCase.listByCursor(campusId, status, cursor, size);
            return Result.ok(Map.of(
                    "items", cursorPage.items().stream().map(e -> toCard(e, viewer)).toList(),
                    "nextCursor", cursorPage.nextCursor() == null ? "" : cursorPage.nextCursor()));
        }
        return Result.ok(queryUseCase.list(campusId, status, page, size).stream()
                .map(e -> toCard(e, viewer))
                .toList());
    }

    /** 我的任务：我发布的 / 我抢的 */
    @GetMapping("/mine")
    public Result<List<Map<String, Object>>> mine(@RequestParam(defaultValue = "PUBLISHED_BY_ME") String role,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        long userId = CurrentUser.get();
        List<Errand> list = "GRABBED_BY_ME".equals(role)
                ? queryUseCase.myGrabbed(userId, page, size)
                : queryUseCase.myPublished(userId, page, size);
        return Result.ok(list.stream().map(e -> toCard(e, userId)).toList());
    }

    /**
     * 详情：带 availableActions，前端据此渲染按钮。
     *
     * 保留 Cache Aside 读取与观测接线；返回结果统一使用实时聚合，
     * 让存在性、状态和 availableActions 来自同一次 DB 查询。
     * 空缓存可能由发布提交前的慢读写入，不能据此否决已经提交的订单。
     * 因此 API 命中空缓存时仍查 DB；缓存用例的回源统计不代表接口的全部 DB 查询。
     */
    @GetMapping("/{errandId}")
    public Result<Map<String, Object>> detail(@PathVariable long errandId) {
        long viewer = CurrentUser.get();
        detailUseCase.detailJson(errandId);
        // 缓存没有存在性否决权；动作集合按查看者与同一个实时聚合计算。
        return errandRepository.findById(errandId)
                .map(e -> Result.ok(toCard(e, viewer)))
                .orElseGet(() -> Result.fail(ErrorCode.ERRAND_NOT_FOUND));
    }

    /** 状态时间线（事件溯源的展示面） */
    @GetMapping("/{errandId}/timeline")
    public Result<List<Map<String, Object>>> timeline(@PathVariable long errandId) {
        CurrentUser.get();
        return Result.ok(queryUseCase.statusLog(errandId).stream()
                .map(c -> Map.<String, Object>of(
                        "time", c.time().toString(), "from", c.from(), "to", c.to(),
                        "round", c.round(), "operatorId", c.operatorId()))
                .toList());
    }

    /**
     * 抢单。requestId 由客户端传入做幂等，重试用同一个值。
     * 并发抢单演示时前端会同时发 N 个请求、各自带不同 requestId。
     */
    public record GrabRequestBody(Integer expectedRound) {}

    @PostMapping("/{errandId}/grab")
    public org.springframework.http.ResponseEntity<Result<GrabErrandUseCase.Result>> grab(
            @PathVariable long errandId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody(required = false) GrabRequestBody body) {
        var result = grabUseCase.grab(new GrabErrandUseCase.Command(errandId, CurrentUser.get(),
                requestId, body == null || body.expectedRound() == null ? -1 : body.expectedRound()));
        return grabResponse(result);
    }

    @GetMapping("/{errandId}/grab-requests/{requestId}")
    public org.springframework.http.ResponseEntity<Result<GrabErrandUseCase.Result>> grabRequest(
            @PathVariable long errandId, @PathVariable String requestId) {
        return grabResponse(grabUseCase.query(errandId, CurrentUser.get(), requestId));
    }

    private org.springframework.http.ResponseEntity<Result<GrabErrandUseCase.Result>> grabResponse(GrabErrandUseCase.Result result) {
        var response = org.springframework.http.ResponseEntity.status(result.httpStatus());
        if (result.retryAfterMillis() > 0) response.header("Retry-After", Long.toString((result.retryAfterMillis()+999)/1000));
        return response.body(result.grabbed() ? Result.ok(result) : Result.fail(result.code(), result));
    }

    public record ConfirmRequestBody(Integer expectedRound) {}

    @PostMapping("/{errandId}/confirm")
    public org.springframework.http.ResponseEntity<Result<Void>> confirm(
            @PathVariable long errandId, @RequestBody(required = false) ConfirmRequestBody body) {
        long runnerId = CurrentUser.get();
        if (body == null || body.expectedRound() == null || body.expectedRound() < 0) {
            return org.springframework.http.ResponseEntity.badRequest().body(Result.fail(ErrorCode.INVALID_REQUEST));
        }
        // 将用户点击时的轮次绑定到确认请求，防止 A→B→A 后旧确认操作新占位。
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, runnerId, body.expectedRound()));
        return org.springframework.http.ResponseEntity.ok(Result.ok(null));
    }

    @PostMapping("/{errandId}/pickup")
    public Result<Void> pickup(@PathVariable long errandId) {
        pickUpUseCase.pickUp(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    @PostMapping("/{errandId}/deliver")
    public Result<Void> deliver(@PathVariable long errandId) {
        deliverUseCase.deliver(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    @PostMapping("/{errandId}/settle")
    public Result<Map<String, Object>> settle(@PathVariable long errandId) {
        var r = settleUseCase.settle(errandId, CurrentUser.get());
        return Result.ok(Map.of("result", r.name()));
    }

    @PostMapping("/{errandId}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable long errandId) {
        var r = refundUseCase.cancelAndRefund(errandId, CurrentUser.get());
        return Result.ok(Map.of("result", r.name()));
    }

    @PostMapping("/{errandId}/dispute")
    public Result<Void> dispute(@PathVariable long errandId) {
        disputeUseCase.raise(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    public record ArbitrateRequest(String favor) {}

    @PostMapping("/{errandId}/arbitrate")
    public Result<Map<String, Object>> arbitrate(@PathVariable long errandId,
                                                 @RequestBody ArbitrateRequest req) {
        var favor = "PUBLISHER".equalsIgnoreCase(req.favor())
                ? ArbitrateErrandUseCase.Favor.PUBLISHER
                : ArbitrateErrandUseCase.Favor.RUNNER;
        var r = arbitrateUseCase.arbitrate(errandId, favor, CurrentUser.get());
        return Result.ok(Map.of("result", r.name()));
    }

    /** 统一的任务卡片：列表与详情共用，保证 availableActions 处处一致 */
    private Map<String, Object> toCard(Errand e, long viewer) {
        var resolved = actionResolver.resolve(e, viewer);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", e.id());
        card.put("title", e.title());
        card.put("status", e.status().name());
        card.put("type", e.type().name());
        card.put("rewardCents", e.reward().cents());
        card.put("slotTotal", e.slotTotal());
        card.put("slotTaken", e.slotTaken());
        card.put("publisherId", e.publisherId());
        card.put("grabberId", e.grabberId() == null ? -1L : e.grabberId());
        card.put("round", e.round());
        card.put("role", resolved.role());
        card.put("availableActions", resolved.actions());
        return card;
    }
}
