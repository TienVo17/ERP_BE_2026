package com.company.erp.identity.api;

import java.util.UUID;

import com.company.erp.identity.application.PasswordService;
import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthRateLimitIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void endpointsEnforceFrozenTrustedIpLimitsWithoutChangingAccountState() throws Exception {
        UUID userId = UUID.randomUUID();
        String loginId = "rate-limit-" + userId;
        String password = "rate limit test password";
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status
                        ) VALUES (:id, 'USER', :loginId, :passwordHash, 'QA', 'Rate Limit User', 'ACTIVE')
                        """)
                .param("id", userId)
                .param("loginId", loginId)
                .param("passwordHash", passwordService.encode(password))
                .update();

        String body = objectMapper.writeValueAsString(new AuthRequests.LoginRequest(loginId, password));
        for (int attempt = 1; attempt <= 10; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(csrf())
                            .with(request -> remoteAddress(request, "192.0.2.100"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .with(request -> remoteAddress(request, "192.0.2.100"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        for (int attempt = 1; attempt <= 120; attempt++) {
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .with(csrf())
                            .with(request -> remoteAddress(request, "192.0.2.101"))
                            .cookie(new jakarta.servlet.http.Cookie("ERP_REFRESH", "invalid-refresh-token")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_REAUTH_REQUIRED"));
        }
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(csrf())
                        .with(request -> remoteAddress(request, "192.0.2.101"))
                        .cookie(new jakarta.servlet.http.Cookie("ERP_REFRESH", "invalid-refresh-token")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                        SELECT status, version, password_generation, must_change_password
                        FROM identity.app_user
                        WHERE id = :id
                        """)
                .param("id", userId)
                .query((resultSet, rowNum) -> java.util.List.of(
                        resultSet.getString("status"),
                        resultSet.getLong("version"),
                        resultSet.getLong("password_generation"),
                        resultSet.getBoolean("must_change_password")))
                .single()).containsExactly("ACTIVE", 0L, 0L, false);
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM identity.login_event
                        WHERE login_id_attempted = :loginId AND outcome = 'BLOCKED_IP'
                        """)
                .param("loginId", loginId)
                .query(Integer.class)
                .single()).isOne();
    }

    private static org.springframework.mock.web.MockHttpServletRequest remoteAddress(
            org.springframework.mock.web.MockHttpServletRequest request,
            String address) {
        request.setRemoteAddr(address);
        return request;
    }
}
