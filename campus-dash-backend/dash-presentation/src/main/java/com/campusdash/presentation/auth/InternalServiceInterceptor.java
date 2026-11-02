package com.campusdash.presentation.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 内部端点独立校验服务凭证；空配置关闭入口，公网网关也会阻断此路径。 */
@Component
public class InternalServiceInterceptor implements HandlerInterceptor {
    private final byte[] expected;
    public InternalServiceInterceptor(@Value("${dash.auth.internal-token:}") String token) {
        expected = token.getBytes(StandardCharsets.UTF_8);
    }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String presented = request.getHeader("X-Internal-Token");
        if (expected.length < 32 || presented == null || !MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(403); return false;
        }
        return true;
    }
}
