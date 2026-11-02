package com.campusdash.infrastructure.cache;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Redis 原子条目令牌：重复读取不能弹出候选，旧删除不能影响新资格。 */
class RedisCandidateQueueIT {
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private RedisCandidateQueueAdapter queue;
    private long id;
    @BeforeAll static void connect() throws Exception {
        String host = System.getProperty("it.redis.host", "127.0.0.1");
        int port = Integer.getInteger("it.redis.port", 6380);
        boolean available;
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(host, port), 500); available = true; }
        catch (Exception e) { available = false; }
        if (Boolean.getBoolean("requireMiddleware")) assertTrue(available, "必须提供真实 Redis");
        Assumptions.assumeTrue(available, "真实 Redis 不可用");
        connection = redisConnectionFactory(host, port); connection.afterPropertiesSet(); connection.start();
        redis = new StringRedisTemplate(connection); redis.afterPropertiesSet();
    }
    @AfterAll static void disconnect() { if (connection != null) connection.destroy(); }
    @BeforeEach void setup() {
        id = 8_700_000_000_000_000_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000_000L);
        queue = new RedisCandidateQueueAdapter(redis);
    }
    @AfterEach void cleanup() {
        redis.delete(List.of("errand:candidates:{" + id + "}", "errand:candidate-meta:{" + id + "}"));
    }

    @Test void concurrentPeekDoesNotConsumeOrReorderTheCandidate() throws Exception {
        queue.offer(id, 100, 1, 2);
        queue.offer(id, 101, 2, 2);
        var head = queue.peekBest(id).orElseThrow();
        queue.offer(id, 100, 100, 2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> queue.peekBest(id).orElseThrow());
            var second = pool.submit(() -> queue.peekBest(id).orElseThrow());
            assertEquals(head, first.get());
            assertEquals(head, second.get());
        }
        assertEquals(2, queue.size(id));
        assertEquals(2, head.requestRound(), "重复 offer 不得篡改尚有效的入队意图");
    }

    @Test void delayedRemovalCannotDeleteNewEntryOfTheSameRunner() {
        queue.offer(id, 100, 1, 0);
        var old = queue.peekBest(id).orElseThrow();
        queue.offer(id, 100, 1, 2);
        var fresh = queue.peekBest(id).orElseThrow();
        assertNotEquals(old.token(), fresh.token());
        assertEquals(2, fresh.requestRound());
        queue.offer(id, 100, 0, 0);
        assertEquals(fresh, queue.peekBest(id).orElseThrow(), "迟到旧轮次 offer 不能覆盖新资格");
        assertFalse(queue.remove(id, old));
        assertEquals(fresh, queue.peekBest(id).orElseThrow());
        assertEquals(1, queue.size(id));
    }
    @Test void explicitOfferRestoresLostMetadataInTheNewCycle() {
        queue.offer(id, 100, 1, 0);
        redis.delete("errand:candidate-meta:{" + id + "}");
        queue.offer(id, 100, 2, 2);
        assertEquals(2, queue.peekBest(id).orElseThrow().requestRound());
        assertEquals(1, queue.size(id));
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
