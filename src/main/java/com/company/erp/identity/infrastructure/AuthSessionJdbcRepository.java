package com.company.erp.identity.infrastructure;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.company.erp.identity.domain.AuthSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuthSessionJdbcRepository {

    private final JdbcClient jdbc;

    public AuthSessionJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSession(AuthSession session) {
        jdbc.sql("""
                        INSERT INTO identity.auth_session (
                            id, user_id, absolute_expires_at, idle_expires_at,
                            created_at, last_refreshed_at, user_agent, client_ip
                        ) VALUES (
                            :id, :userId, :absoluteExpiresAt, :idleExpiresAt,
                            :createdAt, :lastRefreshedAt, :userAgent, CAST(:clientIp AS inet)
                        )
                        """)
                .param("id", session.id())
                .param("userId", session.userId())
                .param("absoluteExpiresAt", timestamp(session.absoluteExpiresAt()))
                .param("idleExpiresAt", timestamp(session.idleExpiresAt()))
                .param("createdAt", timestamp(session.createdAt()))
                .param("lastRefreshedAt", timestamp(session.lastRefreshedAt()))
                .param("userAgent", session.userAgent())
                .param("clientIp", session.clientIp())
                .update();
    }

    public void insertRefreshToken(
            UUID tokenId,
            UUID sessionId,
            byte[] tokenHash,
            UUID parentTokenId,
            Instant expiresAt,
            Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO identity.refresh_token (
                            id, auth_session_id, token_hash, parent_token_id,
                            status, expires_at, created_at
                        ) VALUES (
                            :id, :sessionId, :tokenHash, :parentTokenId,
                            'ACTIVE', :expiresAt, :createdAt
                        )
                        """)
                .param("id", tokenId)
                .param("sessionId", sessionId)
                .param("tokenHash", tokenHash)
                .param("parentTokenId", parentTokenId, Types.OTHER)
                .param("expiresAt", timestamp(expiresAt))
                .param("createdAt", timestamp(createdAt))
                .update();
    }

    public Optional<LockedRefreshToken> lockByHash(byte[] tokenHash) {
        return jdbc.sql("""
                        SELECT rt.id AS token_id, rt.auth_session_id, rt.status AS token_status,
                               rt.expires_at AS token_expires_at,
                               s.user_id, s.created_at, s.last_refreshed_at,
                               s.idle_expires_at, s.absolute_expires_at,
                               host(s.client_ip) AS client_ip, s.user_agent, s.revoked_at
                        FROM identity.refresh_token rt
                        JOIN identity.auth_session s ON s.id = rt.auth_session_id
                        WHERE rt.token_hash = :tokenHash
                        FOR UPDATE OF rt, s
                        """)
                .param("tokenHash", tokenHash)
                .query((resultSet, rowNum) -> new LockedRefreshToken(
                        resultSet.getObject("token_id", UUID.class),
                        resultSet.getObject("auth_session_id", UUID.class),
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("token_status"),
                        resultSet.getObject("token_expires_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("last_refreshed_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("idle_expires_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("absolute_expires_at", OffsetDateTime.class).toInstant(),
                        resultSet.getString("client_ip"),
                        resultSet.getString("user_agent"),
                        instant(resultSet.getObject("revoked_at", OffsetDateTime.class))))
                .optional();
    }

    public void markUsed(UUID tokenId, Instant instant) {
        jdbc.sql("""
                        UPDATE identity.refresh_token
                        SET status = 'USED', used_at = :now
                        WHERE id = :tokenId AND status = 'ACTIVE'
                        """)
                .param("now", timestamp(instant))
                .param("tokenId", tokenId)
                .update();
    }

    public void advanceIdleDeadline(UUID sessionId, Instant lastRefreshedAt, Instant idleExpiresAt) {
        jdbc.sql("""
                        UPDATE identity.auth_session
                        SET last_refreshed_at = :lastRefreshedAt,
                            idle_expires_at = :idleExpiresAt
                        WHERE id = :sessionId AND revoked_at IS NULL
                        """)
                .param("lastRefreshedAt", timestamp(lastRefreshedAt))
                .param("idleExpiresAt", timestamp(idleExpiresAt))
                .param("sessionId", sessionId)
                .update();
    }

    public void revokeFamily(UUID sessionId, Instant instant, String reason) {
        jdbc.sql("""
                        UPDATE identity.auth_session
                        SET revoked_at = COALESCE(revoked_at, :now),
                            revoked_reason = COALESCE(revoked_reason, :reason)
                        WHERE id = :sessionId
                        """)
                .param("now", timestamp(instant))
                .param("reason", reason)
                .param("sessionId", sessionId)
                .update();
        jdbc.sql("""
                        UPDATE identity.refresh_token
                        SET status = 'REVOKED', revoked_at = :now
                        WHERE auth_session_id = :sessionId AND status = 'ACTIVE'
                        """)
                .param("now", timestamp(instant))
                .param("sessionId", sessionId)
                .update();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record LockedRefreshToken(
            UUID tokenId,
            UUID sessionId,
            UUID userId,
            String status,
            Instant tokenExpiresAt,
            Instant createdAt,
            Instant lastRefreshedAt,
            Instant idleExpiresAt,
            Instant absoluteExpiresAt,
            String clientIp,
            String userAgent,
            Instant revokedAt) {

        public AuthSession session(Instant nextIdleDeadline) {
            return new AuthSession(
                    sessionId,
                    userId,
                    createdAt,
                    lastRefreshedAt,
                    nextIdleDeadline,
                    absoluteExpiresAt,
                    clientIp,
                    userAgent,
                    true,
                    revokedAt);
        }
    }
}
