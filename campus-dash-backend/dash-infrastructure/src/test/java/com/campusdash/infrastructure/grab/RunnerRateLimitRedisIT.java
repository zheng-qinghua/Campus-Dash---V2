package com.campusdash.infrastructure.grab;

import com.campusdash.domain.grab.ports.RunnerRateLimiterPort.Status;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerRateLimitRedisIT {
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    final long runner = 8_000_000_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000);
    @BeforeAll static void connect() {
        try (var socket = new Socket("127.0.0.1", 6380)) { }
        catch (Exception unavailable) { Assumptions.abort("Redis 6380 未启动，跳过真实脚本测试"); }
        connection = redisConnectionFactory("127.0.0.1", 6380); connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection); redis.afterPropertiesSet();
    }
    @AfterAll static void close() { if (connection != null) connection.destroy(); }
    @AfterEach void cleanup() {
        // 仅删除本测试随机跑腿的明确键，不扫描或删除业务数据。
        redis.delete(List.of(key("all"), key("errand:1"), key("errand:2"), key("query")));
    }
    String key(String suffix) { return "dash:rl:v1:{runner:" + runner + "}:" + suffix; }
    RedisRunnerRateLimiterAdapter adapter() { return new RedisRunnerRateLimiterAdapter(redis, .001, 4, .001, 2, 5, 10, 10); }
    @Test void twoInstancesShareAtomicDualBudgetAndRejectedRequestDoesNotChargeOtherBucket() throws Exception {
        var one = adapter(); var two = adapter();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Status>> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                var selected = i % 2 == 0 ? one : two;
                requests.add(pool.submit(() -> { start.await(); return selected.checkGrab(runner, 1).status(); }));
            }
            start.countDown();
            int passed = 0; for (var request : requests) if (request.get() == Status.ALLOWED) passed++;
            assertEquals(2, passed);
        }
        assertEquals(Status.ALLOWED, one.checkGrab(runner, 2).status());
        assertEquals(Status.ALLOWED, two.checkGrab(runner, 2).status());
        assertEquals(Status.LIMITED, one.checkGrab(runner, 2).status());
        assertEquals(Status.ALLOWED, one.checkQuery(runner).status());
        assertTrue(redis.getExpire(key("all"), TimeUnit.MILLISECONDS) >= 4_000_000);
    }
    @Test void futureClockWatermarkDoesNotRefillRepeatedly() {
        redis.opsForHash().putAll(key("all"), java.util.Map.of("tokens", "0", "time", Long.toString(System.currentTimeMillis() + 10_000)));
        assertEquals(Status.LIMITED, adapter().checkGrab(runner, 1).status());
        assertEquals(Status.LIMITED, adapter().checkGrab(runner, 1).status());
    }

    private static LettuceConnectionFactory redisConnectionFactory(String host, int port) {
        var config = new org.springframework.data.redis.connection.RedisStandaloneConfiguration(host, port);
        String username = System.getenv("SPRING_DATA_REDIS_USERNAME");
        String password = System.getenv("SPRING_DATA_REDIS_PASSWORD");
        if (username != null && !username.isBlank()) config.setUsername(username);
        if (password != null && !password.isBlank()) config.setPassword(password);
        return new LettuceConnectionFactory(config);
    }
}
