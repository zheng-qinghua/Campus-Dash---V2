package com.campusdash.application.auth;

import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.domain.auth.ports.IdentityVerificationPort;
import com.campusdash.domain.auth.ports.RefreshTokenPort;
import org.springframework.stereotype.Service;
import java.util.Optional;

/** 只能由标准 OIDC 客户端的已验证回调调用，HTTP 请求不接收任意 userId。 */
@Service
public class VerifiedIdentityLoginUseCase {
    private final IdentityVerificationPort identities;
    private final AuthPort auth;
    public VerifiedIdentityLoginUseCase(IdentityVerificationPort identities, AuthPort auth) {
        this.identities = identities; this.auth = auth;
    }
    public record Session(long userId, String token, String accessToken, String refreshToken) {}
    public Optional<Session> login(String issuer, String subject) {
        return identities.verify(issuer, subject).filter(id -> id > 0).map(id -> {
            if (auth instanceof RefreshTokenPort refresh) {
                var pair = refresh.loginWithRefresh(id);
                return new Session(id, pair.accessToken(), pair.accessToken(), pair.refreshToken());
            }
            String token = auth.login(id);
            return new Session(id, token, token, null);
        });
    }
}
