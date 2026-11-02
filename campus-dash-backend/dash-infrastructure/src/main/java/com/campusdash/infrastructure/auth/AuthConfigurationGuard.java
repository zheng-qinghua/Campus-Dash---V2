package com.campusdash.infrastructure.auth;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Set;

/** 在启动时阻断演示身份配置进入正式环境。 */
@Component
public class AuthConfigurationGuard {
    public AuthConfigurationGuard(Environment env) {
        Set<String> profiles = Set.copyOf(Arrays.asList(env.getActiveProfiles()));
        boolean production = profiles.contains("prod") || profiles.contains("production")
                || "production".equalsIgnoreCase(env.getProperty("dash.auth.environment", ""))
                || "prod".equalsIgnoreCase(env.getProperty("dash.auth.environment", ""));
        boolean demo = env.getProperty("dash.auth.demo-login-enabled", Boolean.class, false);
        boolean header = env.getProperty("dash.auth.allow-header-identity", Boolean.class, false);
        boolean local = profiles.contains("local") || profiles.contains("test") || profiles.contains("bench");
        if (demo && (!local || production)) throw new IllegalStateException("演示登录只能在 local/test/bench 显式开启");
        if (production && header) throw new IllegalStateException("生产环境禁止 header identity");
        String secret = env.getProperty("dash.auth.jwt-secret", "");
        if (production && (secret.contains("demo") || secret.contains("local")
                || ("jwt".equals(env.getProperty("dash.auth.mode", "session")) && secret.length() < 32)))
            throw new IllegalStateException("生产 JWT 密钥缺失或仍为演示密钥");
    }
}
