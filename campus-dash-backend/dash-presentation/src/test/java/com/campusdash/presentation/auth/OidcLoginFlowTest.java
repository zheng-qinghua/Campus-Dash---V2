package com.campusdash.presentation.auth;

import com.campusdash.application.auth.VerifiedIdentityLoginUseCase;
import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.domain.auth.ports.IdentityVerificationPort;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 HTTP token/JWKS 端点 + 签名 ID token；不绕过 Spring OIDC 协议校验。 */
@SpringBootTest(classes = OidcLoginFlowTest.TestApp.class, properties = "dash.auth.oidc.enabled=true")
@AutoConfigureMockMvc
class OidcLoginFlowTest {
    static final RSAKey key;
    static final HttpServer issuerServer;
    static final String issuer;
    static final AtomicInteger issued = new AtomicInteger();
    static volatile String nonce, challenge, tokenAudience = "campus-client", tokenSubject = "mapped", tokenNonce;
    static volatile boolean pkceVerified;
    static {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            issuerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();
            issuerServer.createContext("/jwks", exchange -> {
                byte[] data = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
            });
            issuerServer.createContext("/token", exchange -> {
                try {
                    Map<String, String> form = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    String verifier = form.get("code_verifier");
                    String computed = verifier == null ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                    pkceVerified = computed.equals(challenge);
                    if (!pkceVerified) { exchange.sendResponseHeaders(400, -1); exchange.close(); return; }
                    Instant now = Instant.now();
                    var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(tokenSubject).audience(tokenAudience)
                            .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)))
                            .claim("nonce", tokenNonce == null ? nonce : tokenNonce).build();
                    var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
                    token.sign(new RSASSASigner(key));
                    String body = "{\"access_token\":\"provider-token\",\"token_type\":\"Bearer\",\"expires_in\":60,\"id_token\":\"" + token.serialize() + "\"}";
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
                } catch (Exception failure) { exchange.close(); throw new RuntimeException(failure); }
            });
            issuerServer.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @Autowired MockMvc mvc;
    @BeforeEach void reset() { issued.set(0); tokenAudience = "campus-client"; tokenSubject = "mapped"; tokenNonce = null; pkceVerified = false; }
    @AfterAll static void stop() { issuerServer.stop(0); }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(IdentitySecurityConfiguration.class)
    static class TestApp {
        @Bean ClientRegistrationRepository registrations() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("campus")
                    .clientId("campus-client").clientSecret(java.util.UUID.randomUUID().toString())
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).scope("openid")
                    .redirectUri("http://localhost/login/oauth2/code/campus").issuerUri(issuer)
                    .authorizationUri(issuer + "/authorize").tokenUri(issuer + "/token").jwkSetUri(issuer + "/jwks")
                    .userNameAttributeName("sub").build());
        }
        @Bean VerifiedIdentityLoginUseCase login() {
            IdentityVerificationPort identities = (source, subject) -> issuer.equals(source) && "mapped".equals(subject)
                    ? Optional.of(12L) : Optional.empty();
            AuthPort auth = new AuthPort() {
                @Override public String login(long userId) { issued.incrementAndGet(); return "local-session"; }
                @Override public Optional<Long> resolve(String token) { return Optional.empty(); }
                @Override public void logout(String token) { }
            };
            return new VerifiedIdentityLoginUseCase(identities, auth);
        }
    }
    record Authorization(MockHttpSession session, String state) {}
    Authorization authorize() throws Exception {
        var result = mvc.perform(get("/oauth2/authorization/campus")).andExpect(status().is3xxRedirection()).andReturn();
        var parameters = parameters(URI.create(result.getResponse().getRedirectedUrl()).getRawQuery());
        assertEquals("S256", parameters.get("code_challenge_method"));
        nonce = parameters.get("nonce"); challenge = parameters.get("code_challenge");
        assertNotNull(nonce); assertNotNull(challenge);
        return new Authorization((MockHttpSession) result.getRequest().getSession(false), parameters.get("state"));
    }
    @Test void authorizationCodePkceAndMappedIdentityIssueLocalSession() throws Exception {
        var authorization = authorize();
        var response = mvc.perform(get("/login/oauth2/code/campus").session(authorization.session())
                .param("state", authorization.state()).param("code", "test-code")).andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(pkceVerified); assertEquals(1, issued.get());
        assertTrue(response.getContentAsString().contains("local-session"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
    @Test void stateTamperingDoesNotIssueLocalSession() throws Exception {
        var authorization = authorize();
        mvc.perform(get("/login/oauth2/code/campus").session(authorization.session()).param("state", "wrong")
                .param("code", "test-code")).andExpect(status().isUnauthorized());
        assertEquals(0, issued.get());
    }
    @Test void audienceAndNonceAreValidatedBeforeLocalSessionIssuance() throws Exception {
        for (String field : List.of("audience", "nonce")) {
            var authorization = authorize();
            tokenAudience = field.equals("audience") ? "other-client" : "campus-client";
            tokenNonce = field.equals("nonce") ? "wrong-nonce" : null;
            mvc.perform(get("/login/oauth2/code/campus").session(authorization.session()).param("state", authorization.state())
                    .param("code", "test-code")).andExpect(status().isUnauthorized());
            assertEquals(0, issued.get());
        }
    }
    @Test void unboundSubjectCannotSelectLocalUserId() throws Exception {
        var authorization = authorize(); tokenSubject = "12";
        mvc.perform(get("/login/oauth2/code/campus").session(authorization.session()).param("state", authorization.state())
                .param("code", "test-code")).andExpect(status().isForbidden());
        assertEquals(0, issued.get());
    }
    static Map<String, String> parameters(String encoded) {
        Map<String, String> result = new HashMap<>();
        for (String entry : encoded.split("&")) {
            String[] pair = entry.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length < 2 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
