package com.campusdash.application.usecase.query;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GetErrandDetailUseCaseTest {

    @Test
    @DisplayName("布隆端口会抛异常也不影响详情回源，读路径不调用无用布隆查询")
    void bloom_failure_cannot_block_existing_order_read_through() {
        Errand errand = Errand.draft(101L, 1L, 1001L, ErrandType.DELIVERY,
                "布隆异常回源", Money.ofCents(100), 1);
        errand.publish(0);
        var reads = new AtomicInteger();
        var cache = new CacheWithFailingBloom();
        var useCase = new GetErrandDetailUseCase(repository(Optional.of(errand), reads), cache);

        String result = useCase.detailJson(101L).orElseThrow();

        assertTrue(result.contains("\"id\":\"101\""));
        assertTrue(result.contains("\"status\":\"PUBLISHED\""));
        assertEquals(0, cache.bloomCalls);
        assertEquals(1, reads.get());
        assertEquals(result, useCase.detailJson(101L).orElseThrow());
        assertEquals(1, reads.get(), "正常详情仍保留缓存命中");
    }

    @Test
    @DisplayName("真实不存在仍写短空缓存，重复缓存用例调用不再次回源或调用布隆")
    void absent_order_retains_negative_cache_without_bloom_dependency() {
        var reads = new AtomicInteger();
        var cache = new CacheWithFailingBloom();
        var useCase = new GetErrandDetailUseCase(repository(Optional.empty(), reads), cache);

        assertTrue(useCase.detailJson(102L).isEmpty());
        assertTrue(cache.cached.orElseThrow().isEmpty());
        assertTrue(useCase.detailJson(102L).isEmpty());
        assertEquals(1, reads.get());
        assertEquals(0, cache.bloomCalls);
    }

    private ErrandRepository repository(Optional<Errand> result, AtomicInteger reads) {
        return (ErrandRepository) Proxy.newProxyInstance(ErrandRepository.class.getClassLoader(),
                new Class<?>[]{ErrandRepository.class}, (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        reads.incrementAndGet();
                        return result;
                    }
                    throw new AssertionError("详情读取不应调用 " + method.getName());
                });
    }

    private static final class CacheWithFailingBloom implements ErrandCachePort {
        private Optional<CachedErrand> cached = Optional.empty();
        private int bloomCalls;

        @Override public Optional<CachedErrand> get(long id) { return cached; }
        @Override public void put(long id, String json) {
            cached = Optional.of(new CachedErrand(json, System.currentTimeMillis() + 60_000, false));
        }
        @Override public void putEmpty(long id) {
            cached = Optional.of(new CachedErrand(null, System.currentTimeMillis() + 60_000, true));
        }
        @Override public void evict(long id) { cached = Optional.empty(); }
        @Override public boolean tryAcquireRebuild(long id) { return true; }
        @Override public void releaseRebuild(long id) {}
        @Override public boolean mightExist(long id) {
            bloomCalls++;
            throw new IllegalStateException("模拟布隆 Redis 不可用");
        }
        @Override public void registerExisting(long id) {}
    }
}
