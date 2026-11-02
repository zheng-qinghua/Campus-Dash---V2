package com.campusdash.presentation;

import com.campusdash.presentation.auth.AuthInterceptor;
import com.campusdash.presentation.auth.InternalServiceInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final List<String> corsOrigins;
    private final InternalServiceInterceptor internalServiceInterceptor;

    public WebConfig(AuthInterceptor authInterceptor,
                     InternalServiceInterceptor internalServiceInterceptor,
                     @Value("${dash.web.cors-origins:http://localhost:5173}") String corsOrigins) {
        this.authInterceptor = authInterceptor;
        this.internalServiceInterceptor = internalServiceInterceptor;
        this.corsOrigins = List.of(corsOrigins.split(","));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalServiceInterceptor).addPathPatterns("/api/internal/**");
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登录/登出本身不需要认证；健康检查供前端探测
                .excludePathPatterns("/api/auth/login", "/api/auth/refresh", "/api/health")
                // 内部端点由独立服务凭证拦截器校验，不接受普通用户令牌
                .excludePathPatterns("/api/internal/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Retry-After")
                .allowCredentials(true);
    }
}
