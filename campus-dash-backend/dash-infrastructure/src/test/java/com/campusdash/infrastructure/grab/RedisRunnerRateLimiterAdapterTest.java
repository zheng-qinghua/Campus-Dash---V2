package com.campusdash.infrastructure.grab;

import com.campusdash.domain.grab.ports.RunnerRateLimiterPort.Status;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RedisRunnerRateLimiterAdapterTest {
    @Test void redisFailureClosesWritesButAllowsBoundedIndependentQueries() {
        var unavailable = new StringRedisTemplate() {
            @Override public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new IllegalStateException("Redis unavailable");
            }
        };
        var limiter = new RedisRunnerRateLimiterAdapter(unavailable, 2, 4, .5, 2, 5, 10, 1);
        assertEquals(Status.UNAVAILABLE, limiter.checkGrab(1, 2).status());
        assertEquals(Status.ALLOWED, limiter.checkQuery(1).status());
        // 测试只要求预算有限，避免纳秒时钟刚好跨秒导致偶发失败。
        long rejected = java.util.stream.IntStream.range(0, 100).filter(i -> limiter.checkQuery(1).status() == Status.UNAVAILABLE).count();
        assertTrue(rejected > 90);
    }
}
