package com.company.erp.system.application;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.system.infrastructure.IdempotencyJdbcRepository;
import com.company.erp.system.infrastructure.IdempotencyJdbcRepository.NewClaim;
import com.company.erp.system.infrastructure.IdempotencyJdbcRepository.Record;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotencyService {

    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final IdempotencyJdbcRepository repository;
    private final TransactionTemplate claimTransaction;
    private final TransactionTemplate executionTransaction;
    private final TransactionTemplate recoveryTransaction;
    private final Clock clock;

    @Autowired
    public IdempotencyService(
            IdempotencyJdbcRepository repository,
            PlatformTransactionManager transactionManager) {
        this(repository, transactionManager, Clock.systemUTC());
    }

    IdempotencyService(
            IdempotencyJdbcRepository repository,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.claimTransaction = required(transactionManager);
        this.executionTransaction = required(transactionManager);
        this.recoveryTransaction = requiresNew(transactionManager);
        this.clock = clock;
    }

    public CommandResponse execute(CommandRequest rawRequest, Supplier<CommandResult> command) {
        CommandRequest request = normalize(rawRequest);
        Claim claim = claimTransaction.execute(status -> claim(request));
        if (claim == null) {
            throw new IllegalStateException("Claim transaction returned no result");
        }
        if (claim.replay() != null) {
            return claim.replay();
        }

        try {
            CommandResponse response = executionTransaction.execute(status -> executeClaim(claim, command, status));
            if (response == null) {
                throw new IllegalStateException("Execution transaction returned no response");
            }
            return response;
        } catch (RuntimeException exception) {
            recoveryTransaction.executeWithoutResult(status ->
                    repository.releaseForRetry(claim.id(), claim.owner(), claim.generation()));
            throw exception;
        }
    }

    public int purgeExpired(int limit) {
        Integer deleted = claimTransaction.execute(status -> repository.purgeExpired(limit));
        return deleted == null ? 0 : deleted;
    }

    private Claim claim(CommandRequest request) {
        Scope scope = scope(request);
        UUID owner = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = now();
        OffsetDateTime leaseExpiresAt = now.plus(LEASE);
        OffsetDateTime expiresAt = now.plus(RETENTION);
        UUID id = UUID.randomUUID();

        if (repository.insertClaim(new NewClaim(
                id, scope.text(), scope.digest(), request.actorId(), request.method(), request.normalizedPath(),
                request.idempotencyKey(), request.requestHash(), commandId, owner, leaseExpiresAt, expiresAt))) {
            return new Claim(id, owner, 1, request.requestHash(), null);
        }

        Record existing = repository.findByScopeAndKeyForUpdate(scope.text(), request.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Idempotency conflict disappeared"));
        if (!MessageDigest.isEqual(
                existing.requestHash().getBytes(StandardCharsets.UTF_8),
                request.requestHash().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This idempotency key was used for another request.");
        }
        if ("COMPLETED".equals(existing.status())) {
            return new Claim(existing.id(), null, existing.leaseGeneration(), existing.requestHash(), response(existing));
        }
        if ("QUARANTINED".equals(existing.status())) {
            if (existing.expiresAt().isAfter(now)) {
                throw new ApiException(ApiErrorCode.IDEMPOTENCY_RESULT_EXPIRED,
                        "The original response is no longer available for replay.");
            }
            repository.deleteExpired(existing.id());
            return claim(request);
        }
        if ("IN_PROGRESS".equals(existing.status()) && existing.leaseExpiresAt().isAfter(now)) {
            throw new ApiException(ApiErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "The command is still in progress. Retry after two seconds.", 2);
        }
        if (!repository.reclaim(
                existing.id(), request.requestHash(), owner, existing.leaseGeneration(), leaseExpiresAt, expiresAt)) {
            throw new ApiException(ApiErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "The command is being reclaimed. Retry after two seconds.", 2);
        }
        return new Claim(existing.id(), owner, existing.leaseGeneration() + 1, existing.requestHash(), null);
    }

    private CommandResponse executeClaim(
            Claim claim,
            Supplier<CommandResult> command,
            org.springframework.transaction.TransactionStatus transactionStatus) {
        Record locked = repository.findByIdForUpdate(claim.id())
                .orElseThrow(() -> new IllegalStateException("Idempotency claim disappeared"));
        if (!"IN_PROGRESS".equals(locked.status())
                || !claim.owner().equals(locked.leaseOwner())
                || claim.generation() != locked.leaseGeneration()
                || !claim.requestHash().equals(locked.requestHash())) {
            if ("COMPLETED".equals(locked.status())) {
                return response(locked);
            }
            throw new ApiException(ApiErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "The command lease changed before execution.");
        }

        Object savepoint = transactionStatus.createSavepoint();
        CommandResult result = command.get();
        if (result.terminal()) {
            transactionStatus.rollbackToSavepoint(savepoint);
        }
        validateResponse(result.response());
        if (!repository.complete(
                claim.id(), claim.owner(), claim.generation(), result.response().status(),
                result.response().contentType(), result.response().body(), now())) {
            throw new IllegalStateException("Idempotency completion fence failed");
        }
        return result.response();
    }

    private static void validateResponse(CommandResponse response) {
        if (response.status() < 200 || response.status() > 599) {
            throw new IllegalArgumentException("Response status must be between 200 and 599");
        }
        if (response.contentType() == null || response.contentType().isBlank()) {
            throw new IllegalArgumentException("Response content type is required");
        }
        if (response.body() == null || response.body().length > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Response body exceeds the replay limit");
        }
    }

    private static CommandRequest normalize(CommandRequest request) {
        if (request == null || request.actorId() == null) {
            throw new IllegalArgumentException("Actor is required");
        }
        String method = required(request.method(), "Method").toUpperCase(Locale.ROOT);
        String path = required(request.normalizedPath(), "Normalized path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Normalized path must begin with /");
        }
        String key = required(request.idempotencyKey(), "Idempotency key");
        if (key.length() > 120) {
            throw new ApiException(ApiErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key must not exceed 120 characters.");
        }
        return new CommandRequest(
                request.actorId(), method, path, key, required(request.requestHash(), "Request hash"),
                request.ifMatch());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static Scope scope(CommandRequest request) {
        byte[] digest = sha256(request.actorId() + "\n" + request.method() + "\n" + request.normalizedPath());
        return new Scope(HexFormat.of().formatHex(digest), digest);
    }

    public static String requestHash(byte[] canonicalBody, String ifMatch) {
        MessageDigest digest = sha256Digest();
        digest.update(canonicalBody == null ? new byte[0] : canonicalBody);
        digest.update((byte) '\n');
        digest.update((ifMatch == null ? "" : ifMatch).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] sha256(String value) {
        return sha256Digest().digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CommandResponse response(Record record) {
        return new CommandResponse(record.responseStatus(), record.responseContentType(), record.responseBody());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static TransactionTemplate required(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    public record CommandRequest(
            UUID actorId,
            String method,
            String normalizedPath,
            String idempotencyKey,
            String requestHash,
            String ifMatch) {}

    public record CommandResponse(int status, String contentType, byte[] body) {
        public CommandResponse {
            body = body == null ? null : body.clone();
        }

        @Override
        public byte[] body() {
            return body == null ? null : body.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CommandResponse response
                    && status == response.status
                    && java.util.Objects.equals(contentType, response.contentType)
                    && java.util.Arrays.equals(body, response.body);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(status, contentType, java.util.Arrays.hashCode(body));
        }
    }

    public record CommandResult(CommandResponse response, boolean terminal) {
        public static CommandResult success(CommandResponse response) {
            return new CommandResult(response, false);
        }

        public static CommandResult terminal(CommandResponse response) {
            return new CommandResult(response, true);
        }
    }

    private record Scope(String text, byte[] digest) {}

    private record Claim(UUID id, UUID owner, long generation, String requestHash, CommandResponse replay) {}
}
