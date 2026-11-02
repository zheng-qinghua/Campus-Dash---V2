package com.campusdash.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntryProtectionFilterTest {
    EntryProtectionFilter filter(GatewayBudget budget, String trusted) {
        return new EntryProtectionFilter(budget, trusted, 10, 1000, 1000, 100, 100, 2, 10);
    }
    @Test void forgedForwardedIdentityIsIgnoredAndHeadersAreRemoved() {
        var budget = mock(GatewayBudget.class);
        when(budget.check(anyString(), anyDouble(), anyInt())).thenReturn(Mono.just(new GatewayBudget.Decision(true, false, 0)));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/errands/1/grab")
                .remoteAddress(new InetSocketAddress("203.0.113.7", 42)).header("X-Forwarded-For", "1.2.3.4")
                .header("X-User-Id", "9001"));
        var filter = filter(budget, "");
        assertEquals("203.0.113.7", filter.sourceIp(exchange));
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.filter(exchange, e -> { assertNull(e.getRequest().getHeaders().getFirst("X-User-Id"));
            assertNull(e.getRequest().getHeaders().getFirst("X-Forwarded-For")); forwarded.set(true); return Mono.empty(); }).block();
        assertTrue(forwarded.get());
    }
    @Test void trustedProxyUsesNearestUntrustedHop() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/health")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 42)).header("X-Forwarded-For", "1.2.3.4, 203.0.113.7, 127.0.0.2"));
        assertEquals("203.0.113.7", filter(mock(GatewayBudget.class), "127.0.0.1,127.0.0.2").sourceIp(exchange));
    }
    @Test void internalEndpointAndEncodedBypassesNeverReachBackend() {
        var budget = mock(GatewayBudget.class);
        for (String path : new String[]{"/api/internal/stats", "/api//internal/stats", "/api/%69nternal/stats", "/api/internal;xx/stats"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            filter(budget, "").filter(exchange, e -> { fail("不得转发内部路径"); return Mono.empty(); }).block();
            assertTrue(exchange.getResponse().getStatusCode().is4xxClientError());
        }
        verifyNoInteractions(budget);
    }
    @Test void limitAndRedisFailureHaveDifferentStatusesAndRetryAfterCeils() {
        for (boolean unavailable : new boolean[]{false, true}) {
            var budget = mock(GatewayBudget.class);
            when(budget.check(anyString(), anyDouble(), anyInt())).thenReturn(Mono.just(new GatewayBudget.Decision(false, unavailable, 1001)));
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/errands/1/grab"));
            filter(budget, "").filter(exchange, e -> { fail("不得放行"); return Mono.empty(); }).block();
            assertEquals(unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
            assertEquals("2", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        }
    }
}
