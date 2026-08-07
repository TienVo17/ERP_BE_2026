package com.company.erp.production.api;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ApiProblemDetails;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.production.api.ProductionModels.ProductionCommandRequest;
import com.company.erp.production.api.ProductionModels.ProductionConfigurationRequest;
import com.company.erp.production.api.ProductionModels.ProductionEventPageResponse;
import com.company.erp.production.api.ProductionModels.ProductionGroupCreateRequest;
import com.company.erp.production.api.ProductionModels.ProductionGroupResponse;
import com.company.erp.production.api.ProductionModels.ProductionOrderPageResponse;
import com.company.erp.production.api.ProductionModels.ProductionOrderResponse;
import com.company.erp.production.application.ProductionDocumentRenderer;
import com.company.erp.production.application.ProductionService;
import com.company.erp.reporting.ReportAdmission;
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
public class ProductionController {

    private final ProductionService service;
    private final ProductionDocumentRenderer documentRenderer;
    private final ReportAdmission reportAdmission;
    private final IdempotencyService idempotency;
    private final ApiProblemDetails problems;
    private final ObjectMapper objectMapper;

    public ProductionController(
            ProductionService service,
            ProductionDocumentRenderer documentRenderer,
            ReportAdmission reportAdmission,
            IdempotencyService idempotency,
            ApiProblemDetails problems,
            ObjectMapper objectMapper) {
        this.service = service;
        this.documentRenderer = documentRenderer;
        this.reportAdmission = reportAdmission;
        this.idempotency = idempotency;
        this.problems = problems;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/production-orders")
    @PreAuthorize("hasAuthority('PRODUCTION:VIEW')")
    ResponseEntity<ProductionOrderPageResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size, @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) String status, @RequestParam(required = false) UUID buyerOrderId,
            @RequestParam(required = false) String productionNo, @RequestParam(required = false) String productKind,
            @RequestParam(required = false) String groupNo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(page, size, sort, status,
                buyerOrderId, productionNo, productKind, groupNo));
    }

    @GetMapping("/production-orders/{id}")
    @PreAuthorize("hasAuthority('PRODUCTION:VIEW')")
    ResponseEntity<ProductionOrderResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }

    @GetMapping("/production-orders/{id}/events")
    @PreAuthorize("hasAuthority('PRODUCTION:VIEW')")
    ResponseEntity<ProductionEventPageResponse> events(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.events(id, page, size, sort));
    }

    @GetMapping(value = "/production-orders/{id}/document.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('PRODUCTION:VIEW')")
    ResponseEntity<byte[]> document(@PathVariable UUID id) {
        ProductionOrderResponse order = service.get(id);
        byte[] pdf = reportAdmission.generate(() -> documentRenderer.render(order));
        String filename = order.productionNo().replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .cacheControl(CacheControl.noStore())
                .body(pdf);
    }

    @PutMapping("/production-orders/{id}/configuration")
    @PreAuthorize("hasAuthority('PRODUCTION:CONFIGURE')")
    ResponseEntity<byte[]> configure(
            @PathVariable UUID id,
            @Valid @RequestBody ProductionConfigurationRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        return command(200, body, ifMatch, key, authentication, request,
                () -> service.configure(id, version, body, principal(authentication), requestId(request)));
    }

    @PostMapping("/production-orders/{id}/finish")
    @PreAuthorize("hasAuthority('PRODUCTION:FINISH')")
    ResponseEntity<byte[]> finish(
            @PathVariable UUID id,
            @Valid @RequestBody ProductionCommandRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        String normalizedKey = idempotencyKey(key);
        CommandRequest command = new CommandRequest(
                principal(authentication).user().id(),
                request.getMethod(),
                request.getRequestURI(),
                normalizedKey,
                IdempotencyService.requestHash(json(body), ifMatch),
                ifMatch);
        CommandResponse response = idempotency.execute(command, execution -> {
            try {
                Object result = service.finish(
                        id,
                        version,
                        body,
                        execution.commandId(),
                        principal(authentication),
                        requestId(request),
                        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")));
                return CommandResult.success(new CommandResponse(
                        200, MediaType.APPLICATION_JSON_VALUE, json(result)));
            } catch (ApiException exception) {
                return CommandResult.terminal(problem(exception.errorCode(), exception.getMessage(), request));
            } catch (ResourceNotFoundException exception) {
                return CommandResult.terminal(problem(
                        ApiErrorCode.NOT_FOUND,
                        "The requested resource does not exist.",
                        request));
            }
        });
        return ResponseEntity.status(response.status())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(response.body());
    }

    @PostMapping("/production-groups")
    @PreAuthorize("hasAuthority('PRODUCTION:GROUP')")
    ResponseEntity<byte[]> group(@Valid @RequestBody ProductionGroupCreateRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        return command(201, body, null, key, authentication, request,
                () -> service.group(body, principal(authentication), requestId(request), key));
    }

    @PostMapping("/production-groups/{id}/ungroup")
    @PreAuthorize("hasAuthority('PRODUCTION:GROUP')")
    ResponseEntity<byte[]> ungroup(@PathVariable UUID id, @Valid @RequestBody ProductionCommandRequest body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        long version = version(ifMatch);
        return command(200, body, ifMatch, key, authentication, request,
                () -> service.ungroup(id, version, body, principal(authentication), requestId(request), key));
    }

    private ResponseEntity<byte[]> command(int success, Object body, String ifMatch, String rawKey,
            JwtAuthenticationToken authentication, HttpServletRequest request, Supplier<?> action) {
        String key = idempotencyKey(rawKey);
        CommandRequest command = new CommandRequest(principal(authentication).user().id(), request.getMethod(), request.getRequestURI(),
                key, IdempotencyService.requestHash(json(body), ifMatch), ifMatch);
        CommandResponse response = idempotency.execute(command, () -> {
            try {
                return CommandResult.success(new CommandResponse(success, MediaType.APPLICATION_JSON_VALUE, json(action.get())));
            } catch (ApiException exception) {
                return CommandResult.terminal(problem(exception.errorCode(), exception.getMessage(), request));
            } catch (ResourceNotFoundException exception) {
                return CommandResult.terminal(problem(ApiErrorCode.NOT_FOUND, "The requested resource does not exist.", request));
            }
        });
        return ResponseEntity.status(response.status()).contentType(MediaType.parseMediaType(response.contentType()))
                .cacheControl(CacheControl.noStore()).body(response.body());
    }

    private CommandResponse problem(ApiErrorCode code, String detail, HttpServletRequest request) {
        return new CommandResponse(code.status().value(), MediaType.APPLICATION_PROBLEM_JSON_VALUE, json(problems.create(code, detail, request)));
    }

    private byte[] json(Object value) {
        try { return objectMapper.writeValueAsBytes(value); }
        catch (RuntimeException exception) { throw new IllegalStateException("Could not serialize Production command payload", exception); }
    }

    private static long version(String value) {
        if (value == null || value.isBlank()) throw new ApiException(ApiErrorCode.IF_MATCH_REQUIRED, "If-Match is required.");
        try { return AdminQuery.parseIfMatch(value); }
        catch (ApiException exception) { throw new ApiException(ApiErrorCode.INVALID_IF_MATCH, "If-Match must contain a quoted non-negative version."); }
    }

    private static String idempotencyKey(String value) {
        if (value == null || value.isBlank()) throw new ApiException(ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key is required.");
        String key = value.trim();
        if (key.length() > 120) throw new ApiException(ApiErrorCode.INVALID_IDEMPOTENCY_KEY, "Idempotency-Key must not exceed 120 characters.");
        return key;
    }

    private static ErpPrincipal principal(JwtAuthenticationToken token) { return (ErpPrincipal) token.getPrincipal(); }
    private static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? null : value.substring(0, Math.min(value.length(), 120));
    }
}
