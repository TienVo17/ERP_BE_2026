package com.company.erp.identity.infrastructure;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.company.erp.identity.api.AuthResponses.PermissionResponse;
import com.company.erp.identity.application.SessionSort;
import com.company.erp.identity.domain.AppUser;
import com.company.erp.identity.domain.AuthSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityJdbcRepository {

    public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID ADMIN_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private final JdbcClient jdbc;

    public IdentityJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByCanonicalLoginId(String loginId) {
        return jdbc.sql("""
                        SELECT id, kind, login_id, password_hash, name, status,
                               must_change_password, password_generation
                        FROM identity.app_user
                        WHERE lower(btrim(login_id)) = lower(btrim(:loginId))
                          AND kind IN ('USER', 'STAFF')
                        """)
                .param("loginId", loginId)
                .query((resultSet, rowNum) -> mapUser(resultSet))
                .optional();
    }

    public Optional<AppUser> findById(UUID userId) {
        return jdbc.sql("""
                        SELECT id, kind, login_id, password_hash, name, status,
                               must_change_password, password_generation
                        FROM identity.app_user
                        WHERE id = :userId
                        """)
                .param("userId", userId)
                .query((resultSet, rowNum) -> mapUser(resultSet))
                .optional();
    }

    public boolean bootstrapAdminExists() {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM identity.user_role ur
                            WHERE ur.role_id = :adminRoleId
                              AND ur.assigned_by = :systemUserId
                              AND ur.user_id <> :systemUserId
                        )
                        """)
                .param("adminRoleId", ADMIN_ROLE_ID)
                .param("systemUserId", SYSTEM_USER_ID)
                .query(Boolean.class)
                .single();
    }

    public void createBootstrapAdmin(UUID userId, String loginId, String name, String passwordHash) {
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status,
                            must_change_password, password_generation,
                            created_by, updated_by
                        ) VALUES (
                            :id, 'USER', :loginId, :passwordHash, 'ADMINISTRATOR', :name, 'ACTIVE',
                            true, 1, :systemUserId, :systemUserId
                        )
                        """)
                .param("id", userId)
                .param("loginId", loginId.trim())
                .param("passwordHash", passwordHash)
                .param("name", name.trim())
                .param("systemUserId", SYSTEM_USER_ID)
                .update();
        jdbc.sql("""
                        INSERT INTO identity.user_role (user_id, role_id, assigned_by)
                        VALUES (:userId, :roleId, :systemUserId)
                        """)
                .param("userId", userId)
                .param("roleId", ADMIN_ROLE_ID)
                .param("systemUserId", SYSTEM_USER_ID)
                .update();
    }

    public Set<String> roleCodes(UUID userId) {
        return jdbc.sql("""
                        SELECT r.code
                        FROM identity.user_role ur
                        JOIN identity.app_role r ON r.id = ur.role_id
                        WHERE ur.user_id = :userId AND r.active = true
                        ORDER BY r.code
                        """)
                .param("userId", userId)
                .query(String.class)
                .set();
    }

    public List<PermissionResponse> effectivePermissions(UUID userId) {
        return jdbc.sql("""
                        WITH role_grants AS (
                            SELECT DISTINCT rp.permission_id
                            FROM identity.user_role ur
                            JOIN identity.app_role r ON r.id = ur.role_id AND r.active = true
                            JOIN identity.role_permission rp ON rp.role_id = ur.role_id
                            WHERE ur.user_id = :userId
                        )
                        SELECT p.id, p.module_code, p.action_code, p.description, p.active
                        FROM identity.permission p
                        LEFT JOIN role_grants rg ON rg.permission_id = p.id
                        LEFT JOIN identity.user_permission_override uo
                          ON uo.user_id = :userId AND uo.permission_id = p.id
                        WHERE p.active = true
                          AND CASE
                              WHEN uo.effect = 'DENY' THEN false
                              WHEN uo.effect = 'ALLOW' THEN true
                              ELSE rg.permission_id IS NOT NULL
                          END
                        ORDER BY p.module_code, p.action_code, p.id
                        """)
                .param("userId", userId)
                .query((resultSet, rowNum) -> new PermissionResponse(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("module_code"),
                        resultSet.getString("action_code"),
                        resultSet.getString("description"),
                        resultSet.getBoolean("active")))
                .list();
    }

    public Optional<AuthSession> findActiveSession(UUID sessionId, UUID userId, Instant instant) {
        return jdbc.sql("""
                        SELECT id, user_id, created_at, last_refreshed_at, idle_expires_at,
                               absolute_expires_at, host(client_ip) AS client_ip, user_agent, revoked_at
                        FROM identity.auth_session
                        WHERE id = :sessionId
                          AND user_id = :userId
                          AND revoked_at IS NULL
                          AND idle_expires_at > :now
                          AND absolute_expires_at > :now
                        """)
                .param("sessionId", sessionId)
                .param("userId", userId)
                .param("now", OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                .query((resultSet, rowNum) -> mapSession(resultSet, sessionId))
                .optional();
    }

    /**
     * Finds a live session the caller owns. Revoked and expired rows are excluded so a caller
     * cannot "revoke" a session that already ended and receive a success response.
     */
    public Optional<AuthSession> findOwnedSession(
            UUID sessionId,
            UUID userId,
            UUID currentSessionId,
            Instant instant) {
        return jdbc.sql("""
                        SELECT id, user_id, created_at, last_refreshed_at, idle_expires_at,
                               absolute_expires_at, host(client_ip) AS client_ip, user_agent, revoked_at
                        FROM identity.auth_session
                        WHERE id = :sessionId
                          AND user_id = :userId
                          AND revoked_at IS NULL
                          AND idle_expires_at > :now
                          AND absolute_expires_at > :now
                        """)
                .param("sessionId", sessionId)
                .param("userId", userId)
                .param("now", OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                .query((resultSet, rowNum) -> mapSession(resultSet, currentSessionId))
                .optional();
    }

    /**
     * Lists the caller's simultaneously live sessions. Ended sessions are excluded because the
     * frozen session response carries no lifecycle field to tell them apart.
     */
    public List<AuthSession> listSessions(
            UUID userId,
            int page,
            int size,
            UUID currentSessionId,
            Instant instant,
            List<String> normalizedSort) {
        // The ORDER BY text is built only from allowlisted column names in SessionSort.
        return jdbc.sql("""
                        SELECT id, user_id, created_at, last_refreshed_at, idle_expires_at,
                               absolute_expires_at, host(client_ip) AS client_ip, user_agent, revoked_at
                        FROM identity.auth_session
                        WHERE user_id = :userId
                          AND revoked_at IS NULL
                          AND idle_expires_at > :now
                          AND absolute_expires_at > :now
                        ORDER BY %s
                        LIMIT :limit OFFSET :offset
                        """.formatted(SessionSort.toOrderByClause(normalizedSort)))
                .param("userId", userId)
                .param("now", OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                .param("limit", size)
                .param("offset", page * size)
                .query((resultSet, rowNum) -> mapSession(resultSet, currentSessionId))
                .list();
    }

    public long countSessions(UUID userId, Instant instant) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM identity.auth_session
                        WHERE user_id = :userId
                          AND revoked_at IS NULL
                          AND idle_expires_at > :now
                          AND absolute_expires_at > :now
                        """)
                .param("userId", userId)
                .param("now", OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    public boolean revokeOwnedSession(UUID sessionId, UUID userId, Instant instant, String reason) {
        int updated = jdbc.sql("""
                        UPDATE identity.auth_session
                        SET revoked_at = :now,
                            revoked_reason = :reason
                        WHERE id = :sessionId AND user_id = :userId AND revoked_at IS NULL
                        """)
                .param("now", OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                .param("reason", reason)
                .param("sessionId", sessionId)
                .param("userId", userId)
                .update();
        if (updated > 0) {
            jdbc.sql("""
                            UPDATE identity.refresh_token
                            SET status = 'REVOKED', revoked_at = :now
                            WHERE auth_session_id = :sessionId AND status = 'ACTIVE'
                            """)
                    .param("now", OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                    .param("sessionId", sessionId)
                    .update();
        }
        return updated > 0;
    }

    /**
     * Revokes every still-live session of a user in two set-based statements, so the cost does not
     * grow with the number of historical sessions the user has accumulated.
     */
    public void revokeAllSessions(UUID userId, Instant instant, String reason) {
        OffsetDateTime now = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        jdbc.sql("""
                        UPDATE identity.refresh_token rt
                        SET status = 'REVOKED', revoked_at = :now
                        FROM identity.auth_session s
                        WHERE rt.auth_session_id = s.id
                          AND s.user_id = :userId
                          AND s.revoked_at IS NULL
                          AND rt.status = 'ACTIVE'
                        """)
                .param("now", now)
                .param("userId", userId)
                .update();
        jdbc.sql("""
                        UPDATE identity.auth_session
                        SET revoked_at = :now,
                            revoked_reason = :reason
                        WHERE user_id = :userId AND revoked_at IS NULL
                        """)
                .param("now", now)
                .param("reason", reason)
                .param("userId", userId)
                .update();
    }

    public int invalidatePasswordChallenge(UUID userId, long expectedGeneration) {
        return jdbc.sql("""
                        UPDATE identity.app_user
                        SET password_generation = password_generation + 1,
                            version = version + 1,
                            updated_by = :userId
                        WHERE id = :userId
                          AND password_generation = :expectedGeneration
                          AND must_change_password = true
                        """)
                .param("userId", userId)
                .param("expectedGeneration", expectedGeneration)
                .update();
    }

    public int changePassword(
            UUID userId,
            long expectedGeneration,
            String passwordHash,
            Instant instant) {
        return jdbc.sql("""
                        UPDATE identity.app_user
                        SET password_hash = :passwordHash,
                            password_changed_at = :now,
                            password_generation = password_generation + 1,
                            must_change_password = false,
                            version = version + 1,
                            updated_by = :userId
                        WHERE id = :userId
                          AND password_generation = :expectedGeneration
                          AND status = 'ACTIVE'
                        """)
                .param("passwordHash", passwordHash)
                .param("now", OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                .param("userId", userId)
                .param("expectedGeneration", expectedGeneration)
                .update();
    }

    public void recordLoginEvent(
            UUID userId,
            String loginId,
            String outcome,
            String clientIp,
            String userAgent) {
        jdbc.sql("""
                        INSERT INTO identity.login_event (
                            id, user_id, login_id_attempted, outcome, client_ip, user_agent
                        ) VALUES (
                            :id, :userId, :loginId, :outcome, CAST(:clientIp AS inet), :userAgent
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("userId", userId, Types.OTHER)
                .param("loginId", normalizeAttempt(loginId))
                .param("outcome", outcome)
                .param("clientIp", clientIp)
                .param("userAgent", truncate(userAgent, 500))
                .update();
    }

    private static AppUser mapUser(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new AppUser(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("kind"),
                resultSet.getString("login_id"),
                resultSet.getString("password_hash"),
                resultSet.getString("name"),
                resultSet.getString("status"),
                resultSet.getBoolean("must_change_password"),
                resultSet.getLong("password_generation"));
    }

    private static AuthSession mapSession(java.sql.ResultSet resultSet, UUID currentSessionId)
            throws java.sql.SQLException {
        UUID id = resultSet.getObject("id", UUID.class);
        return new AuthSession(
                id,
                resultSet.getObject("user_id", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("last_refreshed_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("idle_expires_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("absolute_expires_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("client_ip"),
                resultSet.getString("user_agent"),
                id.equals(currentSessionId),
                instant(resultSet.getObject("revoked_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String normalizeAttempt(String loginId) {
        String value = loginId == null ? "[blank]" : loginId.trim();
        return truncate(value.isBlank() ? "[blank]" : value, 100);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
