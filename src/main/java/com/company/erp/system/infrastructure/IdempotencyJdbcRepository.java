package com.company.erp.system.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyJdbcRepository {

    private final JdbcClient jdbc;

    public IdempotencyJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertClaim(NewClaim claim) {
        return jdbc.sql("""
                        INSERT INTO system.idempotency_record (
                            id, scope, idempotency_key, request_hash, status, expires_at,
                            scope_digest, actor_user_id, request_method, normalized_path, command_id,
                            lease_owner, lease_generation, lease_expires_at
                        ) VALUES (
                            :id, :scope, :key, :requestHash, 'IN_PROGRESS', :expiresAt,
                            :scopeDigest, :actorId, :method, :path, :commandId,
                            :leaseOwner, 1, :leaseExpiresAt
                        )
                        ON CONFLICT (scope, idempotency_key) DO NOTHING
                        """)
                .param("id", claim.id())
                .param("scope", claim.scope())
                .param("key", claim.key())
                .param("requestHash", claim.requestHash())
                .param("expiresAt", claim.expiresAt())
                .param("scopeDigest", claim.scopeDigest())
                .param("actorId", claim.actorId())
                .param("method", claim.method())
                .param("path", claim.path())
                .param("commandId", claim.commandId())
                .param("leaseOwner", claim.leaseOwner())
                .param("leaseExpiresAt", claim.leaseExpiresAt())
                .update() == 1;
    }

    public Optional<Record> findByScopeAndKeyForUpdate(String scope, String key) {
        return jdbc.sql(select() + " WHERE scope = :scope AND idempotency_key = :key FOR UPDATE")
                .param("scope", scope)
                .param("key", key)
                .query(IdempotencyJdbcRepository::map)
                .optional();
    }

    public Optional<Record> findByIdForUpdate(UUID id) {
        return jdbc.sql(select() + " WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(IdempotencyJdbcRepository::map)
                .optional();
    }

    public boolean reclaim(
            UUID id,
            String requestHash,
            UUID leaseOwner,
            long expectedGeneration,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime expiresAt) {
        return jdbc.sql("""
                        UPDATE system.idempotency_record
                        SET status = 'IN_PROGRESS', lease_owner = :leaseOwner,
                            lease_generation = lease_generation + 1,
                            lease_expires_at = :leaseExpiresAt, expires_at = :expiresAt
                        WHERE id = :id AND request_hash = :requestHash
                          AND lease_generation = :generation
                          AND status IN ('IN_PROGRESS', 'RETRYABLE')
                        """)
                .param("leaseOwner", leaseOwner)
                .param("leaseExpiresAt", leaseExpiresAt)
                .param("expiresAt", expiresAt)
                .param("id", id)
                .param("requestHash", requestHash)
                .param("generation", expectedGeneration)
                .update() == 1;
    }

    public boolean complete(
            UUID id,
            UUID leaseOwner,
            long leaseGeneration,
            int responseStatus,
            String responseContentType,
            byte[] responseBody,
            OffsetDateTime completedAt) {
        return jdbc.sql("""
                        UPDATE system.idempotency_record
                        SET status = 'COMPLETED', response_status = :responseStatus,
                            response_content_type = :responseContentType, response_body = :responseBody,
                            completed_at = :completedAt, lease_owner = NULL, lease_expires_at = NULL
                        WHERE id = :id AND status = 'IN_PROGRESS'
                          AND lease_owner = :leaseOwner AND lease_generation = :leaseGeneration
                        """)
                .param("responseStatus", responseStatus)
                .param("responseContentType", responseContentType)
                .param("responseBody", responseBody)
                .param("completedAt", completedAt)
                .param("id", id)
                .param("leaseOwner", leaseOwner)
                .param("leaseGeneration", leaseGeneration)
                .update() == 1;
    }

    public boolean releaseForRetry(UUID id, UUID leaseOwner, long leaseGeneration) {
        return jdbc.sql("""
                        UPDATE system.idempotency_record
                        SET status = 'RETRYABLE', lease_owner = NULL, lease_expires_at = NULL
                        WHERE id = :id AND status = 'IN_PROGRESS'
                          AND lease_owner = :leaseOwner AND lease_generation = :leaseGeneration
                        """)
                .param("id", id)
                .param("leaseOwner", leaseOwner)
                .param("leaseGeneration", leaseGeneration)
                .update() == 1;
    }

    /**
     * The caller decides expiry against its own clock, so the delete must use that same instant.
     * Falling back to the database clock lets host skew leave the row in place forever.
     */
    public int deleteExpired(UUID id, OffsetDateTime expiredAt) {
        return jdbc.sql("DELETE FROM system.idempotency_record WHERE id = :id AND expires_at <= :expiredAt")
                .param("id", id)
                .param("expiredAt", expiredAt)
                .update();
    }

    public int purgeExpired(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Purge limit must be between 1 and 1000");
        }
        return jdbc.sql("""
                        WITH expired AS (
                            SELECT id FROM system.idempotency_record
                            WHERE expires_at <= clock_timestamp()
                              AND status IN ('COMPLETED', 'QUARANTINED', 'RETRYABLE')
                            ORDER BY expires_at, id
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        )
                        DELETE FROM system.idempotency_record record
                        USING expired
                        WHERE record.id = expired.id
                        """)
                .param("limit", limit)
                .update();
    }

    private static String select() {
        return """
                SELECT id, scope, idempotency_key, request_hash, status, expires_at,
                       command_id, response_status, response_content_type, response_body, completed_at,
                       lease_owner, lease_generation, lease_expires_at
                FROM system.idempotency_record
                """;
    }

    private static Record map(ResultSet result, int ignored) throws SQLException {
        Integer responseStatus = result.getObject("response_status", Integer.class);
        return new Record(
                result.getObject("id", UUID.class),
                result.getString("scope"),
                result.getString("idempotency_key"),
                result.getString("request_hash"),
                result.getString("status"),
                result.getObject("expires_at", OffsetDateTime.class),
                result.getObject("command_id", UUID.class),
                responseStatus,
                result.getString("response_content_type"),
                result.getBytes("response_body"),
                result.getObject("completed_at", OffsetDateTime.class),
                result.getObject("lease_owner", UUID.class),
                result.getLong("lease_generation"),
                result.getObject("lease_expires_at", OffsetDateTime.class));
    }

    public record NewClaim(
            UUID id,
            String scope,
            byte[] scopeDigest,
            UUID actorId,
            String method,
            String path,
            String key,
            String requestHash,
            UUID commandId,
            UUID leaseOwner,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime expiresAt) {}

    public record Record(
            UUID id,
            String scope,
            String key,
            String requestHash,
            String status,
            OffsetDateTime expiresAt,
            UUID commandId,
            Integer responseStatus,
            String responseContentType,
            byte[] responseBody,
            OffsetDateTime completedAt,
            UUID leaseOwner,
            long leaseGeneration,
            OffsetDateTime leaseExpiresAt) {}
}
