package com.company.erp.identity.infrastructure;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.company.erp.identity.api.AdminResponses.IpAllowlistEntryResponse;
import com.company.erp.identity.api.AdminResponses.LoginEventResponse;
import com.company.erp.identity.application.AdminQuery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MonitoringJdbcRepository {

    private static final Map<String, String> LOGIN_SORT_COLUMNS = Map.of(
            "occurredAt", "e.occurred_at",
            "id", "e.id");
    private static final Map<String, String> ALLOWLIST_SORT_COLUMNS = Map.of(
            "network", "network(a.network)",
            "name", "lower(a.name)",
            "id", "a.id");

    private final JdbcClient jdbc;

    public MonitoringJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> normalizeLoginSort(List<String> requested) {
        return AdminQuery.sort(requested, LOGIN_SORT_COLUMNS, List.of("occurredAt,desc", "id,asc"));
    }

    public List<String> normalizeAllowlistSort(List<String> requested) {
        return AdminQuery.sort(requested, ALLOWLIST_SORT_COLUMNS, List.of("network,asc", "id,asc"));
    }

    public List<LoginEventResponse> loginEvents(
            int page,
            int size,
            UUID userId,
            String loginId,
            String outcome,
            Instant from,
            Instant to,
            List<String> sort) {
        return jdbc.sql("""
                        SELECT e.id, e.user_id, e.login_id_attempted, u.name AS user_name,
                               e.outcome, host(e.client_ip) AS client_ip,
                               e.ip_name_snapshot, e.user_agent, e.occurred_at
                        FROM identity.login_event e
                        LEFT JOIN identity.app_user u ON u.id = e.user_id
                        WHERE (CAST(:userId AS uuid) IS NULL OR e.user_id = :userId)
                          AND (CAST(:loginId AS varchar) IS NULL
                               OR lower(btrim(e.login_id_attempted)) = lower(btrim(:loginId)))
                          AND (CAST(:outcome AS varchar) IS NULL OR e.outcome = :outcome)
                          AND (CAST(:fromInstant AS timestamptz) IS NULL OR e.occurred_at >= :fromInstant)
                          AND (CAST(:toInstant AS timestamptz) IS NULL OR e.occurred_at < :toInstant)
                        ORDER BY %s
                        LIMIT :limit OFFSET :offset
                        """.formatted(AdminQuery.orderBy(sort, LOGIN_SORT_COLUMNS)))
                .param("userId", userId, Types.OTHER)
                .param("loginId", blankToNull(loginId))
                .param("outcome", outcome)
                .param("fromInstant", offset(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("toInstant", offset(to), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((resultSet, rowNum) -> new LoginEventResponse(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("login_id_attempted"),
                        resultSet.getString("user_name"),
                        resultSet.getString("outcome"),
                        resultSet.getString("client_ip"),
                        resultSet.getString("ip_name_snapshot"),
                        resultSet.getString("user_agent"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public long countLoginEvents(
            UUID userId,
            String loginId,
            String outcome,
            Instant from,
            Instant to) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM identity.login_event e
                        WHERE (CAST(:userId AS uuid) IS NULL OR e.user_id = :userId)
                          AND (CAST(:loginId AS varchar) IS NULL
                               OR lower(btrim(e.login_id_attempted)) = lower(btrim(:loginId)))
                          AND (CAST(:outcome AS varchar) IS NULL OR e.outcome = :outcome)
                          AND (CAST(:fromInstant AS timestamptz) IS NULL OR e.occurred_at >= :fromInstant)
                          AND (CAST(:toInstant AS timestamptz) IS NULL OR e.occurred_at < :toInstant)
                        """)
                .param("userId", userId, Types.OTHER)
                .param("loginId", blankToNull(loginId))
                .param("outcome", outcome)
                .param("fromInstant", offset(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("toInstant", offset(to), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class)
                .single();
    }

    public Optional<IpAllowlistEntryResponse> findAllowlist(UUID entryId) {
        return jdbc.sql("""
                        SELECT id, version, network(network)::text AS network, name, active, created_at, updated_at
                        FROM identity.ip_allowlist_entry
                        WHERE id = :entryId
                        """)
                .param("entryId", entryId)
                .query((resultSet, rowNum) -> mapAllowlist(resultSet))
                .optional();
    }

    public List<IpAllowlistEntryResponse> allowlist(
            int page,
            int size,
            Boolean active,
            List<String> sort) {
        return jdbc.sql("""
                        SELECT a.id, a.version, network(a.network)::text AS network, a.name, a.active,
                               a.created_at, a.updated_at
                        FROM identity.ip_allowlist_entry a
                        WHERE (CAST(:active AS boolean) IS NULL OR a.active = :active)
                        ORDER BY %s
                        LIMIT :limit OFFSET :offset
                        """.formatted(AdminQuery.orderBy(sort, ALLOWLIST_SORT_COLUMNS)))
                .param("active", active)
                .param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((resultSet, rowNum) -> mapAllowlist(resultSet))
                .list();
    }

    public long countAllowlist(Boolean active) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM identity.ip_allowlist_entry
                        WHERE (CAST(:active AS boolean) IS NULL OR active = :active)
                        """)
                .param("active", active)
                .query(Long.class)
                .single();
    }

    public UUID createAllowlist(String network, String name, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.ip_allowlist_entry (
                            id, network, name, active, created_by, updated_by
                        ) VALUES (
                            :id, network(CAST(:network AS inet)), :name, true, :actorUserId, :actorUserId
                        )
                        """)
                .param("id", id)
                .param("network", network)
                .param("name", name.trim())
                .param("actorUserId", actorUserId)
                .update();
        return id;
    }

    public int updateAllowlist(
            UUID entryId,
            long version,
            String network,
            String name,
            boolean active,
            UUID actorUserId) {
        return jdbc.sql("""
                        UPDATE identity.ip_allowlist_entry
                        SET network = network(CAST(:network AS inet)),
                            name = :name,
                            active = :active,
                            version = version + 1,
                            updated_by = :actorUserId
                        WHERE id = :entryId AND version = :version
                        """)
                .param("network", network)
                .param("name", name.trim())
                .param("active", active)
                .param("actorUserId", actorUserId)
                .param("entryId", entryId)
                .param("version", version)
                .update();
    }

    public int deleteAllowlist(UUID entryId, long version) {
        return jdbc.sql("DELETE FROM identity.ip_allowlist_entry WHERE id = :entryId AND version = :version")
                .param("entryId", entryId)
                .param("version", version)
                .update();
    }

    private static IpAllowlistEntryResponse mapAllowlist(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new IpAllowlistEntryResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getString("network"),
                resultSet.getString("name"),
                resultSet.getBoolean("active"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
