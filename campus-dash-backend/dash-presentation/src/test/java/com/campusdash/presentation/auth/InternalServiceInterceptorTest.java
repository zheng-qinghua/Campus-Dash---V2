package com.campusdash.presentation.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class InternalServiceInterceptorTest {
    @Test void ordinaryUserAndEmptyConfigurationCannotReadInternalEndpoints() {
        var request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer user-session");
        var response = new MockHttpServletResponse();
        assertFalse(new InternalServiceInterceptor(java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID()).preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
        assertFalse(new InternalServiceInterceptor(java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID()).preHandle(request, response, new Object()));
    }
    @Test void onlyConfiguredServiceCredentialPasses() {
        String secret = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
        var request = new MockHttpServletRequest(); request.addHeader("X-Internal-Token", secret);
        assertTrue(new InternalServiceInterceptor(secret).preHandle(request, new MockHttpServletResponse(), new Object()));
        assertFalse(new InternalServiceInterceptor(secret + "x").preHandle(request, new MockHttpServletResponse(), new Object()));
    }
}
