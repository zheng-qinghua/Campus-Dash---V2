package com.campusdash.infrastructure.auth;

import com.campusdash.domain.auth.ports.IdentityVerificationPort;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 服务端配置维护 (issuer, subject) 映射；生产可替换为账号库适配器。 */
@Component
@ConfigurationProperties(prefix = "dash.auth.oidc")
public class ConfiguredIdentityVerificationAdapter implements IdentityVerificationPort {
    private List<Binding> bindings = new ArrayList<>();
    public static class Binding {
        private String issuer;
        private String subject;
        private long userId;
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public long getUserId() { return userId; }
        public void setUserId(long userId) { this.userId = userId; }
    }
    public List<Binding> getBindings() { return bindings; }
    public void setBindings(List<Binding> bindings) { this.bindings = List.copyOf(bindings); }
    @Override public Optional<Long> verify(String issuer, String subject) {
        if (issuer == null || subject == null || issuer.isBlank() || subject.isBlank()) return Optional.empty();
        List<Binding> matches = bindings.stream().filter(b -> issuer.equals(b.issuer) && subject.equals(b.subject)).toList();
        // 配置重复同样拒绝，不能依赖数组顺序选择身份。
        return matches.size() == 1 && matches.get(0).userId > 0 ? Optional.of(matches.get(0).userId) : Optional.empty();
    }
}
