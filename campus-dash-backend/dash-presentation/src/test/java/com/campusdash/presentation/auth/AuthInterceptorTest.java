package com.campusdash.presentation.auth;

import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.presentation.GlobalExceptionHandler;
import com.campusdash.shared.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthInterceptorTest {
    private final AuthPort auth = mock(AuthPort.class);
    private final ProtectedController controller = new ProtectedController();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .addInterceptors(new AuthInterceptor(auth, false))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    static Stream<RuntimeException> unavailableDependencies() {
        return Stream.of(new DataAccessResourceFailureException("连接不可用"),
                new QueryTimeoutException("查询超时"));
    }

    @ParameterizedTest
    @MethodSource("unavailableDependencies")
    @DisplayName("鉴权连接失败或超时阻断业务并返回503，恢复后同一令牌可正常请求")
    void dependency_failure_does_not_authenticate_and_can_recover(RuntimeException failure) throws Exception {
        when(auth.resolve("valid-token")).thenThrow(failure).thenReturn(Optional.of(7001L));

        var rejected = mvc.perform(get("/api/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                        .header("X-User-Id", "9999"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andReturn();
        assertEquals(0, controller.calls.get());
        assertNull(rejected.getRequest().getAttribute(CurrentUser.ATTR));

        mvc.perform(get("/api/protected").header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.data").value(7001));
        assertEquals(1, controller.calls.get());
    }

    @Test
    @DisplayName("无效或过期令牌仍返回401，不伪装成依赖故障")
    void expired_or_invalid_token_stays_unauthorized() throws Exception {
        when(auth.resolve("expired-token")).thenReturn(Optional.empty());
        var rejected = mvc.perform(get("/api/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andReturn();
        assertEquals(0, controller.calls.get());
        assertNull(rejected.getRequest().getAttribute(CurrentUser.ATTR));
    }

    @Test
    @DisplayName("缺少令牌不调用鉴权存储，也不接受关闭的身份请求头")
    void missing_token_never_reads_dependency_or_accepts_header() throws Exception {
        mvc.perform(get("/api/protected").header("X-User-Id", "7001"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
        verifyNoInteractions(auth);
        assertEquals(0, controller.calls.get());
    }

    @Test
    @DisplayName("非法身份仍被拒绝，不能由合法响应中的零身份放行")
    void non_positive_identity_is_not_authenticated() throws Exception {
        when(auth.resolve("invalid-identity")).thenReturn(Optional.of(0L));
        var rejected = mvc.perform(get("/api/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-identity"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertNull(rejected.getRequest().getAttribute(CurrentUser.ATTR));
        assertEquals(0, controller.calls.get());
    }

    @Test
    @DisplayName("解析和编程异常保留500，不被掩盖为可重试的依赖异常")
    void unexpected_failure_is_not_reclassified_as_dependency_unavailable() throws Exception {
        when(auth.resolve("bad-record")).thenThrow(new NumberFormatException("存储身份格式错误"));
        mvc.perform(get("/api/protected").header(HttpHeaders.AUTHORIZATION, "Bearer bad-record"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertEquals(0, controller.calls.get());
    }

    @RestController
    static class ProtectedController {
        private final AtomicInteger calls = new AtomicInteger();

        @GetMapping("/api/protected")
        Result<Object> getProtected(HttpServletRequest request) {
            calls.incrementAndGet();
            return Result.ok(request.getAttribute(CurrentUser.ATTR));
        }
    }
}
