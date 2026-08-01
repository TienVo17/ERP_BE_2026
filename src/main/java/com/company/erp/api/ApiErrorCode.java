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
    IF_MATCH_REQUIRED(HttpStatus.BAD_REQUEST, "Version header required", "if-match-required"),
    INVALID_IF_MATCH(HttpStatus.BAD_REQUEST, "Invalid version header", "invalid-if-match"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency key required", "idempotency-key-required"),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Invalid idempotency key", "invalid-idempotency-key"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused", "idempotency-key-reused"),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "Command already in progress", "idempotency-in-progress"),
    IDEMPOTENCY_RESULT_EXPIRED(HttpStatus.GONE, "Idempotency result expired", "idempotency-result-expired"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition", "invalid-state-transition"),
    DOWNSTREAM_ACTIVITY_EXISTS(HttpStatus.CONFLICT, "Downstream activity exists", "downstream-activity-exists"),
    GROUP_MEMBERSHIP_INVALID(HttpStatus.CONFLICT, "Invalid group membership", "group-membership-invalid"),
    PRODUCTION_ALREADY_FINISHED(HttpStatus.CONFLICT, "Production already finished", "production-already-finished"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Insufficient stock", "insufficient-stock"),
    DELIVERY_ALREADY_POSTED(HttpStatus.CONFLICT, "Delivery already posted", "delivery-already-posted"),
    DELIVERY_ALREADY_REVERSED(HttpStatus.CONFLICT, "Delivery already reversed", "delivery-already-reversed"),
    DELIVERY_HAS_RETURNS(HttpStatus.CONFLICT, "Delivery has returns", "delivery-has-returns"),
    RETURN_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "Return limit exceeded", "return-limit-exceeded"),
    DISPOSAL_EXCEEDS_STOCK(HttpStatus.CONFLICT, "Disposal exceeds stock", "disposal-exceeds-stock"),
    REPORT_LIMIT_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE, "Report limit exceeded", "report-limit-exceeded"),
    REPORT_BUSY(HttpStatus.TOO_MANY_REQUESTS, "Report generation busy", "report-busy"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Request rate exceeded", "rate-limited"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", "internal-error");

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
