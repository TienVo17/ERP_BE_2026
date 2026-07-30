package com.company.erp.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.company.erp.identity.api.AdminRequests.RoleRequest;
import com.company.erp.identity.api.AdminRequests.RoleUpdateRequest;
import com.company.erp.identity.api.AdminResponses.RoleResponse;
import com.company.erp.identity.api.AuthResponses.PermissionResponse;
import com.company.erp.identity.application.AdminQuery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminRoleJdbcRepository {

    private static final Map<String, String> ROLE_SORT_COLUMNS = Map.of(
            "code", "r.code",
            "name", "lower(r.name)",
            "id", "r.id");
    private static final Map<String, String> PERMISSION_SORT_COLUMNS = Map.of(
            "module", "p.module_code",
            "action", "p.action_code",
            "id", "p.id");

    private final JdbcClient jdbc;

    public AdminRoleJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> normalizeRoleSort(List<String> requested) {
        return AdminQuery.sort(requested, ROLE_SORT_COLUMNS, List.of("code,asc", "id,asc"));
    }

    public List<String> normalizePermissionSort(List<String> requested) {
        return AdminQuery.sort(requested, PERMISSION_SORT_COLUMNS, List.of("module,asc", "action,asc", "id,asc"));
    }

    public Optional<RoleResponse> find(UUID roleId) {
        return jdbc.sql(baseRoleSelect() + " WHERE r.id = :roleId" + roleGroup())
                .param("roleId", roleId)
                .query((resultSet, rowNum) -> mapRole(resultSet))
                .optional();
    }

    public List<RoleResponse> list(int page, int size, Boolean active, List<String> sort) {
        return jdbc.sql(baseRoleSelect() + " WHERE (CAST(:active AS boolean) IS NULL OR r.active = :active)" + roleGroup()
                        + " ORDER BY " + AdminQuery.orderBy(sort, ROLE_SORT_COLUMNS)
                        + " LIMIT :limit OFFSET :offset")
                .param("active", active)
                .param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((resultSet, rowNum) -> mapRole(resultSet))
                .list();
    }

    public long countRoles(Boolean active) {
        return jdbc.sql("SELECT count(*) FROM identity.app_role WHERE (CAST(:active AS boolean) IS NULL OR active = :active)")
                .param("active", active)
                .query(Long.class)
                .single();
    }

    public UUID create(RoleRequest request, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.app_role (
                            id, code, name, description, active, created_by, updated_by
                        ) VALUES (
                            :id, :code, :name, :description, true, :actorUserId, :actorUserId
                        )
                        """)
                .param("id", id)
                .param("code", request.code().trim())
                .param("name", request.name().trim())
                .param("description", blankToNull(request.description()))
                .param("actorUserId", actorUserId)
                .update();
        replacePermissions(id, request.permissionIds(), actorUserId);
        return id;
    }

    public int update(UUID roleId, long version, RoleUpdateRequest request, UUID actorUserId) {
        return jdbc.sql("""
                        UPDATE identity.app_role
                        SET name = :name,
                            description = :description,
                            active = :active,
                            version = version + 1,
                            updated_by = :actorUserId
                        WHERE id = :roleId AND version = :version
                        """)
                .param("name", request.name().trim())
                .param("description", blankToNull(request.description()))
                .param("active", request.active())
                .param("actorUserId", actorUserId)
                .param("roleId", roleId)
                .param("version", version)
                .update();
    }

    public void replacePermissions(UUID roleId, List<UUID> permissionIds, UUID actorUserId) {
        jdbc.sql("DELETE FROM identity.role_permission WHERE role_id = :roleId")
                .param("roleId", roleId)
                .update();
        for (UUID permissionId : permissionIds.stream().distinct().toList()) {
            jdbc.sql("""
                            INSERT INTO identity.role_permission (role_id, permission_id, granted_by)
                            VALUES (:roleId, :permissionId, :actorUserId)
                            """)
                    .param("roleId", roleId)
                    .param("permissionId", permissionId)
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

    public List<PermissionResponse> listPermissions(
            int page,
            int size,
            String module,
            List<String> sort) {
        return jdbc.sql("""
                        SELECT p.id, p.module_code, p.action_code, p.description, p.active
                        FROM identity.permission p
                        WHERE (CAST(:module AS varchar) IS NULL OR p.module_code = :module)
                        ORDER BY %s
                        LIMIT :limit OFFSET :offset
                        """.formatted(AdminQuery.orderBy(sort, PERMISSION_SORT_COLUMNS)))
                .param("module", module)
                .param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((resultSet, rowNum) -> new PermissionResponse(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("module_code"),
                        resultSet.getString("action_code"),
                        resultSet.getString("description"),
                        resultSet.getBoolean("active")))
                .list();
    }

    public long countPermissions(String module) {
        return jdbc.sql("SELECT count(*) FROM identity.permission WHERE (CAST(:module AS varchar) IS NULL OR module_code = :module)")
                .param("module", module)
                .query(Long.class)
                .single();
    }

    private static String baseRoleSelect() {
        return """
                SELECT r.id, r.version, r.code, r.name, r.description, r.active,
                       r.created_at, r.updated_at,
                       COALESCE(array_agg(DISTINCT rp.permission_id)
                           FILTER (WHERE rp.permission_id IS NOT NULL), ARRAY[]::uuid[]) permission_ids
                FROM identity.app_role r
                LEFT JOIN identity.role_permission rp ON rp.role_id = r.id
                """;
    }

    private static String roleGroup() {
        return """
                 GROUP BY r.id, r.version, r.code, r.name, r.description, r.active,
                          r.created_at, r.updated_at
                """;
    }

    private static RoleResponse mapRole(ResultSet resultSet) throws SQLException {
        java.sql.Array permissionArray = resultSet.getArray("permission_ids");
        Object[] rawPermissions = permissionArray == null ? new Object[0] : (Object[]) permissionArray.getArray();
        List<UUID> permissionIds = java.util.Arrays.stream(rawPermissions)
                .map(value -> value instanceof UUID uuid ? uuid : UUID.fromString(value.toString()))
                .sorted()
                .toList();
        return new RoleResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getString("code"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getBoolean("active"),
                permissionIds,
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
