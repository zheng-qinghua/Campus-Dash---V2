package com.campusdash.application.usecase;

import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.ports.GrabProjectionPort;
import com.campusdash.domain.grab.ports.GrabRateLimiterPort;
import com.campusdash.domain.grab.ports.RunnerRateLimiterPort;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class GrabErrandUseCaseTest {
    @Test
    @DisplayName("跑腿限流拒绝后直接返回 429，不访问 DB、信用分或 Redis 占位")
    void grab_returns_rate_limited_before_downstream_calls() {
        AtomicInteger hotCalls = new AtomicInteger();
        var useCase = useCase((errand, runner) -> { hotCalls.incrementAndGet(); return false; },
                RunnerRateLimiterPort.Decision.limited(1000));
        var result = useCase.grab(new GrabErrandUseCase.Command(10001L, 2001L, UUID.randomUUID().toString(), 0));
        assertFalse(result.grabbed());
        assertEquals(ErrorCode.GRAB_RATE_LIMITED, result.code());
        assertEquals(429, result.httpStatus());
        assertEquals(0, hotCalls.get());
    }

    @Test
    @DisplayName("结果查询限流拒绝后不执行热点限流或下游业务")
    void query_limit_stops_before_persistence() {
        var useCase = useCase((errand, runner) -> fail("跑腿限流拒绝后不能继续热点准入"),
                RunnerRateLimiterPort.Decision.limited(750));
        var result = useCase.query(10001L, 2001L, UUID.randomUUID().toString());
        assertEquals(ErrorCode.GRAB_RATE_LIMITED, result.code());
        assertEquals(429, result.httpStatus());
        assertTrue(result.retryAfterMillis() >= 750);
    }

    @Test
    @DisplayName("限流基础设施不可用返回 503，不伪装成用户超限")
    void unavailable_runner_limiter_is_not_reported_as_rate_limit() {
        var useCase = useCase((errand, runner) -> fail("依赖故障不能继续准入"),
                RunnerRateLimiterPort.Decision.unavailable());
        var result = useCase.grab(new GrabErrandUseCase.Command(10001L, 2001L, UUID.randomUUID().toString(), 0));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, result.code());
        assertEquals(503, result.httpStatus());
        assertFalse(result.grabbed());
    }

    @Test
    @DisplayName("无效请求 ID 在执行限流和持久操作前被拒绝")
    void invalid_request_id_is_rejected() {
        var useCase = useCase((errand, runner) -> fail("无效 ID 不得进入准入"),
                RunnerRateLimiterPort.Decision.allowed());
        var result = useCase.grab(new GrabErrandUseCase.Command(10001L, 2001L, "req-limited", 0));
        assertEquals(ErrorCode.INVALID_REQUEST, result.code());
        assertEquals(400, result.httpStatus());
    }

    @Test
    @DisplayName("抢单容量耗尽时不访问 DB，查询使用独立容量，完成后原 ID 可重新准入")
    void in_flight_limit_protects_database_without_blocking_result_queries() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var writeLimits = new AtomicInteger();
        var queryLimits = new AtomicInteger();
        var repository = reads(id -> {
            reads.incrementAndGet();
            if (id == 10001L) {
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }
            return Optional.empty();
        });
        RunnerRateLimiterPort limiter = new RunnerRateLimiterPort() {
            @Override public Decision checkGrab(long runnerId, long errandId) {
                writeLimits.incrementAndGet(); return Decision.allowed();
            }
            @Override public Decision checkQuery(long runnerId) {
                queryLimits.incrementAndGet(); return Decision.allowed();
            }
        };
        var service = useCase(repository, limiter, 1);
        var active = new GrabErrandUseCase.Command(10001L, 2001L, UUID.randomUUID().toString(), 0);
        var overflow = new GrabErrandUseCase.Command(10002L, 2002L, UUID.randomUUID().toString(), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var pending = executor.submit(() -> service.grab(active));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var unavailable = service.grab(overflow);
                assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable.code());
                assertEquals(503, unavailable.httpStatus());
                assertEquals(overflow.requestId(), unavailable.requestId());
                assertTrue(unavailable.retryAfterMillis() > 0);
                assertEquals(1, reads.get(), "满载拒绝不能进入首次 DB 读取，更不能登记请求");
                assertEquals(2, writeLimits.get(), "过载请求也应先经过共享跑腿限流");
                assertEquals(404, service.query(10003L, 2003L, UUID.randomUUID().toString()).httpStatus());
                assertEquals(1, queryLimits.get());
                assertEquals(2, reads.get(), "独立查询容量仍可回查数据库");
                release.countDown();
                assertEquals(404, pending.get(5, TimeUnit.SECONDS).httpStatus());
                assertEquals(404, service.grab(overflow).httpStatus(), "完成后必须归还许可，原 ID 可以重试");
                assertEquals(3, reads.get());
            } finally { release.countDown(); }
        }
    }

    @Test
    @DisplayName("数据库异常返回后归还抢单许可，不会永久耗尽容量")
    void failed_database_read_releases_in_flight_capacity() {
        var calls = new AtomicInteger();
        var repository = reads(id -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("模拟数据库不可用");
            return Optional.empty();
        });
        var service = useCase(repository, allowedLimiter(), 1);
        var command = new GrabErrandUseCase.Command(10001L, 2001L, UUID.randomUUID().toString(), 0);
        assertEquals(503, service.grab(command).httpStatus());
        assertEquals(404, service.grab(command).httpStatus());
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("抢单在途容量必须为正数")
    void invalid_in_flight_capacity_is_rejected_at_startup() {
        assertThrows(IllegalArgumentException.class, () -> useCase(forbidden(ErrandRepository.class), allowedLimiter(), 0));
    }

    private GrabErrandUseCase useCase(ErrandRepository repository, RunnerRateLimiterPort limiter, int capacity) {
        return new GrabErrandUseCase(repository, null, null, null, forbidden(GrabProjectionPort.class),
                null, null, forbidden(CreditRepository.class), null,
                (errand, runner) -> { throw new AssertionError("订单不存在时不能进入热点准入"); },
                limiter, new SnowflakeIdGenerator(1), null, null, null, 40, 5, capacity);
    }

    private RunnerRateLimiterPort allowedLimiter() {
        return new RunnerRateLimiterPort() {
            @Override public Decision checkGrab(long runnerId, long errandId) { return Decision.allowed(); }
            @Override public Decision checkQuery(long runnerId) { return Decision.allowed(); }
        };
    }

    private ErrandRepository reads(Function<Long, Optional<Errand>> callback) {
        return (ErrandRepository) Proxy.newProxyInstance(ErrandRepository.class.getClassLoader(),
                new Class<?>[]{ErrandRepository.class}, (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) return callback.apply((Long) args[0]);
                    throw new AssertionError("不得访问 " + method.getName());
                });
    }

    private GrabErrandUseCase useCase(GrabRateLimiterPort hotspot, RunnerRateLimiterPort.Decision decision) {
        RunnerRateLimiterPort runnerLimiter = new RunnerRateLimiterPort() {
            @Override public Decision checkGrab(long runnerId, long errandId) { return decision; }
            @Override public Decision checkQuery(long runnerId) { return decision; }
        };
        return new GrabErrandUseCase(
                forbidden(ErrandRepository.class), null, null, null, forbidden(GrabProjectionPort.class),
                null, null, forbidden(CreditRepository.class), null, hotspot, runnerLimiter,
                new SnowflakeIdGenerator(1), null, null, null, 40, 5);
    }

    /** 一旦误调用下游就直接使测试失败，不能靠 null 异常被业务 catch 吞掉。 */
    @SuppressWarnings("unchecked")
    private <T> T forbidden(Class<T> port) {
        return (T) Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port}, (proxy, method, args) -> {
            throw new AssertionError("拒绝后不应访问 " + port.getSimpleName() + "." + method.getName());
        });
    }
}
