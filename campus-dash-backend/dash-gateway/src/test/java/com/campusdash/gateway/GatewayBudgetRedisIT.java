package com.campusdash.gateway;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import java.net.Socket;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GatewayBudgetRedisIT {
    static LettuceConnectionFactory connection;
    static ReactiveStringRedisTemplate redis;
    final String key = "dash:gw:it:{" + UUID.randomUUID() + "}:budget";
    @BeforeAll static void connect() {
        try (var socket = new Socket("127.0.0.1", 6380)) { }
        catch (Exception unavailable) { Assumptions.abort("Redis 6380 未启动，跳过真实网关预算测试"); }
        connection = redisConnectionFactory("127.0.0.1", 6380); connection.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connection);
    }
    @AfterAll static void close() { if (connection != null) connection.destroy(); }
    @AfterEach void cleanup() { redis.delete(key).block(); }
    @Test void twoGatewaysShareBudgetAndReturnRetryDelay() {
        var first = new GatewayBudget(redis, 1000); var second = new GatewayBudget(redis, 1000);
        assertTrue(first.check(key, .001, 2).block().allowed());
        assertTrue(second.check(key, .001, 2).block().allowed());
        var denied = first.check(key, .001, 2).block();
        assertFalse(denied.allowed()); assertFalse(denied.unavailable()); assertTrue(denied.retryMillis() > 1000);
    }
    @Test void scriptFailureIsDependencyFailureAndNeverAllowed() {
        redis.opsForValue().set(key, "wrong-type").block();
        var failure = new GatewayBudget(redis, 1000).check(key, 1, 2).block();
        assertFalse(failure.allowed()); assertTrue(failure.unavailable());
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
