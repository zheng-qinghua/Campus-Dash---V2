package com.campusdash.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }

    @Bean RouteLocator routes(RouteLocatorBuilder builder,
            @Value("${dash.gateway.backend-uri:http://127.0.0.1:8080}") String backend,
            @Value("${dash.gateway.backend-ws-uri:ws://127.0.0.1:8080}") String websocket) {
        return builder.routes()
                .route("websocket", route -> route.path("/ws/**").uri(websocket))
                .route("backend", route -> route.path("/api/**", "/oauth2/**", "/login/oauth2/**")
                        .filters(filters -> filters.dedupeResponseHeader("Access-Control-Allow-Origin Access-Control-Allow-Credentials", "RETAIN_FIRST"))
                        .uri(backend))
                .build();
    }
}
