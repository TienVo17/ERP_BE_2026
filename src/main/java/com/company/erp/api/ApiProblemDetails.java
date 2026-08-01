package com.company.erp.api;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public final class ApiProblemDetails {

    private static final String TRACE_ID = "traceId";

    private final ObjectMapper objectMapper;

    public ApiProblemDetails(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProblemDetail create(
            ApiErrorCode errorCode, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
        problem.setType(errorCode.type());
        problem.setTitle(errorCode.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", errorCode.name());
        problem.setProperty(TRACE_ID, traceId(request));
        return problem;
    }

    public void write(
            HttpServletResponse response,
            ApiErrorCode errorCode,
            String detail,
            HttpServletRequest request) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (isProtectedNoStorePath(request.getRequestURI())) {
            response.setHeader("Cache-Control", "no-store");
        }
        objectMapper.writeValue(response.getOutputStream(), create(errorCode, detail, request));
    }

    static boolean isProtectedNoStorePath(String path) {
        return path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/buyer-orders")
                || path.startsWith("/api/v1/production-")
                || path.startsWith("/api/v1/stock-positions")
                || path.startsWith("/api/v1/delivery-")
                || path.startsWith("/api/v1/debit-notes");
    }

    private static String traceId(HttpServletRequest request) {
        String traceId = MDC.get(TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader("X-Trace-Id");
        }
        return traceId == null || traceId.isBlank() ? "unavailable" : traceId;
    }
}
