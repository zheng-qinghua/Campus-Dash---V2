package com.campusdash.infrastructure.auth;

import com.campusdash.shared.EnvironmentSecrets;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentSecretsTest {
    @Test void missingEnvironmentVariableFailsWithoutExposingCredentials() {
        String name = "DASH_TEST_MISSING_" + UUID.randomUUID().toString().replace("-", "");
        var error = assertThrows(IllegalStateException.class, () -> EnvironmentSecrets.required(name));
        assertEquals("必须设置环境变量 " + name, error.getMessage());
    }
}
