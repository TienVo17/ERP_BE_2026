package com.company.erp.config;

import java.io.IOException;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiProblemDetails;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
final class ErpAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiProblemDetails problemDetails;

    ErpAuthenticationEntryPoint(ApiProblemDetails problemDetails) {
        this.problemDetails = problemDetails;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        problemDetails.write(
                response,
                ApiErrorCode.UNAUTHENTICATED,
                "Valid authentication is required.",
                request);
    }
}
