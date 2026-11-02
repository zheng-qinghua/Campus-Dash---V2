package com.campusdash.infrastructure.auth;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.net.Socket;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class JwtAuthIsolationRedisIT {
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static final String SECRET = java.util.Base64.getEncoder().encodeToString(io.jsonwebtoken.Jwts.SIG.HS256.key().build().getEncoded());
    final String issuer = "isolation-it-" + java.util.UUID.randomUUID();
    @BeforeAll static void connect() {
        try (var socket = new Socket("127.0.0.1", 6380)) { }
        catch (Exception unavailable) { Assumptions.abort("Redis 6380 未启动，跳过"); }
        connection = redisConnectionFactory("127.0.0.1", 6380); connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection); redis.afterPropertiesSet();
    }
    @AfterAll static void close() { if (connection != null) connection.destroy(); }
    @Test void demoTokenCannotBecomeLiveIdentityEvenWithSameSigningKey() {
        var demo = new JwtAuthAdapter(redis, SECRET, 1, 1, true, issuer);
        var live = new JwtAuthAdapter(redis, SECRET, 1, 1, false, issuer);
        String token = demo.login(12);
        assertEquals(12, demo.resolve(token).orElseThrow());
        assertTrue(live.resolve(token).isEmpty());
    }
    @Test void concurrentRefreshCanIssueOnlyOneNewSession() throws Exception {
        var one = new JwtAuthAdapter(redis, SECRET, 1, 1, false, issuer);
        var two = new JwtAuthAdapter(redis, SECRET, 1, 1, false, issuer);
        String original = one.loginWithRefresh(12).refreshToken();
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return one.refresh(original); });
            var second = pool.submit(() -> { start.await(); return two.refresh(original); });
            start.countDown(); var a = first.get(); var b = second.get();
            assertEquals(1, (a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0));
            a.ifPresent(value -> one.revokeRefresh(value.refreshToken()));
            b.ifPresent(value -> two.revokeRefresh(value.refreshToken()));
        }
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
