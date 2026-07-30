package com.company.erp.api;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ApiProblemDetails problemDetails;

    public ApiExceptionHandler(ApiProblemDetails problemDetails) {
        this.problemDetails = problemDetails;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = problemDetails.create(
                ApiErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid.",
                request);
        List<Map<String, String>> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(ApiExceptionHandler::fieldError)
                .toList();
        problem.setProperty("fieldErrors", fieldErrors);
        return response(problem, ApiErrorCode.VALIDATION_FAILED.status());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleMethodValidation(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        ProblemDetail problem = problemDetails.create(
                ApiErrorCode.VALIDATION_FAILED,
                "One or more request parameters are invalid.",
                request);
        List<Map<String, String>> fieldErrors = exception.getParameterValidationResults().stream()
                .flatMap(ApiExceptionHandler::parameterErrors)
                .sorted(Comparator.comparing(error -> error.get("field")))
                .toList();
        problem.setProperty("fieldErrors", fieldErrors);
        return response(problem, ApiErrorCode.VALIDATION_FAILED.status());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleInvalidRequest(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return response(problemDetails.create(
                ApiErrorCode.INVALID_REQUEST,
                "The request body is malformed or contains an unknown property.",
                request), ApiErrorCode.INVALID_REQUEST.status());
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(
            ApiException exception, HttpServletRequest request) {
        return response(problemDetails.create(
                exception.errorCode(),
                exception.getMessage(),
                request), exception.errorCode().status());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(
            DuplicateKeyException exception, HttpServletRequest request) {
        return response(problemDetails.create(
                ApiErrorCode.DUPLICATE_BUSINESS_KEY,
                "A resource with the same business key already exists.",
                request), ApiErrorCode.DUPLICATE_BUSINESS_KEY.status());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleInvalidData(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        return response(problemDetails.create(
                ApiErrorCode.VALIDATION_FAILED,
                "A supplied value is invalid for this resource.",
                request), ApiErrorCode.VALIDATION_FAILED.status());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticConflict(
            OptimisticLockingFailureException exception, HttpServletRequest request) {
        return response(problemDetails.create(
                ApiErrorCode.VERSION_CONFLICT,
                "The resource changed after it was read.",
                request), ApiErrorCode.VERSION_CONFLICT.status());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleUnauthenticated(
            AuthenticationException exception, HttpServletRequest request) {
        return response(problemDetails.create(
                ApiErrorCode.UNAUTHENTICATED,
                "Valid authentication is required.",
                request), ApiErrorCode.UNAUTHENTICATED.status());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleForbidden(
            AccessDeniedException exception, HttpServletRequest request) {
        return response(problemDetails.create(
                ApiErrorCode.FORBIDDEN,
                "The authenticated principal may not perform this operation.",
                request), ApiErrorCode.FORBIDDEN.status());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(
            ResourceNotFoundException exception, HttpServletRequest request) {
        return response(problemDetails.create(
                ApiErrorCode.NOT_FOUND,
                "The requested resource does not exist.",
                request), ApiErrorCode.NOT_FOUND.status());
    }

    /**
     * Last-resort handler so every API failure still uses the Problem Details envelope. The
     * exception message is deliberately dropped: it can carry SQL or credential-adjacent detail.
     * Spring's own web exceptions keep their standard handling, including the 404 for an
     * unmapped path.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request)
            throws Exception {
        if (exception instanceof org.springframework.web.ErrorResponse) {
            throw exception;
        }
        LOGGER.error("Unhandled API failure at {}", request.getRequestURI(), exception);
        return response(problemDetails.create(
                ApiErrorCode.INTERNAL_ERROR,
                "The request could not be completed.",
                request), ApiErrorCode.INTERNAL_ERROR.status());
    }

    private static ResponseEntity<ProblemDetail> response(ProblemDetail problem, HttpStatus status) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, "application/problem+json");
        if (problem.getInstance() != null
                && problem.getInstance().getPath().startsWith("/api/v1/auth/")) {
            response.cacheControl(org.springframework.http.CacheControl.noStore());
        }
        return response.body(problem);
    }

    private static Map<String, String> fieldError(FieldError error) {
        return fieldError(error.getField(), error.getCode(), error.getDefaultMessage());
    }

    private static java.util.stream.Stream<Map<String, String>> parameterErrors(
            ParameterValidationResult result) {
        if (result instanceof ParameterErrors errors && errors.hasFieldErrors()) {
            return errors.getFieldErrors().stream().map(ApiExceptionHandler::fieldError);
        }
        String parameterName = result.getMethodParameter().getParameterName();
        String field = parameterName == null
                ? "arg" + result.getMethodParameter().getParameterIndex()
                : parameterName;
        return result.getResolvableErrors().stream()
                .map(error -> fieldError(field, validationCode(error), error.getDefaultMessage()));
    }

    private static Map<String, String> fieldError(String field, String code, String message) {
        Map<String, String> fieldError = new LinkedHashMap<>();
        fieldError.put("field", field);
        fieldError.put("code", normalizeValidationCode(code));
        fieldError.put("message", message == null ? "invalid value" : message);
        return fieldError;
    }

    private static String validationCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? "INVALID" : codes[0];
    }

    private static String normalizeValidationCode(String code) {
        if (code == null || code.isBlank()) {
            return "INVALID";
        }
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < code.length(); index++) {
            char current = code.charAt(index);
            if (Character.isUpperCase(current) && index > 0) {
                normalized.append('_');
            }
            normalized.append(Character.toUpperCase(current));
        }
        return normalized.toString().toUpperCase(Locale.ROOT);
    }
}
