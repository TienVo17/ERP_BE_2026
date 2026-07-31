package com.company.erp.security;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.company.erp.identity.application.PasswordService;
import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.identity.security.JwtTokenService;
import com.company.erp.support.PostgresTestConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Concurrency and revocation regressions for the Phase 1 security model.
 *
 * <p>The sequential paths are covered by the auth and admin controller tests. What no other class
 * can cover is what happens when two requests arrive at once: a single rotation must survive a
 * refresh race, the recovery quorum must hold when two administrators are disabled simultaneously,
 * and two writers holding the same version must not both win. Timing is forced with a barrier, not
 * a sleep, so the race is deterministic rather than probable.
 *
 * <p>This class commits, unlike the transactional controller tests, because a race that rolls back
 * proves nothing. Everything it creates is neutralised afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
class PhaseOneSecurityRegressionIT {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdentityJdbcRepository identityRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdUoms = new ArrayList<>();

    @AfterEach
    void neutraliseCommittedFixtures() {
        // Audit rows reference their actor, so the users stay and are stripped of authority instead.
        createdUsers.forEach(id -> {
            jdbc.sql("DELETE FROM identity.user_role WHERE user_id = :id").param("id", id).update();
            jdbc.sql("UPDATE identity.app_user SET status = 'DISABLED' WHERE id = :id").param("id", id).update();
        });
        createdUoms.forEach(id ->
                jdbc.sql("DELETE FROM master_data.uom WHERE id = :id").param("id", id).update());
        createdUsers.clear();
        createdUoms.clear();
    }

