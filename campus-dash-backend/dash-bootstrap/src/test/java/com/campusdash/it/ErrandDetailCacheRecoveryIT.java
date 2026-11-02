package com.campusdash.it;

import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.infrastructure.cache.RedisErrandCacheAdapter;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 HTTP、MySQL 提交与 Redis 负缓存；仅用闩锁固定慢读回填时序。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dash.mq.enabled=false", "dash.auth.allow-header-identity=false",
        "dash.auth.mode=session", "dash.auth.session-namespace=detail-cache-recovery-it"
})
@ActiveProfiles("test")
class ErrandDetailCacheRecoveryIT {
    private static final long PUBLISHER = 1001L;
    private static final long RUNNER = 2001L;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired AuthPort auth;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired PausingCache cache;
    private final List<String> tokens = new ArrayList<>();
    private final List<Long> cacheKeys = new ArrayList<>();
    private static BenchRun run;
    @RegisterExtension static final BenchTestOutcome outcome = new BenchTestOutcome();

    @BeforeAll static void requireMiddleware() {
        assertTrue(MiddlewareAvailable.check(), "详情恢复验收必须运行真实 MySQL/Redis");
        run = BenchRun.start("IT-DETAIL-CACHE-RECOVERY");
    }

    @AfterAll static void finishRun() {
        if (run != null) {
            run.finish(outcome.status(), outcome.summary("IT-DETAIL-CACHE-RECOVERY"));
            run.close();
        }
    }

    @AfterEach void cleanup() {
        cache.releasePause();
        cacheKeys.forEach(cache::evict);
        tokens.forEach(auth::logout);
    }

    @Test
    @DisplayName("发布提交前的负缓存不能让提交后的详情 API 返回不存在")
    void negative_cache_before_commit_cannot_hide_committed_order() throws Exception {
        long id = nextId();
        String token = token(PUBLISHER);
        try (Connection publication = dataSource.getConnection()) {
            publication.setAutoCommit(false);
            insertPublished(publication, id);
            run.track(id);

            assertMissing(detail(id, token));
            assertTrue(cache.get(id).orElseThrow().isEmpty(), "提交前的 HTTP 查询已写入真实负缓存");

            publication.commit();
            cache.registerExisting(id);
            assertPublished(detail(id, token), id, "PUBLISHER", "CANCEL");
            assertTrue(cache.get(id).orElseThrow().isEmpty(),
                    "不用删除负缓存或等待 TTL，也必须读取已提交订单");
        }
    }

    @Test
    @DisplayName("慢 GET 在发布提交和删除之后才写负缓存，当前及后续详情仍正确")
    void late_negative_cache_after_commit_cannot_override_database_existence() throws Exception {
        long id = nextId();
        String token = token(RUNNER);
        Pause pause = cache.pauseBeforeEmptyWrite(id);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var slowGet = executor.submit(() -> detail(id, token));
            try {
                assertTrue(pause.reached.await(10, TimeUnit.SECONDS), "慢 GET 必须已读到 DB 不存在");
                try (Connection publication = dataSource.getConnection()) {
                    publication.setAutoCommit(false);
                    insertPublished(publication, id);
                    publication.commit();
                    run.track(id);
                }
                cache.registerExisting(id);
                cache.evict(id); // 即使发布后立即删缓存，迟到的负缓存仍可能覆盖这次删除。
                pause.resume.countDown();

                assertPublished(slowGet.get(10, TimeUnit.SECONDS), id, "VIEWER", "GRAB");
                assertTrue(cache.get(id).orElseThrow().isEmpty(), "确认负缓存是在提交之后才写入");
                assertPublished(detail(id, token), id, "VIEWER", "GRAB");
            } finally {
                pause.resume.countDown();
            }
        }
    }

    @Test
    @DisplayName("真实不存在的订单在首次查询和负缓存命中后仍返回 ERRAND_NOT_FOUND")
    void genuinely_missing_order_remains_missing() {
        long id = nextId();
        String token = token(PUBLISHER);
        assertMissing(detail(id, token));
        assertTrue(cache.get(id).orElseThrow().isEmpty());
        assertMissing(detail(id, token));
    }

    private long nextId() {
        long id = ids.nextId();
        cacheKeys.add(id);
        return id;
    }

    private String token(long userId) {
        String token = auth.login(userId);
        tokens.add(token);
        return token;
    }

    private ResponseEntity<Map> detail(long id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("/api/errands/" + id, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private void assertMissing(ResponseEntity<Map> result) {
        assertEquals(200, result.getStatusCode().value());
        assertEquals("ERRAND_NOT_FOUND", result.getBody().get("code"));
        assertNull(result.getBody().get("data"));
    }

    private void assertPublished(ResponseEntity<Map> result, long id, String role, String action) {
        assertEquals(200, result.getStatusCode().value());
        assertEquals("OK", result.getBody().get("code"), result.getBody().toString());
        Map<?, ?> data = (Map<?, ?>) result.getBody().get("data");
        assertEquals(Long.toString(id), data.get("id"));
        assertEquals("PUBLISHED", data.get("status"));
        assertEquals(0, ((Number) data.get("round")).intValue());
        assertEquals(0, ((Number) data.get("slotTaken")).intValue());
        assertEquals(role, data.get("role"));
        assertEquals(List.of(action), data.get("availableActions"));
    }

    private void insertPublished(Connection connection, long id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO errand (id, campus_id, publisher_id, type, title,
                                    reward_amount, slot_total, slot_taken, status, round, version)
                VALUES (?, 1, ?, 'DELIVERY', 'bench_详情负缓存恢复', 100, 1, 0, 'PUBLISHED', 0, 1)
                """)) {
            statement.setLong(1, id);
            statement.setLong(2, PUBLISHER);
            assertEquals(1, statement.executeUpdate());
        }
    }

    @TestConfiguration
    static class CacheConfiguration {
        @Bean @Primary
        PausingCache pausingCache(RedisErrandCacheAdapter delegate) { return new PausingCache(delegate); }
    }

    private static final class Pause {
        final long id;
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final AtomicBoolean armed = new AtomicBoolean(true);
        Pause(long id) { this.id = id; }
    }

    static final class PausingCache implements ErrandCachePort {
        private final ErrandCachePort delegate;
        private volatile Pause pause;
        PausingCache(ErrandCachePort delegate) { this.delegate = delegate; }
        Pause pauseBeforeEmptyWrite(long id) { pause = new Pause(id); return pause; }
        void releasePause() {
            Pause current = pause;
            if (current != null) current.resume.countDown();
            pause = null;
        }
        @Override public Optional<CachedErrand> get(long id) { return delegate.get(id); }
        @Override public void put(long id, String json) { delegate.put(id, json); }
        @Override public void putEmpty(long id) {
            Pause current = pause;
            if (current != null && current.id == id && current.armed.compareAndSet(true, false)) {
                current.reached.countDown();
                try {
                    if (!current.resume.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待发布提交超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            delegate.putEmpty(id);
        }
        @Override public void evict(long id) { delegate.evict(id); }
        @Override public boolean tryAcquireRebuild(long id) { return delegate.tryAcquireRebuild(id); }
        @Override public void releaseRebuild(long id) { delegate.releaseRebuild(id); }
        @Override public boolean mightExist(long id) { return delegate.mightExist(id); }
        @Override public void registerExisting(long id) { delegate.registerExisting(id); }
    }
}
