package com.campusdash.infrastructure.auth;

import com.campusdash.domain.auth.ports.AuthPort;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisLoadingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.lettuce.LettuceExceptionConverter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthRedisDependencyTest {
    private static final String SECRET = java.util.Base64.getEncoder().encodeToString(io.jsonwebtoken.Jwts.SIG.HS256.key().build().getEncoded());
    enum Mode { SESSION, JWT }

    static Stream<Arguments> recoverableFailures() {
        return Stream.of(Mode.values()).flatMap(mode -> Stream.of(
                Arguments.of(mode, new RedisLoadingException("LOADING Redis is loading the dataset in memory")),
                Arguments.of(mode, new RedisBusyException("BUSY Redis is busy running a script"))));
    }

    @ParameterizedTest
    @MethodSource("recoverableFailures")
    @DisplayName("Session与JWT遇到LOADING/BUSY不能放行，故障恢复后同一令牌可继续鉴权")
    void recoverable_redis_error_is_normalized_and_same_token_recovers(Mode mode, Exception nativeFailure) {
        Fixture fixture = new Fixture(mode);
        var translated = new LettuceExceptionConverter().convert(nativeFailure);
        assertInstanceOf(RedisSystemException.class, translated);
        fixture.failThenRecover(translated);

        var failure = assertThrows(DataAccessResourceFailureException.class,
                () -> fixture.auth.resolve(fixture.token));
        assertSame(translated, failure.getCause());
        assertEquals(Optional.of(7001L), fixture.auth.resolve(fixture.token));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    @DisplayName("普通Redis执行错误保留原异常，不被改成可重试的503")
    void wrongtype_is_not_misclassified(Mode mode) {
        Fixture fixture = new Fixture(mode);
        var wrongtype = new LettuceExceptionConverter().convert(
                new RedisCommandExecutionException("WRONGTYPE Operation against a key holding the wrong kind of value"));
        fixture.failThenRecover(wrongtype);
        assertSame(wrongtype, assertThrows(RedisSystemException.class,
                () -> fixture.auth.resolve(fixture.token)));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    @DisplayName("鉴权读取中的编程异常保持可见，不降级成依赖故障或无效令牌")
    void programming_error_is_not_swallowed(Mode mode) {
        Fixture fixture = new Fixture(mode);
        var unexpected = new IllegalStateException("程序状态错误");
        fixture.failThenRecover(unexpected);
        assertSame(unexpected, assertThrows(IllegalStateException.class,
                () -> fixture.auth.resolve(fixture.token)));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    @DisplayName("空令牌不访问Redis，仍按未认证处理")
    void blank_token_does_not_consult_redis(Mode mode) {
        Fixture fixture = new Fixture(mode);
        assertTrue(fixture.auth.resolve(" ").isEmpty());
        assertTrue(fixture.auth.resolve(null).isEmpty());
        verifyNoInteractions(fixture.redis);
    }

    @Test
    @DisplayName("过期或格式错误的JWT继续被拒绝，不依赖Redis是否可用")
    void expired_or_malformed_jwt_does_not_consult_redis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var expired = new JwtAuthAdapter(redis, SECRET, -1, 1, false, "auth-dependency-test");
        var current = new JwtAuthAdapter(redis, SECRET, 1, 1, false, "auth-dependency-test");
        assertTrue(current.resolve(expired.login(7001)).isEmpty());
        assertTrue(current.resolve("not-a-jwt").isEmpty());
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("Redis中不存在的Session仍返回未认证")
    void missing_session_remains_unauthorized() {
        Fixture fixture = new Fixture(Mode.SESSION);
        when(fixture.redis.opsForValue()).thenReturn(fixture.values);
        when(fixture.values.get(anyString())).thenReturn(null);
        assertTrue(fixture.auth.resolve(fixture.token).isEmpty());
    }

    private static final class Fixture {
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        final ValueOperations<String, String> values = mock(ValueOperations.class);
        final Mode mode;
        final AuthPort auth;
        final String token;

        Fixture(Mode mode) {
            this.mode = mode;
            if (mode == Mode.SESSION) {
                auth = new RedisAuthAdapter(redis, 1, false, "auth-dependency-test");
                token = "valid-session";
            } else {
                auth = new JwtAuthAdapter(redis, SECRET, 1, 1, false, "auth-dependency-test");
                token = auth.login(7001);
            }
        }

        void failThenRecover(RuntimeException failure) {
            if (mode == Mode.SESSION) {
                when(redis.opsForValue()).thenReturn(values);
                when(values.get(anyString())).thenThrow(failure).thenReturn("7001");
            } else {
                when(redis.hasKey(anyString())).thenThrow(failure).thenReturn(false);
            }
        }
    }
}
