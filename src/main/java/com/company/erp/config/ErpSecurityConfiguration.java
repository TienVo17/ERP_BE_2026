package com.company.erp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ErpSecurityConfiguration {

    private static final RequestMatcher COOKIE_BACKED_AUTH = request ->
            pathPattern(HttpMethod.POST, "/api/v1/auth/login").matches(request)
                    || pathPattern(HttpMethod.POST, "/api/v1/auth/refresh").matches(request)
                    || pathPattern(HttpMethod.POST, "/api/v1/auth/logout").matches(request);

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            ErpAuthenticationEntryPoint authenticationEntryPoint,
            ErpAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(COOKIE_BACKED_AUTH))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
