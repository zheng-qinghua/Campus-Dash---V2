package com.campusdash.presentation.auth;

import com.campusdash.application.auth.VerifiedIdentityLoginUseCase;
import com.campusdash.shared.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;

/** OIDC 由 Spring Security 校验 state/nonce、签名、issuer、audience 和有效期。 */
@Configuration
public class IdentitySecurityConfiguration {
    @Bean
    SecurityFilterChain identityChain(HttpSecurity http, ObjectProvider<ClientRegistrationRepository> registrations,
            VerifiedIdentityLoginUseCase login, ObjectMapper mapper, org.springframework.core.env.Environment env,
            @Value("${dash.auth.oidc.enabled:false}") boolean enabled) throws Exception {
        boolean production = java.util.Arrays.stream(env.getActiveProfiles())
                .anyMatch(profile -> profile.equals("prod") || profile.equals("production"))
                || java.util.Set.of("prod", "production").contains(env.getProperty("dash.auth.environment", ""));
        if (production && !enabled) throw new IllegalStateException("生产 API 必须配置并启用正式 OIDC 身份源");
        // 业务 API 继续由 Bearer 拦截器鉴权；OIDC 的临时 HttpSession 仅保存授权请求及 state。
        // 不把 OIDC session 当作业务登录会话，避免 cookie 绕过既有身份上下文。
        http.authorizeHttpRequests(rules -> rules.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable());
        if (enabled) {
            ClientRegistrationRepository repository = registrations.getIfAvailable();
            if (repository == null) throw new IllegalStateException("OIDC 已开启但缺少 client/provider 配置");
            var resolver = new DefaultOAuth2AuthorizationRequestResolver(repository, "/oauth2/authorization");
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            http.oauth2Login(oauth -> oauth.authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                    .successHandler((request, response, authentication) -> {
                        response.setContentType("application/json;charset=UTF-8");
                        response.setHeader("Cache-Control", "no-store");
                        if (request.getSession(false) != null) request.getSession(false).invalidate();
                        if (!(authentication.getPrincipal() instanceof OidcUser user)) {
                            response.setStatus(401); return;
                        }
                        var session = login.login(user.getIssuer().toString(), user.getSubject());
                        if (session.isEmpty()) { response.setStatus(403); return; }
                        mapper.writeValue(response.getWriter(), Result.ok(session.get()));
                    })
                    .failureHandler((request, response, exception) -> {
                        if (request.getSession(false) != null) request.getSession(false).invalidate();
                        response.setStatus(401); response.setHeader("Cache-Control", "no-store");
                    }));
        }
        return http.build();
    }
}
