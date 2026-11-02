package com.campusdash.infrastructure.grab;

import com.campusdash.domain.grab.ports.RunnerRateLimiterPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** 跨实例双桶，依赖异常拒绝抢单；查询单独使用受限的本机恢复预算。 */
@Component
public class RedisRunnerRateLimiterAdapter implements RunnerRateLimiterPort {
    private static final Logger log = LoggerFactory.getLogger(RedisRunnerRateLimiterAdapter.class);
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("lua/runner-rate-limit.lua"));
        SCRIPT.setResultType(List.class);
    }
    private final StringRedisTemplate redis;
    private final double runnerRate, errandRate, queryRate;
    private final int runnerBurst, errandBurst, queryBurst, recoveryPerSecond;
    private long recoverySecond;
    private int recoveryCount;

    public RedisRunnerRateLimiterAdapter(StringRedisTemplate redis,
            @Value("${dash.grab.runner-limit.rate:2}") double runnerRate,
            @Value("${dash.grab.runner-limit.burst:4}") int runnerBurst,
            @Value("${dash.grab.runner-limit.errand-rate:0.5}") double errandRate,
            @Value("${dash.grab.runner-limit.errand-burst:2}") int errandBurst,
            @Value("${dash.grab.runner-limit.query-rate:5}") double queryRate,
            @Value("${dash.grab.runner-limit.query-burst:10}") int queryBurst,
            @Value("${dash.grab.runner-limit.recovery-query-per-second:10}") int recoveryPerSecond) {
        if (runnerRate <= 0 || errandRate <= 0 || queryRate <= 0 || runnerBurst < 1
                || errandBurst < 1 || queryBurst < 1 || recoveryPerSecond < 1
                || !Double.isFinite(runnerRate) || !Double.isFinite(errandRate) || !Double.isFinite(queryRate)) {
            throw new IllegalArgumentException("跑腿限流速率和容量必须为有效正数");
        }
        this.redis = redis; this.runnerRate = runnerRate; this.runnerBurst = runnerBurst;
        this.errandRate = errandRate; this.errandBurst = errandBurst;
        this.queryRate = queryRate; this.queryBurst = queryBurst; this.recoveryPerSecond = recoveryPerSecond;
    }

    @Override public Decision checkGrab(long runnerId, long errandId) {
        if (runnerId <= 0 || errandId <= 0) return Decision.unavailable();
        String prefix = "dash:rl:v1:{runner:" + runnerId + "}:";
        return execute(List.of(prefix + "all", prefix + "errand:" + errandId),
                runnerRate, runnerBurst, errandRate, errandBurst);
    }

    @Override public Decision checkQuery(long runnerId) {
        if (runnerId <= 0) return Decision.unavailable();
        Decision decision = execute(List.of("dash:rl:v1:{runner:" + runnerId + "}:query"), queryRate, queryBurst);
        return decision.status() == Status.UNAVAILABLE ? recoveryBudget() : decision;
    }

    private Decision execute(List<String> keys, Object... values) {
        try {
            Object[] args = new Object[values.length];
            for (int i = 0; i < values.length; i++) args[i] = String.valueOf(values[i]);
            List<?> result = redis.execute(SCRIPT, keys, args);
            if (result == null || result.size() != 2) return Decision.unavailable();
            return ((Number) result.get(0)).longValue() == 1 ? Decision.allowed()
                    : Decision.limited(((Number) result.get(1)).longValue());
        } catch (RuntimeException failure) {
            log.warn("跑腿共享限流不可用：{}", failure.getClass().getSimpleName());
            return Decision.unavailable();
        }
    }

    private synchronized Decision recoveryBudget() {
        long second = System.nanoTime() / 1_000_000_000L;
        if (second != recoverySecond) { recoverySecond = second; recoveryCount = 0; }
        if (recoveryCount >= recoveryPerSecond) return Decision.unavailable();
        recoveryCount++;
        return Decision.allowed();
    }
}
