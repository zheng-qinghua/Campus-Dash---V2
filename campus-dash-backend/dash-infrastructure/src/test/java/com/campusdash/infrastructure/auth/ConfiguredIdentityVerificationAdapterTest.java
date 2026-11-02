package com.campusdash.infrastructure.auth;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredIdentityVerificationAdapterTest {
    @Test void identityMustMatchBothIssuerAndSubjectAndCannotChooseUserId() {
        var adapter = new ConfiguredIdentityVerificationAdapter();
        var binding = new ConfiguredIdentityVerificationAdapter.Binding();
        binding.setIssuer("https://test-issuer.example"); binding.setSubject("campus-subject"); binding.setUserId(12);
        adapter.setBindings(List.of(binding));
        assertEquals(12, adapter.verify(binding.getIssuer(), binding.getSubject()).orElseThrow());
        assertTrue(adapter.verify("https://evil.example", binding.getSubject()).isEmpty());
        assertTrue(adapter.verify(binding.getIssuer(), "12").isEmpty());
        adapter.setBindings(List.of(binding, binding));
        assertTrue(adapter.verify(binding.getIssuer(), binding.getSubject()).isEmpty());
    }
}
