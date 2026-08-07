package com.company.erp.inventory.api;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ApiProblemDetails;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.inventory.api.StockModels.EligibleReturnSourcePageResponse;
import com.company.erp.inventory.api.StockModels.StockDisposalRequest;
import com.company.erp.inventory.api.StockModels.StockMovementPageResponse;
import com.company.erp.inventory.api.StockModels.StockPositionPageResponse;
import com.company.erp.inventory.api.StockModels.StockReturnRequest;
import com.company.erp.inventory.application.StockService;
import com.company.erp.production.api.ProductionModels.StockPositionResponse;
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
import java.util.function.BiFunction;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/stock-positions")
@Validated
public class StockController {

    private final StockService service;
    private final IdempotencyService idempotency;
    private final ApiProblemDetails problems;
    private final ObjectMapper objectMapper;

    public StockController(StockService service, IdempotencyService idempotency,
            ApiProblemDetails problems, ObjectMapper objectMapper) {
        this.service = service;
        this.idempotency = idempotency;
        this.problems = problems;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    ResponseEntity<StockPositionPageResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID productionOrderId,
            @RequestParam(required = false) Boolean inStock) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(page, size, sort, customerId, productionOrderId, inStock));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    ResponseEntity<StockPositionResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }

    @GetMapping("/{id}/movements")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    ResponseEntity<StockMovementPageResponse> movements(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) String movementType,
            @RequestParam(required = false) LocalDate businessDateFrom,
            @RequestParam(required = false) LocalDate businessDateTo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.movements(id, page, size, sort, movementType,
                        businessDateFrom, businessDateTo));
    }

    @GetMapping("/{id}/eligible-return-sources")
    @PreAuthorize("hasAuthority('STOCK:RETURN')")
    ResponseEntity<EligibleReturnSourcePageResponse> eligibleReturns(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.eligibleReturns(id, page, size, sort));
    }

    @PostMapping("/{id}/disposals")
    @PreAuthorize("hasAuthority('STOCK:DISPOSE')")
    ResponseEntity<byte[]> dispose(
            @PathVariable UUID id,
            @Valid @RequestBody StockDisposalRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        return command(body, ifMatch, key, authentication, request,
                (commandId, actor) -> service.dispose(id, version, body, commandId,
                        actor, requestId(request)));
    }

    @PostMapping("/{id}/returns")
    @PreAuthorize("hasAuthority('STOCK:RETURN')")
    ResponseEntity<byte[]> recordReturn(
            @PathVariable UUID id,
            @Valid @RequestBody StockReturnRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        return command(body, ifMatch, key, authentication, request,
                (commandId, actor) -> service.recordReturn(id, version, body, commandId,
                        actor, requestId(request)));
    }

    private ResponseEntity<byte[]> command(Object body, String ifMatch, String rawKey,
            JwtAuthenticationToken authentication, HttpServletRequest request,
            BiFunction<UUID, ErpPrincipal, ?> action) {
        ErpPrincipal actor = principal(authentication);
        String key = idempotencyKey(rawKey);
        CommandRequest command = new CommandRequest(actor.user().id(), request.getMethod(),
                request.getRequestURI(), key, IdempotencyService.requestHash(json(body), ifMatch), ifMatch);
        CommandResponse response = idempotency.execute(command, execution -> {
            try {
                return CommandResult.success(new CommandResponse(200, MediaType.APPLICATION_JSON_VALUE,
                        json(action.apply(execution.commandId(), actor))));
            } catch (ApiException exception) {
                return CommandResult.terminal(problem(exception.errorCode(), exception.getMessage(), request));
            } catch (ResourceNotFoundException exception) {
                return CommandResult.terminal(problem(ApiErrorCode.NOT_FOUND,
                        "The requested resource does not exist.", request));
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
            throw new IllegalStateException("Could not serialize Stock command payload", exception);
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
        return value == null || value.isBlank() ? null : value.substring(0, Math.min(120, value.length()));
    }
}
