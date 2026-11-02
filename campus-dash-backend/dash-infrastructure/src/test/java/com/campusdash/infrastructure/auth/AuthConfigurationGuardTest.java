package com.campusdash.infrastructure.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class AuthConfigurationGuardTest {
    @Test void demoRequiresExplicitDevelopmentProfile() {
        var env = new MockEnvironment().withProperty("dash.auth.demo-login-enabled", "true");
        assertThrows(IllegalStateException.class, () -> new AuthConfigurationGuard(env));
        env.setActiveProfiles("local");
        assertDoesNotThrow(() -> new AuthConfigurationGuard(env));
    }
    @Test void productionRejectsDemoAndHeader() {
        var env = new MockEnvironment(); env.setActiveProfiles("prod", "local");
        assertDoesNotThrow(() -> new AuthConfigurationGuard(env));
        env.withProperty("dash.auth.oidc.enabled", "true").withProperty("dash.auth.demo-login-enabled", "true");
        assertThrows(IllegalStateException.class, () -> new AuthConfigurationGuard(env));
        env.withProperty("dash.auth.demo-login-enabled", "false").withProperty("dash.auth.allow-header-identity", "true");
        assertThrows(IllegalStateException.class, () -> new AuthConfigurationGuard(env));
    }
    @Test void productionRejectsMissingSecret() {
        var env = new MockEnvironment().withProperty("dash.auth.oidc.enabled", "true")
                .withProperty("dash.auth.mode", "jwt").withProperty("dash.auth.jwt-secret", "");
        env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new AuthConfigurationGuard(env));
        env.withProperty("dash.auth.jwt-secret", java.util.UUID.randomUUID().toString());
        assertDoesNotThrow(() -> new AuthConfigurationGuard(env));
    }
}
