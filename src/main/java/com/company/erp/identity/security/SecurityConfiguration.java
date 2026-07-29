package com.company.erp.identity.security;

import java.util.function.Supplier;

import com.company.erp.config.ErpAuthenticationEntryPoint;
import com.company.erp.config.ErpSecurityProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    private static final RequestMatcher LOGIN = pathPattern(HttpMethod.POST, "/api/v1/auth/login");
    private static final RequestMatcher REFRESH = pathPattern(HttpMethod.POST, "/api/v1/auth/refresh");
    private static final RequestMatcher LOGOUT = pathPattern(HttpMethod.POST, "/api/v1/auth/logout");

    /**
     * Every unsafe operation that can act on the refresh cookie needs the CSRF token. Logout is
     * included only when the request actually carries the cookie, so a password-change challenge
     * can still terminate itself with its bearer token alone.
     */
    private static RequestMatcher cookieBackedAuth(String refreshCookieName) {
        return request -> LOGIN.matches(request)
                || REFRESH.matches(request)
                || (LOGOUT.matches(request) && hasRefreshCookie(request, refreshCookieName));
    }

    @Bean
    SecurityFilterChain identitySecurityFilterChain(
            HttpSecurity http,
            ErpAuthenticationEntryPoint authenticationEntryPoint,
            PasswordChangeAccessDeniedHandler accessDeniedHandler,
            JwtDecoder jwtDecoder,
            CurrentPrincipalLoader principalLoader,
            ErpSecurityProperties properties) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName("XSRF-TOKEN");
        csrfRepository.setHeaderName("X-CSRF-Token");
        csrfRepository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .sameSite("Strict")
                .secure(true));
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName("_csrf");

        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/change-password").authenticated()
                        .anyRequest().access(normalAccessOnly()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler)
                        .requireCsrfProtectionMatcher(
                                cookieBackedAuth(properties.refreshCookie().name())))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(principalLoader))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
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

    @Bean
    JwtDecoder jwtDecoder(JwtTokenService tokenService) {
        return tokenService::decode;
    }

    private static boolean hasRefreshCookie(
            jakarta.servlet.http.HttpServletRequest request,
            String refreshCookieName) {
        if (request.getCookies() == null) {
            return false;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (refreshCookieName.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    private static AuthorizationManager<RequestAuthorizationContext> normalAccessOnly() {
        return (Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) -> {
            Authentication current = authentication.get();
            boolean granted = current != null
                    && current.isAuthenticated()
                    && !(current instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
                    && (!(current instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken)
                    || (current.getPrincipal() instanceof ErpPrincipal principal
                    && "access".equals(principal.purpose())));
            return new AuthorizationDecision(granted);
        };
    }
}
