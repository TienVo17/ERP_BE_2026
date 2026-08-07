package com.company.erp.delivery.api;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ApiProblemDetails;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.delivery.api.DeliveryModels.DeliveryCommandRequest;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteCreateRequest;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteResponse;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteUpdateRequest;
import com.company.erp.delivery.api.DeliveryModels.DeliverySourcePageResponse;
import com.company.erp.delivery.application.DeliveryService;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.system.application.IdempotencyService;
import com.company.erp.system.application.IdempotencyService.CommandRequest;
import com.company.erp.system.application.IdempotencyService.CommandResponse;
import com.company.erp.system.application.IdempotencyService.CommandResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
@Validated
public class DeliveryController {

    private final DeliveryService service;
    private final IdempotencyService idempotency;
    private final ApiProblemDetails problems;
    private final ObjectMapper objectMapper;

    public DeliveryController(DeliveryService service, IdempotencyService idempotency,
            ApiProblemDetails problems, ObjectMapper objectMapper) {
        this.service = service;
        this.idempotency = idempotency;
        this.problems = problems;
        this.objectMapper = objectMapper;
    }

    /** Composing a draft needs DELIVERY:CREATE only; this page never implies DELIVERY:VIEW. */
    @GetMapping("/delivery-sources")
    @PreAuthorize("hasAuthority('DELIVERY:CREATE')")
    ResponseEntity<DeliverySourcePageResponse> sources(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String sysPoNo,
            @RequestParam(required = false) String buyerPo,
            @RequestParam(required = false) Boolean inStock) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.sources(page, size, sort, customerId, sysPoNo, buyerPo, inStock));
    }

    @GetMapping("/delivery-notes/{id}")
    @PreAuthorize("hasAuthority('DELIVERY:VIEW')")
    ResponseEntity<DeliveryNoteResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }

    @PostMapping("/delivery-notes")
    @PreAuthorize("hasAuthority('DELIVERY:CREATE')")
    ResponseEntity<byte[]> create(
            @Valid @RequestBody DeliveryNoteCreateRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        return command(201, body, null, key, authentication, request,
                () -> service.create(body, principal(authentication), requestId(request)));
    }

    @PutMapping("/delivery-notes/{id}")
    @PreAuthorize("hasAuthority('DELIVERY:CREATE')")
    ResponseEntity<byte[]> update(
            @PathVariable UUID id,
            @Valid @RequestBody DeliveryNoteUpdateRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        return command(200, body, ifMatch, key, authentication, request,
                () -> service.update(id, version, body, principal(authentication), requestId(request)));
    }

    @PostMapping("/delivery-notes/{id}/post")
    @PreAuthorize("hasAuthority('DELIVERY:POST')")
    ResponseEntity<byte[]> post(
            @PathVariable UUID id,
            @Valid @RequestBody DeliveryCommandRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        ErpPrincipal actor = principal(authentication);
        String normalizedKey = idempotencyKey(key);
        CommandRequest command = new CommandRequest(actor.user().id(), request.getMethod(),
                request.getRequestURI(), normalizedKey,
                IdempotencyService.requestHash(json(body), ifMatch), ifMatch);
        CommandResponse response = idempotency.execute(command, execution -> {
            try {
                Object result = service.post(id, version, body, execution.commandId(), actor,
                        requestId(request));
                return CommandResult.success(new CommandResponse(
                        200, MediaType.APPLICATION_JSON_VALUE, json(result)));
            } catch (ApiException exception) {
                return CommandResult.terminal(problem(exception.errorCode(), exception.getMessage(), request));
            } catch (ResourceNotFoundException exception) {
                return CommandResult.terminal(problem(
                        ApiErrorCode.NOT_FOUND, "The requested resource does not exist.", request));
            }
        });
        return ResponseEntity.status(response.status())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .cacheControl(CacheControl.noStore()).body(response.body());
    }

    @PostMapping("/delivery-notes/{id}/reverse")
    @PreAuthorize("hasAuthority('DELIVERY:REVERSE')")
    ResponseEntity<byte[]> reverse(
            @PathVariable UUID id,
            @Valid @RequestBody DeliveryCommandRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        ErpPrincipal actor = principal(authentication);
        String normalizedKey = idempotencyKey(key);
        CommandRequest command = new CommandRequest(actor.user().id(), request.getMethod(),
                request.getRequestURI(), normalizedKey,
                IdempotencyService.requestHash(json(body), ifMatch), ifMatch);
        CommandResponse response = idempotency.execute(command, execution -> {
            try {
                Object result = service.reverse(id, version, body, execution.commandId(), actor,
                        requestId(request));
                return CommandResult.success(new CommandResponse(
                        200, MediaType.APPLICATION_JSON_VALUE, json(result)));
            } catch (ApiException exception) {
                return CommandResult.terminal(problem(exception.errorCode(), exception.getMessage(), request));
            } catch (ResourceNotFoundException exception) {
                return CommandResult.terminal(problem(
                        ApiErrorCode.NOT_FOUND, "The requested resource does not exist.", request));
            }
        });
        return ResponseEntity.status(response.status())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .cacheControl(CacheControl.noStore()).body(response.body());
    }

    private ResponseEntity<byte[]> command(int success, Object body, String ifMatch, String rawKey,
            JwtAuthenticationToken authentication, HttpServletRequest request, Supplier<?> action) {
        String key = idempotencyKey(rawKey);
        CommandRequest command = new CommandRequest(
                principal(authentication).user().id(), request.getMethod(), request.getRequestURI(),
                key, IdempotencyService.requestHash(json(body), ifMatch), ifMatch);
        CommandResponse response = idempotency.execute(command, () -> {
            try {
                return CommandResult.success(new CommandResponse(
                        success, MediaType.APPLICATION_JSON_VALUE, json(action.get())));
            } catch (ApiException exception) {
                return CommandResult.terminal(problem(exception.errorCode(), exception.getMessage(), request));
            } catch (ResourceNotFoundException exception) {
                return CommandResult.terminal(problem(
                        ApiErrorCode.NOT_FOUND, "The requested resource does not exist.", request));
            }
        });
        return ResponseEntity.status(response.status())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .cacheControl(CacheControl.noStore()).body(response.body());
    }

    private CommandResponse problem(ApiErrorCode code, String detail, HttpServletRequest request) {
        return new CommandResponse(code.status().value(), MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                json(problems.create(code, detail, request)));
    }

    private byte[] json(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not serialize Delivery command payload", exception);
        }
    }

    private static long version(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ApiErrorCode.IF_MATCH_REQUIRED, "If-Match is required.");
        }
        try {
            return AdminQuery.parseIfMatch(value);
        } catch (ApiException exception) {
            throw new ApiException(ApiErrorCode.INVALID_IF_MATCH,
                    "If-Match must contain a quoted non-negative version.");
        }
    }

    private static String idempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key is required.");
        }
        String key = value.trim();
        if (key.length() > 120) {
            throw new ApiException(ApiErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key must not exceed 120 characters.");
        }
        return key;
    }

    private static ErpPrincipal principal(JwtAuthenticationToken token) {
        return (ErpPrincipal) token.getPrincipal();
    }

    private static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? null : value.substring(0, Math.min(120, value.length()));
    }
}
