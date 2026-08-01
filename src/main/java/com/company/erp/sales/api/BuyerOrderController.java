package com.company.erp.sales.api;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ApiProblemDetails;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderCommandRequest;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderCopyRequest;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderCreateRequest;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderPageResponse;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderUpdateRequest;
import com.company.erp.sales.application.BuyerOrderService;
import com.company.erp.system.application.IdempotencyService;
import com.company.erp.system.application.IdempotencyService.CommandRequest;
import com.company.erp.system.application.IdempotencyService.CommandResponse;
import com.company.erp.system.application.IdempotencyService.CommandResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
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
@RequestMapping("/api/v1/buyer-orders")
@Validated
public class BuyerOrderController {

    private final BuyerOrderService service;
    private final IdempotencyService idempotency;
    private final ApiProblemDetails problems;
    private final ObjectMapper objectMapper;

    public BuyerOrderController(
            BuyerOrderService service,
            IdempotencyService idempotency,
            ApiProblemDetails problems,
            ObjectMapper objectMapper) {
        this.service = service;
        this.idempotency = idempotency;
        this.problems = problems;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('BUYER_ORDER:VIEW')")
    ResponseEntity<BuyerOrderPageResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String sysPoNo,
            @RequestParam(required = false) String buyerPo,
            @RequestParam(required = false) LocalDate poDateFrom,
            @RequestParam(required = false) LocalDate poDateTo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                service.list(page, size, sort, status, customerId, sysPoNo, buyerPo, poDateFrom, poDateTo));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BUYER_ORDER:VIEW')")
    ResponseEntity<BuyerOrderModels.BuyerOrderResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('BUYER_ORDER:CREATE')")
    ResponseEntity<byte[]> create(
            @Valid @RequestBody BuyerOrderCreateRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        return command(201, body, null, key, authentication, request,
                () -> service.create(body, principal(authentication), requestId(request), key));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('BUYER_ORDER:UPDATE')")
    ResponseEntity<byte[]> update(
            @PathVariable UUID id,
            @Valid @RequestBody BuyerOrderUpdateRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        return command(200, body, ifMatch, key, authentication, request,
                () -> service.update(id, version, body, principal(authentication), requestId(request), key));
    }

    @PostMapping("/{id}/copy")
    @PreAuthorize("hasAuthority('BUYER_ORDER:CREATE')")
    ResponseEntity<byte[]> copy(
            @PathVariable UUID id,
            @Valid @RequestBody BuyerOrderCopyRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        return command(201, body, ifMatch, key, authentication, request,
                () -> service.copy(id, version, body, principal(authentication), requestId(request), key));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('BUYER_ORDER:CONFIRM')")
    ResponseEntity<byte[]> confirm(
            @PathVariable UUID id,
            @Valid @RequestBody BuyerOrderCommandRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        return command(200, body, ifMatch, key, authentication, request,
                () -> service.confirm(id, version, body, principal(authentication), requestId(request), key));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('BUYER_ORDER:REOPEN')")
    ResponseEntity<byte[]> reopen(
            @PathVariable UUID id,
            @Valid @RequestBody BuyerOrderCommandRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        return command(200, body, ifMatch, key, authentication, request,
                () -> service.reopen(id, version, body, principal(authentication), requestId(request), key));
    }

    private ResponseEntity<byte[]> command(
            int successStatus, Object body, String ifMatch, String rawKey,
            JwtAuthenticationToken authentication, HttpServletRequest request,
            Supplier<?> action) {
        String key = idempotencyKey(rawKey);
        byte[] canonicalBody = json(body);
        ErpPrincipal actor = principal(authentication);
        CommandRequest commandRequest = new CommandRequest(
                actor.user().id(), request.getMethod(), request.getRequestURI(), key,
                IdempotencyService.requestHash(canonicalBody, ifMatch), ifMatch);
        CommandResponse response = idempotency.execute(commandRequest, () -> {
            try {
                return CommandResult.success(new CommandResponse(
                        successStatus, MediaType.APPLICATION_JSON_VALUE, json(action.get())));
            } catch (ApiException exception) {
                return CommandResult.terminal(new CommandResponse(
                        exception.errorCode().status().value(), MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        json(problems.create(exception.errorCode(), exception.getMessage(), request))));
            } catch (ResourceNotFoundException exception) {
                return CommandResult.terminal(new CommandResponse(
                        ApiErrorCode.NOT_FOUND.status().value(), MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        json(problems.create(ApiErrorCode.NOT_FOUND,
                                "The requested resource does not exist.", request))));
            }
        });
        return ResponseEntity.status(response.status())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(response.body());
    }

    private byte[] json(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not serialize Buyer Order command payload", exception);
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

    private static ErpPrincipal principal(JwtAuthenticationToken authentication) {
        return (ErpPrincipal) authentication.getPrincipal();
    }

    private static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? null : value.substring(0, Math.min(value.length(), 120));
    }
}
