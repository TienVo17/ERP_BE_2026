package com.company.erp.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.erp.api.ApiException;
import com.company.erp.api.ApiErrorCode;
import com.company.erp.support.PostgresTestConfiguration;
import com.company.erp.system.application.DocumentNumberAllocator;
import com.company.erp.system.application.DocumentType;
import com.company.erp.system.application.IdempotencyService;
import com.company.erp.system.application.IdempotencyService.CommandRequest;
import com.company.erp.system.application.IdempotencyService.CommandResponse;
import com.company.erp.system.application.IdempotencyService.CommandResult;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class IdempotencyServiceIT {

    @Autowired
    private IdempotencyService service;

    @Autowired
    private DocumentNumberAllocator numbers;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void replaysTheExactCompletedResponseWithoutExecutingAgain() {
        UUID actor = createActor();
        CommandRequest request = request(actor, "create-order", "hash-a");
        AtomicInteger executions = new AtomicInteger();

        CommandResponse first = service.execute(request, () -> {
            executions.incrementAndGet();
            String number = numbers.next(DocumentType.SALES_ORDER, 2036);
            return CommandResult.success(json(201, "{\"sysPoNo\":\"" + number + "\"}"));
        });
        CommandResponse replay = service.execute(request, () -> {
            throw new AssertionError("completed command executed again");
        });

        assertThat(replay).isEqualTo(first);
        assertThat(executions).hasValue(1);
    }

    @Test
    void passesThePersistedTrustedCommandContextOnlyToTheInitialExecution() {
        UUID actor = createActor();
        CommandRequest request = request(actor, "trusted-context", "hash-context");
        AtomicInteger executions = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<UUID> commandId = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<byte[]> scopeDigest = new java.util.concurrent.atomic.AtomicReference<>();

        CommandResponse first = service.execute(request, context -> {
            executions.incrementAndGet();
            commandId.set(context.commandId());
            scopeDigest.set(context.scopeDigest());
            byte[] mutated = context.scopeDigest();
            mutated[0] ^= 1;
            assertThat(context.scopeDigest()).isNotEqualTo(mutated);
            return CommandResult.success(json(201, "{\"created\":true}"));
        });
        CommandResponse replay = service.execute(request, context -> {
            throw new AssertionError("replayed command executed with context " + context.commandId());
        });

        var persisted = jdbc.sql("""
                        SELECT command_id, scope_digest
                        FROM system.idempotency_record
                        WHERE idempotency_key = 'trusted-context'
                        """).query((rs, ignored) -> java.util.Map.entry(
                                rs.getObject(1, UUID.class), rs.getBytes(2))).single();
        assertThat(commandId.get()).isEqualTo(persisted.getKey());
        assertThat(scopeDigest.get()).hasSize(32).isEqualTo(persisted.getValue());
        assertThat(replay).isEqualTo(first);
        assertThat(executions).hasValue(1);
    }

    @Test
    void purgesAnExpiredCompletedResultBeforeReusingTheKey() {
        UUID actor = createActor();
        CommandRequest request = request(actor, "completed-expired", "hash-expired");
        service.execute(request, () -> CommandResult.success(json(201, "{\"generation\":1}")));
        jdbc.sql("""
                        UPDATE system.idempotency_record
                        SET created_at = clock_timestamp() - interval '25 hours',
                            expires_at = clock_timestamp() - interval '1 hour'
                        WHERE idempotency_key = 'completed-expired'
                        """).update();

        CommandResponse fresh = service.execute(request,
                () -> CommandResult.success(json(201, "{\"generation\":2}")));

        assertThat(new String(fresh.body(), StandardCharsets.UTF_8))
                .isEqualTo("{\"generation\":2}");
        assertThat(jdbc.sql("SELECT count(*) FROM system.idempotency_record WHERE idempotency_key = 'completed-expired'")
                .query(Integer.class).single()).isOne();
    }

    @Test
    void rejectsTheSameScopedKeyWithAnotherHash() {
        UUID actor = createActor();
        service.execute(request(actor, "same-key", "hash-a"),
                () -> CommandResult.success(json(201, "{}")));

        assertThatThrownBy(() -> service.execute(request(actor, "same-key", "hash-b"),
                () -> CommandResult.success(json(201, "{}"))))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).errorCode())
                        .isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void scopesTheSameClientKeyByActorMethodAndNormalizedPath() {
        UUID firstActor = createActor();
        UUID secondActor = createActor();
        AtomicInteger executions = new AtomicInteger();

        for (CommandRequest request : java.util.List.of(
                request(firstActor, "shared", "hash-a"),
                new CommandRequest(firstActor, "PUT", "/api/v1/buyer-orders/one", "shared", "hash-a", "\"0\""),
                request(secondActor, "shared", "hash-a"))) {
            service.execute(request, () -> {
                executions.incrementAndGet();
                return CommandResult.success(json(200, "{}"));
            });
        }

        assertThat(executions).hasValue(3);
    }

    @Test
    void rollsBackDomainEffectsBeforePersistingAnExpectedTerminalProblem() {
        UUID actor = createActor();
        CommandRequest request = request(actor, "terminal", "hash-terminal");

        CommandResponse first = service.execute(request, () -> {
            numbers.next(DocumentType.DELIVERY, 2037);
            return CommandResult.terminal(json(409, "{\"code\":\"INSUFFICIENT_STOCK\"}"));
        });
        CommandResponse replay = service.execute(request, () -> {
            throw new AssertionError("terminal result executed again");
        });

        assertThat(first.status()).isEqualTo(409);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM system.document_number_counter WHERE document_type = 'DELIVERY' AND document_year = 2037")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void unexpectedFailureDoesNotCreateACompletedReplay() {
        UUID actor = createActor();
        CommandRequest request = request(actor, "retry-after-failure", "hash-failure");

        assertThatThrownBy(() -> service.execute(request, () -> {
            numbers.next(DocumentType.PRODUCTION, 2038);
            throw new IllegalStateException("unexpected");
        })).isInstanceOf(IllegalStateException.class);

        CommandResponse retry = service.execute(request,
                () -> CommandResult.success(json(201, "{\"retried\":true}")));
        assertThat(retry.status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT status FROM system.idempotency_record WHERE idempotency_key = 'retry-after-failure'")
                .query(String.class).single()).isEqualTo("COMPLETED");
    }

    /**
     * A migrated legacy row has no response to replay. While its original retention still stands the
     * caller is told the result is gone rather than handed a second execution of a command that
     * already ran once.
     */
    @Test
    void refusesToReplayAQuarantinedLegacyResultWhileItsRetentionStands() {
        UUID actor = createActor();
        CommandRequest request = request(actor, "legacy-live", "hash-legacy");
        service.execute(request, () -> CommandResult.success(json(201, "{}")));
        quarantine("legacy-live", "6 hours");

        assertThatThrownBy(() -> service.execute(request,
                () -> CommandResult.success(json(201, "{\"executed\":\"again\"}"))))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).errorCode())
                        .isEqualTo(ApiErrorCode.IDEMPOTENCY_RESULT_EXPIRED));
    }

    /** Once that retention has passed the key is purged and becomes usable again. */
    @Test
    void purgesAnExpiredQuarantinedRowAndLetsTheKeyBeUsedAgain() {
        UUID actor = createActor();
        CommandRequest request = request(actor, "legacy-expired", "hash-legacy");
        service.execute(request, () -> CommandResult.success(json(201, "{}")));
        quarantine("legacy-expired", "-1 hours");

        CommandResponse reused = service.execute(request,
                () -> CommandResult.success(json(201, "{\"executed\":\"fresh\"}")));

        assertThat(new String(reused.body(), StandardCharsets.UTF_8)).isEqualTo("{\"executed\":\"fresh\"}");
        assertThat(jdbc.sql("SELECT count(*) FROM system.idempotency_record WHERE idempotency_key = 'legacy-expired'")
                .query(Integer.class).single()).isOne();
    }

    @Test
    void storesDigestsAndBoundedPublicOutputButNoRawRequestBody() {
        UUID actor = createActor();
        service.execute(request(actor, "privacy", "body-sha-256-only"),
                () -> CommandResult.success(json(200, "{\"ok\":true}")));

        var row = jdbc.sql("""
                        SELECT octet_length(scope_digest), request_hash, response_content_type,
                               convert_from(response_body, 'UTF8')
                        FROM system.idempotency_record
                        WHERE idempotency_key = 'privacy'
                        """).query((rs, ignored) -> java.util.List.of(
                                rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4))).single();
        assertThat(row).containsExactly(32, "body-sha-256-only", "application/json", "{\"ok\":true}");
    }

    /** Reproduces what the V015 upgrade leaves behind: a terminal row with no replayable response. */
    private void quarantine(String key, String remainingRetention) {
        jdbc.sql("""
                        UPDATE system.idempotency_record
                        SET status = 'QUARANTINED', response_status = NULL, response_content_type = NULL,
                            response_body = NULL, completed_at = NULL, lease_owner = NULL,
                            lease_expires_at = NULL,
                            created_at = clock_timestamp() - interval '24 hours',
                            expires_at = clock_timestamp() + CAST(:retention AS interval)
                        WHERE idempotency_key = :key
                        """)
                .param("retention", remainingRetention)
                .param("key", key)
                .update();
    }

    private CommandRequest request(UUID actor, String key, String hash) {
        return new CommandRequest(actor, "POST", "/api/v1/buyer-orders", key, hash, null);
    }

    private static CommandResponse json(int status, String body) {
        return new CommandResponse(status, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private UUID createActor() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status
                        ) VALUES (:id, 'USER', :login, '{argon2}test-placeholder', 'QA', 'Idempotency Test', 'ACTIVE')
                        """).param("id", id).param("login", "idempotency-" + id).update();
        return id;
    }
}
