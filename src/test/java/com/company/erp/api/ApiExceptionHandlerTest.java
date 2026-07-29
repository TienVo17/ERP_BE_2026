package com.company.erp.api;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import jakarta.validation.Validation;
import jakarta.validation.constraints.Max;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.MethodValidationAdapter;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private ApiExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new ApiExceptionHandler(new ApiProblemDetails(JsonMapper.builder().build()));
        request = new MockHttpServletRequest("POST", "/api/v1/test");
    }

    @Test
    void mapsValidationFailuresToStableFieldErrors() throws Exception {
        Method method = ApiExceptionHandlerTest.class.getDeclaredMethod("validatedEndpoint", ValidationRequest.class);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(
                new ValidationRequest(""), "validationRequest");
        bindingResult.addError(new FieldError(
                "validationRequest",
                "code",
                "",
                false,
                new String[] {"NotBlank"},
                null,
                "must not be blank"));

        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new MethodParameter(method, 0), bindingResult);

        ProblemDetail problem = handler.handleValidation(exception, request).getBody();

        assertProblem(problem, HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED);
        assertThat(problem.getDetail()).isEqualTo("One or more fields are invalid.");
        assertThat(problem.getProperties()).containsKey("fieldErrors");
        assertThat((List<?>) problem.getProperties().get("fieldErrors"))
                .singleElement()
                .satisfies(fieldError -> {
                    Map<?, ?> values = (Map<?, ?>) fieldError;
                    assertThat(values.get("field")).isEqualTo("code");
                    assertThat(values.get("code")).isEqualTo("NOT_BLANK");
                    assertThat(values.get("message")).isEqualTo("must not be blank");
                });
    }

    @Test
    void mapsRequestParameterValidationToStableFieldErrors() throws Exception {
        Method method = ApiExceptionHandlerTest.class.getDeclaredMethod("pagedEndpoint", int.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        MethodValidationAdapter adapter = new MethodValidationAdapter(
                Validation.buildDefaultValidatorFactory().getValidator());
        var result = adapter.validateArguments(
                this,
                method,
                new MethodParameter[] {parameter},
                new Object[] {101},
                new Class<?>[0]);
        HandlerMethodValidationException exception = new HandlerMethodValidationException(result);

        ProblemDetail problem = handler.handleMethodValidation(exception, request).getBody();

        assertProblem(problem, HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED);
        assertThat(problem.getDetail()).isEqualTo("One or more request parameters are invalid.");
        assertThat((List<?>) problem.getProperties().get("fieldErrors"))
                .singleElement()
                .satisfies(fieldError -> {
                    Map<?, ?> values = (Map<?, ?>) fieldError;
                    assertThat(values.get("field")).isEqualTo("size");
                    assertThat(values.get("code").toString()).contains("MAX");
                    assertThat(values.get("message")).isEqualTo("must be less than or equal to 100");
                });
    }

    @Test
    void mapsMalformedOrUnknownJsonToInvalidRequest() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Unrecognized field passwordHash containing secret-data",
                new MockHttpInputMessage(new byte[0]));

        ProblemDetail problem = handler.handleInvalidRequest(exception, request).getBody();

        assertProblem(problem, HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST);
        assertThat(problem.getDetail()).isEqualTo(
                "The request body is malformed or contains an unknown property.");
        assertThat(problem.toString()).doesNotContain("passwordHash", "secret-data");
    }

    @Test
    void mapsDatabaseFailuresWithoutLeakingSql() {
        ProblemDetail duplicate = handler.handleDuplicate(
                new DuplicateKeyException(
                        "duplicate key value violates unique constraint user_login_id_key; password=secret"),
                request).getBody();
        ProblemDetail conflict = handler.handleOptimisticConflict(
                new OptimisticLockingFailureException("UPDATE identity.app_user SET password_hash='secret'"),
                request).getBody();

        assertProblem(duplicate, HttpStatus.CONFLICT, ApiErrorCode.DUPLICATE_BUSINESS_KEY);
        assertThat(duplicate.getDetail()).isEqualTo("A resource with the same business key already exists.");
        assertThat(duplicate.toString()).doesNotContain("constraint", "password", "secret");

        assertProblem(conflict, HttpStatus.CONFLICT, ApiErrorCode.VERSION_CONFLICT);
        assertThat(conflict.getDetail()).isEqualTo("The resource changed after it was read.");
        assertThat(conflict.toString()).doesNotContain("UPDATE", "password_hash", "secret");
    }

    @Test
    void mapsSecurityAndNotFoundFailuresToStableCodes() {
        ProblemDetail unauthenticated = handler.handleUnauthenticated(
                new AuthenticationCredentialsNotFoundException("raw bearer token"), request).getBody();
        ProblemDetail forbidden = handler.handleForbidden(
                new AccessDeniedException("ADMIN:SUPERUSER denied"), request).getBody();
        ProblemDetail notFound = handler.handleNotFound(
                new ResourceNotFoundException("identity.app_user", "missing-login-id"), request).getBody();

        assertProblem(unauthenticated, HttpStatus.UNAUTHORIZED, ApiErrorCode.UNAUTHENTICATED);
        assertProblem(forbidden, HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN);
        assertProblem(notFound, HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND);

        assertThat(unauthenticated.toString()).doesNotContain("raw bearer token");
        assertThat(forbidden.toString()).doesNotContain("ADMIN:SUPERUSER");
        assertThat(notFound.toString()).doesNotContain("identity.app_user", "missing-login-id");
    }

    private static void assertProblem(ProblemDetail problem, HttpStatus status, ApiErrorCode errorCode) {
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(status.value());
        assertThat(problem.getTitle()).isEqualTo(errorCode.title());
        assertThat(problem.getType().toString()).isEqualTo(errorCode.type().toString());
        assertThat(problem.getProperties())
                .containsEntry("code", errorCode.name())
                .containsKey("traceId");
        assertThat(problem.getInstance().toString()).isEqualTo("/api/v1/test");
    }

    @SuppressWarnings("unused")
    private void validatedEndpoint(ValidationRequest request) {
    }

    @SuppressWarnings("unused")
    private void pagedEndpoint(@Max(100) int size) {
    }

    private record ValidationRequest(String code) {
    }
}
