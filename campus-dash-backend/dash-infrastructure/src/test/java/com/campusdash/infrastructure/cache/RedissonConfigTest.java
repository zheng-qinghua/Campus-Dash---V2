package com.campusdash.infrastructure.cache;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RedissonConfigTest {
    @Test void configuresExternallyProvidedAuthenticationWithoutChangingAddress() {
        String password = UUID.randomUUID().toString();
        var config = new RedissonConfig().configuration("127.0.0.1", 6380, 16, "redis-user", password);
        var server = config.useSingleServer();
        assertEquals("redis://127.0.0.1:6380", server.getAddress());
        assertEquals("redis-user", server.getUsername());
        assertEquals(password, server.getPassword());
    }

    @Test void unauthenticatedLocalRedisDoesNotReceiveAnEmptyPassword() {
        var server = new RedissonConfig().configuration("127.0.0.1", 6380, 16, "", "").useSingleServer();
        assertNull(server.getUsername());
        assertNull(server.getPassword());
    }
}