    @Test
    void concurrentRefreshRotatesOnceAndThenForcesReauthentication() throws Exception {
        UUID userId = createUser("refresh-race", false);
        String password = "valid authentication password";
        setPassword(userId, password);
        MvcResult login = login(userId, password);
        Cookie refreshCookie = login.getResponse().getCookie("ERP_REFRESH");

        List<MockHttpServletResponse> responses = race(
                () -> mockMvc.perform(post("/api/v1/auth/refresh").with(csrf()).cookie(refreshCookie))
                        .andReturn().getResponse(),
                () -> mockMvc.perform(post("/api/v1/auth/refresh").with(csrf()).cookie(refreshCookie))
                        .andReturn().getResponse());

        List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();
        assertThat(statuses).containsExactlyInAnyOrder(200, 401);

        // The loser replayed a token that had already rotated, which is terminal for the family:
        // even the winner's fresh cookie must now send the operator back to the login screen.
        MockHttpServletResponse winner = responses.stream()
                .filter(response -> response.getStatus() == 200)
                .findFirst()
                .orElseThrow();
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(csrf())
                        .cookie(winner.getCookie("ERP_REFRESH")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disablingAUserImmediatelyStopsItsLiveAccessToken() throws Exception {
        UUID actorId = createRecoveryAdmin("disable-actor");
        createRecoveryAdmin("disable-quorum");
        UUID victimId = createUser("disable-victim", false);
        UUID victimSession = createSession(victimId);
        String victimToken = accessToken(victimId, victimSession);

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + victimToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/users/{id}/status", victimId)
                        .with(jwtOf(actorId))
                        .header(HttpHeaders.IF_MATCH, "\"" + version(victimId) + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED","reason":"left the company"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + victimToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replacingRolesImmediatelyStopsTheOldAccessToken() throws Exception {
        UUID actorId = createRecoveryAdmin("roles-actor");
        createRecoveryAdmin("roles-quorum");
        UUID subjectId = createUser("roles-subject", false);
        UUID subjectSession = createSession(subjectId);
        String subjectToken = accessToken(subjectId, subjectSession);

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + subjectToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/users/{id}/roles", subjectId)
                        .with(jwtOf(actorId))
                        .header(HttpHeaders.IF_MATCH, "\"" + version(subjectId) + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleIds":[],"reason":"authority withdrawn"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + subjectToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentStatusChangesCannotDropRecoveryAdminsBelowTwo() throws Exception {
        // Only the DISABLED SYSTEM principal is seeded and every other suite either rolls back or
        // creates role-less users, so the starting quorum is empty and this scenario is exact.
        assertThat(recoveryCapableCount()).isZero();
        UUID actorId = createRecoveryAdmin("quorum-actor");
        UUID firstVictim = createRecoveryAdmin("quorum-first");
        UUID secondVictim = createRecoveryAdmin("quorum-second");
        assertThat(recoveryCapableCount()).isEqualTo(3);

        List<MockHttpServletResponse> responses = race(
                () -> disable(actorId, firstVictim),
                () -> disable(actorId, secondVictim));

        assertThat(responses.stream().map(MockHttpServletResponse::getStatus).toList())
                .containsExactlyInAnyOrder(200, 409);
        MockHttpServletResponse refused = responses.stream()
                .filter(response -> response.getStatus() == 409)
                .findFirst()
                .orElseThrow();
        assertThat(objectMapper.readTree(refused.getContentAsString()).get("code").asText())
                .isEqualTo("RECOVERY_ADMIN_REQUIRED");
        assertThat(recoveryCapableCount()).isEqualTo(2);
    }

    @Test
    void concurrentUpdatesHoldingTheSameVersionProduceExactlyOneWinner() throws Exception {
        UUID actorId = createRecoveryAdmin("conflict-actor");
        createRecoveryAdmin("conflict-quorum");
        UUID uomId = createUom("RACE-" + UUID.randomUUID());

        List<MockHttpServletResponse> responses = race(
                () -> renameUom(actorId, uomId, "First writer"),
                () -> renameUom(actorId, uomId, "Second writer"));

        assertThat(responses.stream().map(MockHttpServletResponse::getStatus).toList())
                .containsExactlyInAnyOrder(200, 409);
        MockHttpServletResponse refused = responses.stream()
                .filter(response -> response.getStatus() == 409)
                .findFirst()
                .orElseThrow();
        assertThat(objectMapper.readTree(refused.getContentAsString()).get("code").asText())
                .isEqualTo("VERSION_CONFLICT");
        assertThat(jdbc.sql("SELECT version FROM master_data.uom WHERE id = :id")
                .param("id", uomId)
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    /** Runs both calls from a barrier so neither can win by starting first. */
    private List<MockHttpServletResponse> race(
            Callable<MockHttpServletResponse> first,
            Callable<MockHttpServletResponse> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<MockHttpServletResponse>> futures = List.of(
                    executor.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return first.call();
                    }),
                    executor.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return second.call();
                    }));
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    private MockHttpServletResponse disable(UUID actorId, UUID victimId) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/users/{id}/status", victimId)
                        .with(jwtOf(actorId))
                        .header(HttpHeaders.IF_MATCH, "\"" + version(victimId) + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED","reason":"concurrent recovery test"}
                                """))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse renameUom(UUID actorId, UUID uomId, String name) throws Exception {
        return mockMvc.perform(put("/api/v1/master-data/uoms/{id}", uomId)
                        .with(jwtOf(actorId))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("code", "RACE", "name", name))))
                .andReturn()
                .getResponse();
    }

    private MvcResult login(UUID userId, String password) throws Exception {
        String loginId = jdbc.sql("SELECT login_id FROM identity.app_user WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
        return mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("203.0.113." + (Math.abs(userId.hashCode()) % 250 + 1));
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "loginId", loginId, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private long recoveryCapableCount() {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM identity.app_user u
                        JOIN identity.user_role ur ON ur.user_id = u.id
                        WHERE ur.role_id = :adminRoleId
                          AND u.kind IN ('USER', 'STAFF')
                          AND u.status = 'ACTIVE'
                          AND u.must_change_password = false
                        """)
                .param("adminRoleId", ADMIN_ROLE_ID)
                .query(Long.class)
                .single();
    }

    private UUID createRecoveryAdmin(String prefix) {
        UUID id = createUser(prefix, false);
        jdbc.sql("INSERT INTO identity.user_role (user_id, role_id, assigned_by) VALUES (:userId, :roleId, :actorId)")
                .param("userId", id)
                .param("roleId", ADMIN_ROLE_ID)
                .param("actorId", SYSTEM_USER_ID)
                .update();
        return id;
    }

    private UUID createUser(String prefix, boolean mustChangePassword) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status,
                            must_change_password, created_by, updated_by
                        ) VALUES (
                            :id, 'USER', :loginId, :passwordHash, 'QA', 'Regression User', 'ACTIVE',
                            :mustChangePassword, :systemId, :systemId
                        )
                        """)
                .param("id", id)
                .param("loginId", prefix + "-" + id)
                .param("passwordHash", passwordService.encode("valid authentication password"))
                .param("mustChangePassword", mustChangePassword)
                .param("systemId", SYSTEM_USER_ID)
                .update();
        createdUsers.add(id);
        return id;
    }

    private void setPassword(UUID userId, String password) {
        jdbc.sql("UPDATE identity.app_user SET password_hash = :hash WHERE id = :id")
                .param("hash", passwordService.encode(password))
                .param("id", userId)
                .update();
    }

    private UUID createUom(String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO master_data.uom (id, code, name, created_by, updated_by)
                        VALUES (:id, :code, 'Race unit', :actorId, :actorId)
                        """)
                .param("id", id)
                .param("code", code.substring(0, Math.min(code.length(), 30)))
                .param("actorId", SYSTEM_USER_ID)
                .update();
        createdUoms.add(id);
        return id;
    }

    private UUID createSession(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO identity.auth_session (
                            id, user_id, absolute_expires_at, idle_expires_at,
                            created_at, last_refreshed_at, client_ip, user_agent
                        ) VALUES (
                            :id, :userId, :absoluteExpiresAt, :idleExpiresAt,
                            :now, :now, CAST('203.0.113.7' AS inet), 'security-regression-test'
                        )
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("absoluteExpiresAt", now.plus(8, ChronoUnit.HOURS))
                .param("idleExpiresAt", now.plus(1, ChronoUnit.HOURS))
                .param("now", now)
                .update();
        return sessionId;
    }

    private long version(UUID userId) {
        return jdbc.sql("SELECT version FROM identity.app_user WHERE id = :id")
                .param("id", userId)
                .query(Long.class)
                .single();
    }

    private String accessToken(UUID userId, UUID sessionId) {
        return jwtTokenService.issueSessionAccessToken(
                identityRepository.findById(userId).orElseThrow(), sessionId).token();
    }

    private RequestPostProcessor jwtOf(UUID userId) {
        String token = accessToken(userId, createSession(userId));
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }
}
