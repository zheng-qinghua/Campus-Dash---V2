package com.campusdash.it;

import com.campusdash.application.usecase.GrabProjectionSync;
import com.campusdash.application.usecase.GrabRequestStep;
import com.campusdash.application.usecase.TimeoutTransferStep;
import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.model.GrabRequest;
import com.campusdash.domain.grab.ports.GrabRequestRepository;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 实际 MVC、认证、数据库与 Redis 的协议验收；不使用 mocked 业务 Bean。 */
@SpringBootTest(properties = {
        "dash.mq.enabled=false", "dash.auth.allow-header-identity=false",
        "dash.auth.mode=session", "dash.auth.session-namespace=grab-http-it",
        "dash.credit.max-ongoing=10000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GrabHttpContractIT {
    private static final long RUNNER = 880001L;
    private static final long OTHER_RUNNER = 880002L;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthPort auth;
    @Autowired ErrandRepository errands;
    @Autowired ErrandCachePort cache;
    @Autowired GrabProjectionSync sync;
    @Autowired GrabRequestStep requestStep;
    @Autowired TimeoutTransferStep timeoutStep;
    @Autowired GrabRequestRepository requests;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired JdbcTemplate jdbc;
    private static BenchRun run;
    @RegisterExtension static final BenchTestOutcome outcome = new BenchTestOutcome();
    private final List<String> issuedTokens = new ArrayList<>();

    @BeforeAll static void requireMiddleware() {
        assertTrue(MiddlewareAvailable.check(), "HTTP 协议验收必须运行真实 MySQL/Redis");
        run = BenchRun.start("IT-GRAB-HTTP");
    }
    @AfterAll static void finishRun() {
        if (run != null) { run.finish(outcome.status(), outcome.summary("IT-GRAB-HTTP")); run.close(); }
    }
    @AfterEach void closeSessions() { issuedTokens.forEach(auth::logout); }

    private long task() {
        long id = ids.nextId();
        errands.insert(Errand.draft(id, 1, 1001, ErrandType.DELIVERY, "bench_HTTP抢单契约", Money.ofCents(100), 1));
        assertEquals(1, errands.casPublish(id, 0));
        run.track(id); sync.sync(id); cache.registerExisting(id);
        return id;
    }
    private String token(long runnerId) {
        String token = auth.login(runnerId);
        issuedTokens.add(token);
        return token;
    }
    private MockHttpServletRequestBuilder grab(long id, String requestId, String token, int round) {
        return post("/api/errands/{id}/grab", id).header("Authorization", "Bearer " + token)
                .header("X-Request-Id", requestId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRound\":" + round + "}");
    }
    private MvcResult call(MockHttpServletRequestBuilder request, int status) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return result;
    }
    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }
    private void assertWireShape(JsonNode data, String requestId, String state) {
        assertTrue(data.get("code").isTextual());
        assertTrue(data.get("grabbed").isBoolean());
        assertEquals(requestId, data.get("requestId").asText());
        assertEquals(state, data.get("status").asText());
        assertTrue(data.get("appliedRound").isIntegralNumber(), "轮次是 int，前端按 number 接收");
        assertTrue(data.get("replayed").isBoolean());
        assertTrue(data.has("candidateRank"));
        assertTrue(data.get("candidateRank").isNull(), "本协议不承诺固定候选排名");
        // Jackson 把 long 输出为字符串；前端网络层必须 Number() 归一化退避时间。
        assertTrue(data.get("retryAfterMillis").isTextual());
        assertTrue(Long.parseLong(data.get("retryAfterMillis").asText()) >= 0);
        assertTrue(data.get("httpStatus").isIntegralNumber());
    }

    @Test void missing_or_invalid_request_fields_return_400_without_registering() throws Exception {
        long id = task();
        String token = token(RUNNER), requestId = UUID.randomUUID().toString();
        var missingId = call(post("/api/errands/{id}/grab", id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRound\":0}"), 400);
        assertEquals("INVALID_REQUEST", json(missingId).get("code").asText());
        call(post("/api/errands/{id}/grab", id).header("Authorization", "Bearer " + token)
                .header("X-Request-Id", requestId), 400);
        call(post("/api/errands/{id}/grab", id).header("Authorization", "Bearer " + token)
                .header("X-Request-Id", requestId).contentType(MediaType.APPLICATION_JSON).content("{}"), 400);
        call(grab(id, "not-a-uuid", token, 0), 400);
        call(grab(id, requestId, token, -1), 400);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM grab_request WHERE errand_id=?", Integer.class, id));
    }

    @Test void success_and_replay_match_frontend_fields_and_latest_actions() throws Exception {
        long id = task();
        String token = token(RUNNER), requestId = UUID.randomUUID().toString();
        var first = json(call(grab(id, requestId, token, 0), 200));
        assertEquals("OK", first.get("code").asText());
        assertWireShape(first.get("data"), requestId, "SUCCEEDED");
        assertTrue(first.get("data").get("grabbed").asBoolean());
        assertFalse(first.get("data").get("replayed").asBoolean());
        var replay = json(call(grab(id, requestId, token, 0), 200)).get("data");
        assertWireShape(replay, requestId, "SUCCEEDED");
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM grab_record WHERE errand_id=?", Integer.class, id));
        var detail = json(call(get("/api/errands/{id}", id).header("Authorization", "Bearer " + token), 200)).get("data");
        assertEquals(Long.toString(RUNNER), detail.get("grabberId").asText());
        assertEquals(0, detail.get("round").asInt());
        assertTrue(detail.get("availableActions").toString().contains("CONFIRM"));
    }

    @Test void registered_processing_returns_202_and_retry_after_without_business_effect() throws Exception {
        long id = task();
        String token = token(RUNNER), requestId = UUID.randomUUID().toString();
        var errand = errands.findById(id).orElseThrow();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("GRAB:" + id + ":0").getBytes(StandardCharsets.UTF_8)));
        assertTrue(requestStep.register(new GrabRequest.NewRequest(ids.nextId(), 1, id, RUNNER,
                requestId, hash, 0, errand.version(), UUID.randomUUID().toString(), 30)));
        var originalDeadline = requests.find(1, RUNNER, requestId).orElseThrow().leaseUntil();
        for (var request : List.of(grab(id, requestId, token, 0),
                get("/api/errands/{id}/grab-requests/{requestId}", id, requestId)
                        .header("Authorization", "Bearer " + token))) {
            var response = call(request, 202);
            assertEquals("1", response.getResponse().getHeader("Retry-After"));
            var data = json(response).get("data");
            assertWireShape(data, requestId, "PROCESSING");
            assertFalse(data.get("grabbed").asBoolean());
        }
        assertEquals(originalDeadline, requests.find(1, RUNNER, requestId).orElseThrow().leaseUntil());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM grab_record WHERE errand_id=?", Integer.class, id));
    }

    @Test void conflicting_fingerprint_or_stale_round_returns_409() throws Exception {
        long id = task();
        String token = token(RUNNER), requestId = UUID.randomUUID().toString();
        call(grab(id, requestId, token, 0), 200);
        var conflict = json(call(grab(id, requestId, token, 1), 409));
        assertEquals("REQUEST_CONFLICT", conflict.get("code").asText());
        assertTrue(conflict.get("data").get("status").isNull(), "准入冲突不能伪造原请求的终态");
        long another = task();
        assertEquals("REQUEST_CONFLICT", json(call(grab(another, requestId, token, 0), 409)).get("code").asText());
        var stale = json(call(grab(another, UUID.randomUUID().toString(), token, 1), 409));
        assertEquals("STALE_VERSION", stale.get("code").asText());
        assertEquals("REJECTED", stale.get("data").get("status").asText());
    }

    @Test void authenticated_runner_is_authoritative_for_queries_and_submissions() throws Exception {
        long id = task();
        String ownerToken = token(RUNNER), otherToken = token(OTHER_RUNNER);
        String requestId = UUID.randomUUID().toString();
        call(grab(id, requestId, ownerToken, 0), 200);
        var otherQuery = call(get("/api/errands/{id}/grab-requests/{requestId}", id, requestId)
                .header("Authorization", "Bearer " + otherToken).header("X-User-Id", RUNNER)
                .param("runnerId", Long.toString(RUNNER)), 404);
        assertEquals("REQUEST_NOT_FOUND", json(otherQuery).get("code").asText());
        call(get("/api/errands/{id}/grab-requests/{requestId}", id, requestId).header("X-User-Id", RUNNER), 401);
        var ownerQuery = json(call(get("/api/errands/{id}/grab-requests/{requestId}", id, requestId)
                .header("Authorization", "Bearer " + ownerToken).header("X-User-Id", OTHER_RUNNER), 200));
        assertEquals("SUCCEEDED", ownerQuery.get("data").get("status").asText());
        String otherRequestId = UUID.randomUUID().toString();
        var spoofed = json(call(grab(id, otherRequestId, otherToken, 0).header("X-User-Id", RUNNER)
                .content("{\"expectedRound\":0,\"runnerId\":" + RUNNER + "}"), 409));
        assertEquals("SLOT_FULL", spoofed.get("code").asText());
        assertTrue(requests.find(1, OTHER_RUNNER, otherRequestId).isPresent());
        assertTrue(requests.find(1, RUNNER, otherRequestId).isEmpty());
    }

    @Test void confirmation_requires_the_round_observed_by_the_client() throws Exception {
        long id = task();
        String session = token(RUNNER);
        call(grab(id, UUID.randomUUID().toString(), session, 0), 200);
        for (String body : new String[] { null, "{}", "{\"expectedRound\":-1}" }) {
            var request = post("/api/errands/{id}/confirm", id).header("Authorization", "Bearer " + session);
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
            assertEquals("INVALID_REQUEST", json(call(request, 400)).get("code").asText());
        }
        assertEquals("LOCKED", errands.findById(id).orElseThrow().status().name());
    }

    @Test void delayed_confirmation_from_a_cannot_confirm_a_new_a_b_a_round() throws Exception {
        long id = task();
        String session = token(RUNNER);
        call(grab(id, UUID.randomUUID().toString(), session, 0), 200);
        for (long next : new long[] { OTHER_RUNNER, RUNNER }) {
            jdbc.update("UPDATE errand SET confirm_deadline=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?", id);
            assertTrue(timeoutStep.transfer(errands.findById(id).orElseThrow(), next, 300).applied());
        }
        assertEquals(2, errands.findById(id).orElseThrow().round());
        var stale = post("/api/errands/{id}/confirm", id).header("Authorization", "Bearer " + session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRound\":0}");
        assertEquals("STALE_VERSION", json(call(stale, 200)).get("code").asText());
        assertEquals("LOCKED", errands.findById(id).orElseThrow().status().name());
        var current = post("/api/errands/{id}/confirm", id).header("Authorization", "Bearer " + session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRound\":2}");
        assertEquals("OK", json(call(current, 200)).get("code").asText());
        assertEquals("OK", json(call(current, 200)).get("code").asText());
        assertEquals("STALE_VERSION", json(call(stale, 200)).get("code").asText());
        assertEquals("ACCEPTED", errands.findById(id).orElseThrow().status().name());
    }
}
