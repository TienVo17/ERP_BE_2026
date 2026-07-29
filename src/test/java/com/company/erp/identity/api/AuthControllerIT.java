package com.company.erp.identity.api;

import java.util.UUID;

import com.company.erp.identity.application.PasswordService;
import com.company.erp.support.PostgresTestConfiguration;
import java.util.concurrent.atomic.AtomicInteger;

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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
class AuthControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private ObjectMapper objectMapper;

    // Each test method authenticates from its own client IP so the production
    // 10-logins-per-minute trusted-IP limiter is never the reason a test fails.
    private static final AtomicInteger CLIENT_IP_SEQUENCE = new AtomicInteger();

    private String loginId;
    private String password;
    private String clientIp;

    @BeforeEach
    void createUser() {
        UUID id = UUID.randomUUID();
        clientIp = "198.51.100." + (CLIENT_IP_SEQUENCE.incrementAndGet() % 250 + 1);
        loginId = "auth-" + id;
        password = "valid authentication password";
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status
                        ) VALUES (:id, 'USER', :loginId, :passwordHash, 'QA', 'Auth User', 'ACTIVE')
                        """)
                .param("id", id)
                .param("loginId", loginId)
                .param("passwordHash", passwordService.encode(password))
                .update();
    }

    @Test
    void csrfEndpointIssuesReadableTokenWithoutCreatingServerSession() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @Test
    void loginDerivesIdentityServerSideSetsProtectedCookieAndSupportsBearerMe() throws Exception {
        MvcResult login = login(loginId.toUpperCase(), password)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("ERP_REFRESH="),
                                org.hamcrest.Matchers.containsString("HttpOnly"),
                                org.hamcrest.Matchers.containsString("Secure"),
                                org.hamcrest.Matchers.containsString("SameSite=Strict"),
                                org.hamcrest.Matchers.containsString("Path=/api/v1/auth")))))
                .andExpect(jsonPath("$.purpose").value("session"))
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.session.current").value(true))
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(password))))
                .andReturn();

        String accessToken = json(login).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(loginId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.permissions").isArray());
    }

    @Test
    void invalidAndUnknownCredentialsHaveSameGenericResponse() throws Exception {
        MvcResult invalid = login(loginId, "wrong password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn();
        MvcResult unknown = login("does-not-exist-" + UUID.randomUUID(), "wrong password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn();

        assert objectMapper.readTree(invalid.getResponse().getContentAsString()).get("detail").asText()
                .equals(objectMapper.readTree(unknown.getResponse().getContentAsString()).get("detail").asText());
    }

    @Test
    void missingOrInvalidCsrfRejectsLoginRefreshAndCookieLogout() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthRequests.LoginRequest(loginId, password))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("ERP_REFRESH", "invalid-refresh-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("ERP_REFRESH", "invalid-refresh-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void refreshCookieCannotAuthenticateBearerOnlyEndpoint() throws Exception {
        MvcResult login = login(loginId, password).andExpect(status().isOk()).andReturn();
        String cookieValue = login.getResponse().getCookie("ERP_REFRESH").getValue();

        mockMvc.perform(get("/api/v1/auth/me")
                        .cookie(new jakarta.servlet.http.Cookie("ERP_REFRESH", cookieValue)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void normalAccessBearerWithoutRefreshCookieCannotPretendToLogout() throws Exception {
        MvcResult login = login(loginId, password).andExpect(status().isOk()).andReturn();
        String accessToken = json(login).get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void bearerHeaderCannotBypassCsrfWhenLogoutUsesRefreshCookie() throws Exception {
        MvcResult login = login(loginId, password).andExpect(status().isOk()).andReturn();
        String accessToken = json(login).get("accessToken").asText();
        var refreshCookie = login.getResponse().getCookie("ERP_REFRESH");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void logoutRevokesFamilyClearsCookieAndRejectsExistingAccessToken() throws Exception {
        MvcResult login = login(loginId, password).andExpect(status().isOk()).andReturn();
        String accessToken = json(login).get("accessToken").asText();
        var refreshCookie = login.getResponse().getCookie("ERP_REFRESH");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(csrf())
                        .cookie(refreshCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("ERP_REFRESH", 0))
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void refreshRotatesCookieAndReplayingOldCookieRevokesTheFamily() throws Exception {
        MvcResult login = login(loginId, password).andExpect(status().isOk()).andReturn();
        var oldCookie = login.getResponse().getCookie("ERP_REFRESH");

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(csrf())
                        .cookie(oldCookie))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(cookie().exists("ERP_REFRESH"))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(refreshed.getResponse().getCookie("ERP_REFRESH").getValue())
                .isNotEqualTo(oldCookie.getValue());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(csrf())
                        .cookie(oldCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_REAUTH_REQUIRED"))
                .andExpect(cookie().maxAge("ERP_REFRESH", 0));
    }

    @Test
    void mixedChallengeAndStaleRefreshLogoutInvalidatesTheChallenge() throws Exception {
        MvcResult normalLogin = login(loginId, password).andExpect(status().isOk()).andReturn();
        var staleRefreshCookie = normalLogin.getResponse().getCookie("ERP_REFRESH");
        jdbc.sql("UPDATE identity.app_user SET must_change_password = true, password_generation = 4 WHERE login_id = :loginId")
                .param("loginId", loginId)
                .update();
        MvcResult challengeLogin = login(loginId, password)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("password_change"))
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0))
                .andReturn();
        String challenge = json(challengeLogin).get("accessToken").asText();
        MvcResult csrf = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String csrfToken = json(csrf).get("token").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + challenge)
                        .header("X-CSRF-Token", csrfToken)
                        .cookie(staleRefreshCookie, csrf.getResponse().getCookie("XSRF-TOKEN")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("ERP_REFRESH", 0))
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0));

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + challenge)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"valid authentication password","newPassword":"challenge must be invalid"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forcedPasswordChallengeCannotUseBusinessApisAndIsOneTime() throws Exception {
        jdbc.sql("UPDATE identity.app_user SET must_change_password = true, password_generation = 4 WHERE login_id = :loginId")
                .param("loginId", loginId)
                .update();
        MvcResult login = login(loginId, password)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("password_change"))
                .andExpect(cookie().doesNotExist("ERP_REFRESH"))
                .andReturn();
        String challenge = json(login).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + challenge))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + challenge)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"valid authentication password","newPassword":"a newly changed password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("session"))
                .andExpect(cookie().exists("ERP_REFRESH"));

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + challenge)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"valid authentication password","newPassword":"another changed password"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionListIsRedactedAndCannotRevokeAnotherUsersSession() throws Exception {
        MvcResult login = login(loginId, password).andExpect(status().isOk()).andReturn();
        JsonNode body = json(login);
        String accessToken = body.get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem(body.get("session").get("id").asText())))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("tokenHash"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("refreshToken"))));

        mockMvc.perform(post("/api/v1/auth/sessions/{id}/revoke", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void sessionListAppliesAllowlistedSortAndRejectsOtherFields() throws Exception {
        MvcResult login = login(loginId, password).andExpect(status().isOk()).andReturn();
        String accessToken = json(login).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .param("sort", "lastRefreshedAt,asc")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sort[0]").value("lastRefreshedAt,asc"))
                .andExpect(jsonPath("$.sort[1]").value("id,asc"));

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .param("sort", "password_hash,asc")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void sessionListShowsOnlyLiveSessionsAndDeadSessionsCannotBeRevoked() throws Exception {
        MvcResult firstDevice = login(loginId, password).andExpect(status().isOk()).andReturn();
        MvcResult secondDevice = login(loginId, password).andExpect(status().isOk()).andReturn();
        String accessToken = json(secondDevice).get("accessToken").asText();
        String firstSessionId = json(firstDevice).get("session").get("id").asText();
        String secondSessionId = json(secondDevice).get("session").get("id").asText();

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].id", hasItem(firstSessionId)));

        mockMvc.perform(post("/api/v1/auth/sessions/{id}/revoke", firstSessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(secondSessionId));

        mockMvc.perform(post("/api/v1/auth/sessions/{id}/revoke", firstSessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String attemptedLoginId, String attemptedPassword)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(csrf())
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AuthRequests.LoginRequest(
                        attemptedLoginId,
                        attemptedPassword))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
