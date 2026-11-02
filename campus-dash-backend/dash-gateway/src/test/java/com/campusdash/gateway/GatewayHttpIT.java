package com.campusdash.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 实际启动 WebFlux 网关，通过随机端口访问临时后端，验证路由与过滤顺序。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayHttpIT {
    static final HttpServer backend;
    static final AtomicInteger forwarded = new AtomicInteger();
    static final AtomicReference<String> identityHeader = new AtomicReference<>();
    static {
        try {
            backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backend.createContext("/", exchange -> {
                forwarded.incrementAndGet();
                identityHeader.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                byte[] body = "{\"code\":\"OK\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "http://localhost:5173");
                exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            backend.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("dash.gateway.backend-uri", () -> "http://127.0.0.1:" + backend.getAddress().getPort());
        registry.add("dash.gateway.redis-timeout-ms", () -> "2000");
    }
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    WebTestClient client;
    @BeforeEach void connectHttp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }
    @BeforeAll static void requireRedis() {
        try (var socket = new Socket("127.0.0.1", 6380)) { }
        catch (Exception unavailable) { Assumptions.abort("Redis 未启动，跳过实际网关 HTTP 集成测试"); }
    }
    @AfterAll static void close() { backend.stop(0); }
    @Test void routeRemovesSpoofedIdentityAndDeduplicatesCorsResponse() {
        client.get().uri("/api/health").header("Origin", "http://localhost:5173")
                .header("X-User-Id", "9001").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
                .expectBody().jsonPath("$.code").isEqualTo("OK");
        assertNull(identityHeader.get());
    }
    @Test void internalEndpointNeverReachesBackend() {
        int previous = forwarded.get();
        client.get().uri("/api/internal/cache-stats").exchange().expectStatus().isNotFound();
        assertEquals(previous, forwarded.get());
    }
    @Test void corsPreflightAllowsRequestIdAndExposesRetryAfter() {
        client.options().uri("/api/errands/1/grab").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type,X-Request-Id")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Expose-Headers", "Retry-After");
    }
}
