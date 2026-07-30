package com.company.erp.identity.api;

import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
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
@Transactional
class AdminRoleControllerIT {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID MANAGE_USERS_ID = UUID.fromString("30000000-0000-0000-0000-000000000043");
    private static final UUID MANAGE_ROLES_ID = UUID.fromString("30000000-0000-0000-0000-000000000044");

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
    private UUID targetUserId;

    @BeforeEach
    void createAdminAndRecoveryPrincipal() {
        actorId = createUser("role-actor", false);
        assignRole(actorId, ADMIN_ROLE_ID);
        actorSessionId = createSession(actorId);
        UUID secondAdmin = createUser("role-recovery", false);
        assignRole(secondAdmin, ADMIN_ROLE_ID);
        targetUserId = createUser("role-target", false);
    }

    @Test
    void managesRolesUsingSeededPermissionIdsAndRejectsUnknownPermission() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/roles")
                        .with(accessJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "code", "QA_AUDITOR_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "QA Auditor",
                                "description", "Read administrative audit",
                                "permissionIds", List.of(MANAGE_USERS_ID)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permissionIds", hasItem(MANAGE_USERS_ID.toString())))
                .andReturn();
        UUID roleId = UUID.fromString(json(created).get("id").asText());

        mockMvc.perform(put("/api/v1/admin/roles/{roleId}", roleId)
                        .with(accessJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", "QA Auditor Updated",
                                "active", true,
                                "permissionIds", List.of(MANAGE_USERS_ID, MANAGE_ROLES_ID),
                                "reason", "scope approved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.permissionIds", hasItem(MANAGE_ROLES_ID.toString())));

        mockMvc.perform(post("/api/v1/admin/roles")
                        .with(accessJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "code", "UNKNOWN_PERMISSION",
                                "name", "Invalid role",
                                "permissionIds", List.of(UUID.randomUUID())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void replacingAssignmentsAndOverridesIsImmediateAuditedAndDenyWins() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/{userId}/roles", targetUserId)
                        .with(accessJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "roleIds", List.of(ADMIN_ROLE_ID),
                                "reason", "temporary administrator"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleIds", hasItem(ADMIN_ROLE_ID.toString())));

        mockMvc.perform(put("/api/v1/admin/users/{userId}/permission-overrides", targetUserId)
                        .with(accessJwt())
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "overrides", List.of(java.util.Map.of(
                                        "permissionId", MANAGE_USERS_ID,
                                        "effect", "DENY",
                                        "reason", "separate duties")),
                                "reason", "restrict user administration"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionOverrides[0].effect").value("DENY"));

        UUID targetSession = createSession(targetUserId);
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(jwtFor(targetUserId, targetSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String audit = jdbc.sql("""
                        SELECT reason || ' ' || coalesce(after_data::text, '')
                        FROM audit.audit_event
                        WHERE entity_id = :entityId
                        ORDER BY occurred_at DESC
                        LIMIT 1
                        """)
                .param("entityId", targetUserId)
                .query(String.class)
                .single();
        assertThat(audit)
                .contains("restrict user administration")
                .doesNotContain("password")
                .doesNotContain("token")
                .doesNotContain("cookie");
    }

    @Test
    void optionalRoleAndPermissionFiltersMayBeOmitted() throws Exception {
        mockMvc.perform(get("/api/v1/admin/roles").with(accessJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mockMvc.perform(get("/api/v1/admin/permissions").with(accessJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void listsOnlySeededPermissionCatalogWithAllowlistedSort() throws Exception {
        mockMvc.perform(get("/api/v1/admin/permissions")
                        .with(accessJwt())
                        .param("module", "ADMIN")
                        .param("sort", "action,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem(MANAGE_USERS_ID.toString())))
                .andExpect(jsonPath("$.filters.module").value("ADMIN"))
                .andExpect(jsonPath("$.sort[0]").value("action,desc"))
                .andExpect(content().string(not(containsString("SUPERUSER"))));

        mockMvc.perform(get("/api/v1/admin/permissions")
                        .with(accessJwt())
                        .param("sort", "description,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private UUID createUser(String prefix, boolean mustChangePassword) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status,
                            must_change_password, created_by, updated_by
                        ) VALUES (
                            :id, 'USER', :loginId, :passwordHash, 'QA', :loginId, 'ACTIVE',
                            :mustChangePassword, :systemId, :systemId
                        )
                        """)
                .param("id", id)
                .param("loginId", prefix + "-" + id)
                .param("passwordHash", passwordService.encode("an existing role password"))
                .param("mustChangePassword", mustChangePassword)
                .param("systemId", SYSTEM_USER_ID)
                .update();
        return id;
    }

    private void assignRole(UUID userId, UUID roleId) {
        jdbc.sql("INSERT INTO identity.user_role (user_id, role_id, assigned_by) VALUES (:userId, :roleId, :actorId)")
                .param("userId", userId)
                .param("roleId", roleId)
                .param("actorId", SYSTEM_USER_ID)
                .update();
    }

    private UUID createSession(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO identity.auth_session (
                            id, user_id, absolute_expires_at, idle_expires_at,
                            created_at, last_refreshed_at, client_ip, user_agent
                        ) VALUES (
                            :id, :userId, :absoluteExpiresAt, :idleExpiresAt,
                            :now, :now, CAST('198.51.100.30' AS inet), 'admin-role-test'
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

    private org.springframework.test.web.servlet.request.RequestPostProcessor accessJwt() {
        return jwtFor(actorId, actorSessionId);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(
            UUID userId,
            UUID sessionId) {
        var user = identityRepository.findById(userId).orElseThrow();
        String token = jwtTokenService.issueSessionAccessToken(user, sessionId).token();
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
