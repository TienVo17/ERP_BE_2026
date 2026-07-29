package com.company.erp.api;

import java.net.URI;

import org.springframework.http.HttpStatus;

public enum ApiErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request", "invalid-request"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed", "validation"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required", "unauthenticated"),
    CSRF_INVALID(HttpStatus.UNAUTHORIZED, "CSRF validation failed", "csrf-invalid"),
    REFRESH_REAUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "Interactive login required", "refresh-reauth-required"),
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "Password change required", "password-change-required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied", "forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found", "not-found"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "Version conflict", "version-conflict"),
    DUPLICATE_BUSINESS_KEY(HttpStatus.CONFLICT, "Duplicate business key", "duplicate-business-key"),
    MASTER_IN_USE(HttpStatus.CONFLICT, "Master data is in use", "master-in-use"),
    RECOVERY_ADMIN_REQUIRED(HttpStatus.CONFLICT, "Recovery administrator required", "recovery-admin-required"),
    EXCHANGE_RATE_MISSING(HttpStatus.CONFLICT, "Exchange rate missing", "exchange-rate-missing"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Request rate exceeded", "rate-limited");

    private static final String TYPE_BASE = "https://erp.example.invalid/problems/";

    private final HttpStatus status;
    private final String title;
    private final URI type;

    ApiErrorCode(HttpStatus status, String title, String typeSlug) {
        this.status = status;
        this.title = title;
        this.type = URI.create(TYPE_BASE + typeSlug);
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public URI type() {
        return type;
    }
}
