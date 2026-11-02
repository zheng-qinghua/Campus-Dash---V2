package com.campusdash.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;

/** 网关故障关闭适配：Redis 超时和真正超额采用不同 HTTP 语义。 */
@Component
public class GatewayBudget {
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("lua/gateway-rate-limit.lua"));
        SCRIPT.setResultType(List.class);
    }
    private final ReactiveStringRedisTemplate redis;
    private final Duration timeout;
    public GatewayBudget(ReactiveStringRedisTemplate redis,
            @Value("${dash.gateway.redis-timeout-ms:300}") long timeoutMillis) {
        this.redis = redis; this.timeout = Duration.ofMillis(timeoutMillis);
    }
    public record Decision(boolean allowed, boolean unavailable, long retryMillis) {}
    public Mono<Decision> check(String key, double rate, int burst) {
        return redis.execute(SCRIPT, List.of(key), List.of(String.valueOf(rate), String.valueOf(burst)))
                .next().map(value -> new Decision(((Number) value.get(0)).longValue() == 1,
                        false, ((Number) value.get(1)).longValue()))
                .switchIfEmpty(Mono.just(new Decision(false, true, 1000)))
                .timeout(timeout).onErrorReturn(new Decision(false, true, 1000));
    }
}
