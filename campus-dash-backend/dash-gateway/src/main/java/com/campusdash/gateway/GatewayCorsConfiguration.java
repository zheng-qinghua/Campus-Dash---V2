package com.campusdash.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
import java.util.List;

@Configuration
public class GatewayCorsConfiguration {
    @Bean CorsWebFilter corsFilter(@Value("${dash.gateway.cors-origins:http://localhost:5173,http://127.0.0.1:5173}") String origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        config.setExposedHeaders(List.of("Retry-After")); config.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**", config);
        return new FirstCorsFilter(source);
    }
    private static class FirstCorsFilter extends CorsWebFilter implements Ordered {
        FirstCorsFilter(UrlBasedCorsConfigurationSource source) { super(source); }
        @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    }
}
