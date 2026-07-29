package com.company.erp.api;

import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
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
class SecurityProblemDetailsIT {

    private final MockMvc mockMvc;

    @Autowired
    SecurityProblemDetailsIT(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void anonymousBusinessRequestReturnsStatelessProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/not-implemented"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId", notNullValue()))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @Test
    @WithMockUser
    void preAuthenticatedBusinessPostDoesNotRequireCookieCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/not-implemented")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @Test
    @WithMockUser
    void authenticatedMissingEndpointReachesMvcWithoutSecurityFallback() throws Exception {
        mockMvc.perform(get("/api/v1/not-implemented"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @Test
    void missingCsrfOnCookieBackedAuthRequestReturnsStableProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"synthetic\",\"password\":\"synthetic\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(jsonPath("$.traceId", notNullValue()))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @Test
    void validCsrfAllowsAuthRequestToReachIdentityHandling() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"synthetic\",\"password\":\"synthetic\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }
}
