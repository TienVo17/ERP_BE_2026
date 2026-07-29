package com.company.erp.identity.security;

import java.io.IOException;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiProblemDetails;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.stereotype.Component;

@Component
public class PasswordChangeAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiProblemDetails problemDetails;

    public PasswordChangeAccessDeniedHandler(ApiProblemDetails problemDetails) {
        this.problemDetails = problemDetails;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException, ServletException {
        if (exception instanceof MissingCsrfTokenException
                || exception instanceof InvalidCsrfTokenException) {
            problemDetails.write(response, ApiErrorCode.CSRF_INVALID, "A valid CSRF token is required.", request);
            return;
        }
        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && token.getPrincipal() instanceof ErpPrincipal principal
                && "password_change".equals(principal.purpose())) {
            problemDetails.write(
                    response,
                    ApiErrorCode.PASSWORD_CHANGE_REQUIRED,
                    "The password must be changed before accessing this operation.",
                    request);
            return;
        }
        problemDetails.write(
                response,
                ApiErrorCode.FORBIDDEN,
                "The authenticated principal may not perform this operation.",
                request);
    }
}
