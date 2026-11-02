package com.campusdash.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/** 外部来源只认 socket 地址；仅明确配置的代理可以提供转发链。 */
@Component
public class EntryProtectionFilter implements WebFilter, Ordered {
    private final GatewayBudget budget;
    private final Set<String> trustedProxies;
    private final Semaphore concurrency;
    private final double routeRate, ipRate, authRate;
    private final int routeBurst, ipBurst, authBurst;
    private long recoverySecond;
    private int recoveryCount;

    public EntryProtectionFilter(GatewayBudget budget,
            @Value("${dash.gateway.trusted-proxies:}") String trustedProxies,
            @Value("${dash.gateway.max-concurrent:1000}") int maxConcurrent,
            @Value("${dash.gateway.route-rate:1000}") double routeRate,
            @Value("${dash.gateway.route-burst:2000}") int routeBurst,
            @Value("${dash.gateway.ip-rate:100}") double ipRate,
            @Value("${dash.gateway.ip-burst:200}") int ipBurst,
            @Value("${dash.gateway.auth-ip-rate:2}") double authRate,
            @Value("${dash.gateway.auth-ip-burst:10}") int authBurst) {
        if (maxConcurrent < 1 || routeRate <= 0 || routeBurst < 1 || ipRate <= 0 || ipBurst < 1
                || authRate <= 0 || authBurst < 1 || !Double.isFinite(routeRate)
                || !Double.isFinite(ipRate) || !Double.isFinite(authRate))
            throw new IllegalArgumentException("网关配额必须为有效正数");
        this.budget = budget;
        this.trustedProxies = Arrays.stream(trustedProxies.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.concurrency = new Semaphore(maxConcurrent); this.routeRate = routeRate;
        this.routeBurst = routeBurst; this.ipRate = ipRate; this.ipBurst = ipBurst;
        this.authRate = authRate; this.authBurst = authBurst;
    }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String raw = exchange.getRequest().getURI().getRawPath().toLowerCase(java.util.Locale.ROOT);
        if (raw.contains("%") || raw.contains(";") || raw.contains("\\") || raw.contains(".."))
            return reject(exchange, HttpStatus.BAD_REQUEST, "INVALID_PATH", 0);
        String path = exchange.getRequest().getPath().value().replaceAll("/+", "/");
        if (path.equals("/api/internal") || path.startsWith("/api/internal/"))
            return reject(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND", 0);
        if (!concurrency.tryAcquire()) return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "GATEWAY_BUSY", 1000);
        String source = sourceIp(exchange);
        boolean auth = path.startsWith("/api/auth/") || path.startsWith("/oauth2/") || path.startsWith("/login/oauth2/");
        boolean query = path.matches("/api/errands/[^/]+/grab-requests/[^/]+");
        String route = auth ? "auth" : path.endsWith("/grab") ? "grab" : query ? "grab-query" : "api";
        ServerWebExchange sanitized = exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove("X-User-Id"); headers.remove("X-Runner-Id"); headers.remove("X-Campus-Id");
            headers.remove("X-Internal-Token"); headers.remove("Forwarded");
            headers.remove("X-Forwarded-For"); headers.remove("X-Forwarded-Host");
            headers.remove("X-Forwarded-Proto"); headers.remove("X-Forwarded-Port");
            headers.remove("X-Forwarded-Prefix"); headers.remove("X-Real-IP");
        })).build();
        // 不同 Redis slot 的层次顺序扣费，不做跨 slot 原子承诺，也不返还前层额度。
        return budget.check("dash:gw:v1:{route:" + route + "}:all", routeRate, routeBurst)
                .flatMap(first -> first.allowed() ? budget.check("dash:gw:v1:{ip:" + source + "}:" + (auth ? "auth" : "api"),
                        auth ? authRate : ipRate, auth ? authBurst : ipBurst) : Mono.just(first))
                .flatMap(decision -> decision.allowed() || (query && decision.unavailable() && recoveryBudget())
                        ? chain.filter(sanitized)
                        : reject(exchange, decision.unavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS,
                                decision.unavailable() ? "RATE_LIMIT_UNAVAILABLE" : "RATE_LIMITED", decision.retryMillis()))
                .doFinally(signal -> concurrency.release());
    }
    String sourceIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        String peer = remote == null ? "unknown" : remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
        if (!trustedProxies.contains(peer)) return peer;
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 2048) return peer;
        String[] addresses = forwarded.split(",");
        // 从最近一跳向前剥离受信任代理，不能相信客户端伪造的最左地址。
        for (int i = addresses.length - 1; i >= 0; i--) {
            String address = addresses[i].trim();
            if (!io.netty.util.NetUtil.isValidIpV4Address(address) && !io.netty.util.NetUtil.isValidIpV6Address(address)) return peer;
            if (!trustedProxies.contains(address)) return address;
        }
        return peer;
    }
    private synchronized boolean recoveryBudget() {
        long second = System.nanoTime() / 1_000_000_000L;
        if (second != recoverySecond) { recoverySecond = second; recoveryCount = 0; }
        return recoveryCount++ < 20;
    }
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, long retryMillis) {
        var response = exchange.getResponse();
        response.setStatusCode(status); response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Access-Control-Expose-Headers", "Retry-After");
        if (retryMillis > 0) response.getHeaders().set("Retry-After", String.valueOf(Math.max(1, (retryMillis + 999) / 1000)));
        byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"请求暂时无法处理\",\"data\":null}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
