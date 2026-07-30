package com.company.erp.identity.api;

import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.company.erp.identity.application.PasswordService;
import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.identity.security.JwtTokenService;
import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
class AdminMonitoringControllerIT {

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

    private UUID actorId;
    private UUID actorSessionId;

    @BeforeEach
    void createActor() {
        actorId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status,
                            must_change_password, created_by, updated_by
                        ) VALUES (
                            :id, 'USER', :loginId, :passwordHash, 'QA', 'Monitoring Admin', 'ACTIVE',
                            false, :systemId, :systemId
                        )
                        """)
                .param("id", actorId)
                .param("loginId", "monitoring-admin-" + actorId)
                .param("passwordHash", passwordService.encode("a monitoring admin password"))
                .param("systemId", SYSTEM_USER_ID)
                .update();
        jdbc.sql("INSERT INTO identity.user_role (user_id, role_id, assigned_by) VALUES (:userId, :roleId, :systemId)")
                .param("userId", actorId)
                .param("roleId", ADMIN_ROLE_ID)
                .param("systemId", SYSTEM_USER_ID)
                .update();
        actorSessionId = UUID.randomUUID();
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO identity.auth_session (
                            id, user_id, absolute_expires_at, idle_expires_at,
                            created_at, last_refreshed_at, client_ip, user_agent
                        ) VALUES (
                            :id, :userId, :absoluteExpiresAt, :idleExpiresAt,
                            :now, :now, CAST('198.51.100.40' AS inet), 'admin-monitoring-test'
                        )
                        """)
                .param("id", actorSessionId)
                .param("userId", actorId)
                .param("absoluteExpiresAt", now.plus(8, ChronoUnit.HOURS))
                .param("idleExpiresAt", now.plus(1, ChronoUnit.HOURS))
                .param("now", now)
                .update();
    }

    @Test
    void loginEventsArePagedFilteredSortedAndRedacted() throws Exception {
        UUID successId = UUID.randomUUID();
        UUID failureId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.login_event (
                            id, user_id, login_id_attempted, outcome, client_ip,
                            ip_name_snapshot, user_agent, occurred_at
                        ) VALUES (
                            :successId, :userId, :loginId, 'SUCCESS', CAST('203.0.113.1' AS inet),
                            'Office', 'safe-agent', clock_timestamp() - interval '1 minute'
                        ), (
                            :failureId, NULL, :failedLoginId, 'INVALID_CREDENTIALS', CAST('203.0.113.2' AS inet),
                            NULL, 'safe-agent', clock_timestamp()
                        )
                        """)
                .param("successId", successId)
                .param("failureId", failureId)
                .param("userId", actorId)
                .param("loginId", "monitoring-admin-" + actorId)
                .param("failedLoginId", "unknown-" + failureId)
                .update();

        mockMvc.perform(get("/api/v1/admin/login-events")
                        .with(accessJwt())
                        .param("outcome", "SUCCESS")
                        .param("sort", "occurredAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(successId.toString()))
                .andExpect(jsonPath("$.items[0].userName").value("Monitoring Admin"))
                .andExpect(jsonPath("$.items[0].clientIp").value("203.0.113.1"))
                .andExpect(jsonPath("$.items[0].ipName").value("Office"))
                .andExpect(jsonPath("$.filters.outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.sort[0]").value("occurredAt,desc"))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("token"))));

        mockMvc.perform(get("/api/v1/admin/login-events")
                        .with(accessJwt())
                        .param("sort", "clientIp,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void allowlistCrudCanonicalizesNetworksReportsNotEnforcedAndUsesOptimisticVersion() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/ip-allowlist")
                        .with(accessJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"network":"192.0.2.42/24","name":"Office network"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.network").value("192.0.2.0/24"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        UUID entryId = UUID.fromString(json(created).get("id").asText());

        mockMvc.perform(get("/api/v1/admin/ip-allowlist")
                        .with(accessJwt())
                        .param("active", "true")
                        .param("sort", "network,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforced").value(false))
                .andExpect(jsonPath("$.filters.active").value(true))
                .andExpect(content().string(not(containsString("enabled"))));

        mockMvc.perform(post("/api/v1/admin/ip-allowlist")
                        .with(accessJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"network":"192.0.2.10/24","name":"Duplicate network"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_KEY"));

        mockMvc.perform(post("/api/v1/admin/ip-allowlist")
                        .with(accessJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"network":"not-an-ip-network","name":"Invalid network"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/admin/ip-allowlist")
                        .with(accessJwt())
                        .param("page", Integer.toString(Integer.MAX_VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        mockMvc.perform(put("/api/v1/admin/ip-allowlist/{entryId}", entryId)
                        .with(accessJwt())
                        .header(HttpHeaders.IF_MATCH, "\"9\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"network":"192.0.2.0/24","name":"Updated office","active":false}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mockMvc.perform(delete("/api/v1/admin/ip-allowlist/{entryId}", entryId)
                        .with(accessJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT count(*) FROM identity.ip_allowlist_entry WHERE id = :id")
                .param("id", entryId)
                .query(Long.class)
                .single()).isZero();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor accessJwt() {
        var user = identityRepository.findById(actorId).orElseThrow();
        String token = jwtTokenService.issueSessionAccessToken(user, actorSessionId).token();
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
