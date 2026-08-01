package com.company.erp.api;

public class ApiException extends RuntimeException {

    private final ApiErrorCode errorCode;
    private final Integer retryAfterSeconds;

    public ApiException(ApiErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public ApiException(ApiErrorCode errorCode, String detail, Integer retryAfterSeconds) {
        super(detail);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ApiErrorCode errorCode() {
        return errorCode;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
