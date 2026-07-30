package com.company.erp.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.company.erp.identity.api.AdminRequests.PermissionOverrideRequest;
import com.company.erp.identity.api.AdminRequests.UserCreateRequest;
import com.company.erp.identity.api.AdminRequests.UserProfileUpdateRequest;
import com.company.erp.identity.api.AdminResponses.PermissionOverrideResponse;
import com.company.erp.identity.api.AdminResponses.UserResponse;
import com.company.erp.identity.application.AdminQuery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserJdbcRepository {

    private static final UUID MANAGE_USERS_ID = UUID.fromString("30000000-0000-0000-0000-000000000043");
    private static final UUID MANAGE_ROLES_ID = UUID.fromString("30000000-0000-0000-0000-000000000044");

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "loginId", "lower(btrim(u.login_id))",
            "name", "lower(u.name)",
            "status", "u.status",
            "id", "u.id");
    private static final List<String> DEFAULT_SORT = List.of("loginId,asc", "id,asc");

    private final JdbcClient jdbc;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public AdminUserJdbcRepository(
            JdbcClient jdbc,
            tools.jackson.databind.ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<UserResponse> find(UUID userId) {
        return jdbc.sql(baseSelect() + " WHERE u.id = :userId AND u.kind IN ('USER', 'STAFF')" + groupBy())
                .param("userId", userId)
                .query((resultSet, rowNum) -> mapUser(resultSet))
                .optional();
    }

    public List<UserResponse> list(
            int page,
            int size,
            String status,
            String kind,
            String loginId,
            String name,
            List<String> sort) {
        return jdbc.sql(baseSelect() + filters() + " ORDER BY "
                        + AdminQuery.orderBy(sort, SORT_COLUMNS) + " LIMIT :limit OFFSET :offset")
                .param("status", status)
                .param("kind", kind)
                .param("loginId", blankToNull(loginId))
                .param("name", blankToNull(name))
                .param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((resultSet, rowNum) -> mapUser(resultSet))
                .list();
    }

    public long count(String status, String kind, String loginId, String name) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM identity.app_user u
                        WHERE u.kind IN ('USER', 'STAFF')
                          AND (CAST(:status AS varchar) IS NULL OR u.status = :status)
                          AND (CAST(:kind AS varchar) IS NULL OR u.kind = :kind)
                          AND (CAST(:loginId AS varchar) IS NULL
                               OR lower(btrim(u.login_id)) = lower(btrim(:loginId)))
                          AND (CAST(:name AS varchar) IS NULL
                               OR lower(u.name) LIKE '%' || lower(:name) || '%')
                        """)
                .param("status", status)
                .param("kind", kind)
                .param("loginId", blankToNull(loginId))
                .param("name", blankToNull(name))
                .query(Long.class)
                .single();
    }

    public List<String> normalizeSort(List<String> requested) {
        return AdminQuery.sort(requested, SORT_COLUMNS, DEFAULT_SORT);
    }

    public UUID create(UserCreateRequest request, String passwordHash, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, group_name, division, position,
                            name, sex, phone, email, remark, status, must_change_password,
                            password_generation, created_by, updated_by
                        ) VALUES (
                            :id, :kind, :loginId, :passwordHash, :groupName, :division, :position,
                            :name, :sex, :phone, :email, :remark, 'ACTIVE', true,
                            1, :actorUserId, :actorUserId
                        )
                        """)
                .param("id", id)
                .param("kind", request.kind())
                .param("loginId", request.loginId().trim())
                .param("passwordHash", passwordHash)
                .param("groupName", blankToNull(request.groupName()))
                .param("division", blankToNull(request.division()))
                .param("position", request.position().trim())
                .param("name", request.name().trim())
                .param("sex", request.sex())
                .param("phone", blankToNull(request.phone()))
                .param("email", blankToNull(request.email()))
                .param("remark", blankToNull(request.remark()))
                .param("actorUserId", actorUserId)
                .update();
        return id;
    }

    public int updateProfile(UUID userId, long version, UserProfileUpdateRequest request, UUID actorUserId) {
        return jdbc.sql("""
                        UPDATE identity.app_user
                        SET group_name = :groupName,
                            division = :division,
                            position = :position,
                            name = :name,
                            sex = :sex,
                            phone = :phone,
                            email = :email,
                            remark = :remark,
                            version = version + 1,
                            updated_by = :actorUserId
                        WHERE id = :userId AND kind IN ('USER', 'STAFF') AND version = :version
                        """)
                .param("groupName", blankToNull(request.groupName()))
                .param("division", blankToNull(request.division()))
                .param("position", request.position().trim())
                .param("name", request.name().trim())
                .param("sex", request.sex())
                .param("phone", blankToNull(request.phone()))
                .param("email", blankToNull(request.email()))
                .param("remark", blankToNull(request.remark()))
                .param("actorUserId", actorUserId)
                .param("userId", userId)
                .param("version", version)
                .update();
    }

    public int updateStatus(UUID userId, long version, String status, UUID actorUserId) {
        return jdbc.sql("""
                        UPDATE identity.app_user
                        SET status = :status, version = version + 1, updated_by = :actorUserId
                        WHERE id = :userId AND kind IN ('USER', 'STAFF') AND version = :version
                        """)
                .param("status", status)
                .param("actorUserId", actorUserId)
                .param("userId", userId)
                .param("version", version)
                .update();
    }

    public int touchVersion(UUID userId, long version, UUID actorUserId) {
        return jdbc.sql("""
                        UPDATE identity.app_user
                        SET version = version + 1, updated_by = :actorUserId
                        WHERE id = :userId AND kind IN ('USER', 'STAFF') AND version = :version
                        """)
                .param("actorUserId", actorUserId)
                .param("userId", userId)
                .param("version", version)
                .update();
    }

    public boolean rolesExist(List<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return true;
        }
        long count = jdbc.sql("SELECT count(*) FROM identity.app_role WHERE id IN (:ids)")
                .param("ids", roleIds.stream().distinct().toList())
                .query(Long.class)
                .single();
        return count == roleIds.stream().distinct().count();
    }

    public void replaceRoles(UUID userId, List<UUID> roleIds, UUID actorUserId) {
        jdbc.sql("DELETE FROM identity.user_role WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        for (UUID roleId : roleIds.stream().distinct().toList()) {
            jdbc.sql("""
                            INSERT INTO identity.user_role (user_id, role_id, assigned_by)
                            VALUES (:userId, :roleId, :actorUserId)
                            """)
                    .param("userId", userId)
                    .param("roleId", roleId)
                    .param("actorUserId", actorUserId)
                    .update();
        }
    }

    public boolean activePermissionsExist(List<UUID> permissionIds) {
        if (permissionIds.isEmpty()) {
            return true;
        }
        long count = jdbc.sql("SELECT count(*) FROM identity.permission WHERE id IN (:ids) AND active = true")
                .param("ids", permissionIds.stream().distinct().toList())
                .query(Long.class)
                .single();
        return count == permissionIds.stream().distinct().count();
    }

    public void replaceOverrides(UUID userId, List<PermissionOverrideRequest> overrides, UUID actorUserId) {
        jdbc.sql("DELETE FROM identity.user_permission_override WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        for (PermissionOverrideRequest override : overrides) {
            jdbc.sql("""
                            INSERT INTO identity.user_permission_override (
                                user_id, permission_id, effect, reason, updated_by
                            ) VALUES (
                                :userId, :permissionId, :effect, :reason, :actorUserId
                            )
                            """)
                    .param("userId", userId)
                    .param("permissionId", override.permissionId())
                    .param("effect", override.effect())
                    .param("reason", blankToNull(override.reason()))
                    .param("actorUserId", actorUserId)
                    .update();
        }
    }

    public int resetPassword(
            UUID userId,
            long version,
            String passwordHash,
            Instant instant,
            UUID actorUserId) {
        return jdbc.sql("""
                        UPDATE identity.app_user
                        SET password_hash = :passwordHash,
                            password_changed_at = :now,
                            password_generation = password_generation + 1,
                            must_change_password = true,
                            version = version + 1,
                            updated_by = :actorUserId
                        WHERE id = :userId AND kind IN ('USER', 'STAFF') AND version = :version
                        """)
                .param("passwordHash", passwordHash)
                .param("now", OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC))
                .param("actorUserId", actorUserId)
                .param("userId", userId)
                .param("version", version)
                .update();
    }

    public void lockRecoveryState() {
        jdbc.sql("SELECT pg_advisory_xact_lock(724026043001)")
                .query((resultSet, rowNum) -> true)
                .single();
        jdbc.sql("""
                        SELECT u.id
                        FROM identity.app_user u
                        WHERE u.kind IN ('USER', 'STAFF')
                        ORDER BY u.id
                        FOR UPDATE
                        """).query(UUID.class).list();
        jdbc.sql("SELECT id FROM identity.app_role ORDER BY id FOR UPDATE")
                .query(UUID.class).list();
    }

    public long recoveryCapableCount() {
        return jdbc.sql("""
                        WITH role_grants AS (
                            SELECT ur.user_id, rp.permission_id
                            FROM identity.user_role ur
                            JOIN identity.app_role r ON r.id = ur.role_id AND r.active = true
                            JOIN identity.role_permission rp ON rp.role_id = r.id
                        ), effective AS (
                            SELECT u.id AS user_id, p.id AS permission_id,
                                   CASE
                                       WHEN o.effect = 'DENY' THEN false
                                       WHEN o.effect = 'ALLOW' THEN true
                                       ELSE EXISTS (
                                           SELECT 1 FROM role_grants rg
                                           WHERE rg.user_id = u.id AND rg.permission_id = p.id
                                       )
                                   END AS granted
                            FROM identity.app_user u
                            CROSS JOIN identity.permission p
                            LEFT JOIN identity.user_permission_override o
                              ON o.user_id = u.id AND o.permission_id = p.id
                            WHERE u.kind IN ('USER', 'STAFF')
                              AND u.status = 'ACTIVE'
                              AND u.must_change_password = false
                              AND btrim(u.password_hash) <> ''
                              AND p.active = true
                              AND p.id IN (:manageUsersId, :manageRolesId)
                        )
                        SELECT count(*)
                        FROM (
                            SELECT user_id
                            FROM effective
                            GROUP BY user_id
                            HAVING count(*) FILTER (WHERE granted) = 2
                        ) capable
                        """)
                .param("manageUsersId", MANAGE_USERS_ID)
                .param("manageRolesId", MANAGE_ROLES_ID)
                .query(Long.class)
                .single();
    }

    private static String baseSelect() {
        return """
                SELECT u.id, u.version, u.status, u.kind, u.login_id, u.name, u.position,
                       u.group_name, u.division, u.sex, u.phone, u.email, u.remark,
                       u.must_change_password, u.created_at, u.updated_at,
                       COALESCE(array_agg(DISTINCT ur.role_id)
                           FILTER (WHERE ur.role_id IS NOT NULL), ARRAY[]::uuid[]) role_ids,
                       COALESCE(
                           jsonb_agg(DISTINCT jsonb_build_object(
                               'permissionId', o.permission_id,
                               'module', p.module_code,
                               'action', p.action_code,
                               'effect', o.effect
                           )) FILTER (WHERE o.permission_id IS NOT NULL),
                           '[]'::jsonb
                       )::text permission_overrides
                FROM identity.app_user u
                LEFT JOIN identity.user_role ur ON ur.user_id = u.id
                LEFT JOIN identity.user_permission_override o ON o.user_id = u.id
                LEFT JOIN identity.permission p ON p.id = o.permission_id
                """;
    }

    private static String filters() {
        return """
                 WHERE u.kind IN ('USER', 'STAFF')
                   AND (CAST(:status AS varchar) IS NULL OR u.status = :status)
                   AND (CAST(:kind AS varchar) IS NULL OR u.kind = :kind)
                   AND (CAST(:loginId AS varchar) IS NULL
                        OR lower(btrim(u.login_id)) = lower(btrim(:loginId)))
                   AND (CAST(:name AS varchar) IS NULL
                        OR lower(u.name) LIKE '%' || lower(:name) || '%')
                """ + groupBy();
    }

    private static String groupBy() {
        return """
                 GROUP BY u.id, u.version, u.status, u.kind, u.login_id, u.name, u.position,
                          u.group_name, u.division, u.sex, u.phone, u.email, u.remark,
                          u.must_change_password, u.created_at, u.updated_at
                """;
    }

    private UserResponse mapUser(ResultSet resultSet) throws SQLException {
        UUID userId = resultSet.getObject("id", UUID.class);
        java.sql.Array roleArray = resultSet.getArray("role_ids");
        Object[] rawRoles = roleArray == null ? new Object[0] : (Object[]) roleArray.getArray();
        List<UUID> roles = java.util.Arrays.stream(rawRoles)
                .map(value -> value instanceof UUID uuid ? uuid : UUID.fromString(value.toString()))
                .sorted()
                .toList();
        return new UserResponse(
                userId,
                resultSet.getLong("version"),
                resultSet.getString("status"),
                resultSet.getString("kind"),
                resultSet.getString("login_id"),
                resultSet.getString("name"),
                resultSet.getString("position"),
                resultSet.getString("group_name"),
                resultSet.getString("division"),
                resultSet.getString("sex"),
                resultSet.getString("phone"),
                resultSet.getString("email"),
                resultSet.getString("remark"),
                roles,
                permissionOverrides(resultSet.getString("permission_overrides")),
                resultSet.getBoolean("must_change_password"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private List<PermissionOverrideResponse> permissionOverrides(String value) {
        try {
            tools.jackson.databind.JsonNode entries = objectMapper.readTree(value);
            List<PermissionOverrideResponse> overrides = new java.util.ArrayList<>();
            for (tools.jackson.databind.JsonNode entry : entries) {
                overrides.add(new PermissionOverrideResponse(
                        UUID.fromString(entry.get("permissionId").asText()),
                        entry.get("module").asText(),
                        entry.get("action").asText(),
                        entry.get("effect").asText()));
            }
            return overrides.stream()
                    .sorted(java.util.Comparator
                            .comparing(PermissionOverrideResponse::module)
                            .thenComparing(PermissionOverrideResponse::action)
                            .thenComparing(PermissionOverrideResponse::permissionId))
                    .toList();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not map permission overrides", exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
