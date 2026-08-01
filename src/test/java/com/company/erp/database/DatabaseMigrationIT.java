package com.company.erp.database;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.erp.support.PostgresTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class DatabaseMigrationIT {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SALE_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Autowired
    private DataSource dataSource;

    @Test
    void appliesV001ThroughV015WithTransactionCommandFoundation() {
        JdbcClient jdbc = JdbcClient.create(dataSource);

        assertThat(Flyway.configure().dataSource(dataSource).load().info().applied())
                .extracting(info -> info.getVersion().toString())
                .containsExactly("001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012", "013", "014", "015");

        assertThat(columns(jdbc, "identity", "app_user"))
                .contains("must_change_password", "password_generation");
        assertThat(columns(jdbc, "identity", "auth_session"))
                .containsExactlyInAnyOrder(
                        "id", "user_id", "absolute_expires_at", "idle_expires_at", "revoked_at",
                        "revoked_reason", "created_at", "last_refreshed_at", "user_agent", "client_ip");
        assertThat(columns(jdbc, "identity", "refresh_token"))
                .containsExactlyInAnyOrder(
                        "id", "auth_session_id", "token_hash", "parent_token_id", "status",
                        "expires_at", "used_at", "revoked_at", "created_at");

        assertThat(indexNames(jdbc, "identity", "auth_session"))
                .contains("ix_auth_session_user_expiry", "ix_auth_session_active_expiry");
        assertThat(indexNames(jdbc, "identity", "refresh_token"))
                .contains("uq_refresh_token_hash", "ix_refresh_token_session_status_expiry");
        assertThat(indexNames(jdbc, "delivery", "delivery_note"))
                .contains("ix_delivery_note_used_exchange_rate");
        assertThat(jdbc.sql("""
                        SELECT pg_get_expr(i.indpred, i.indrelid)
                        FROM pg_index i
                        JOIN pg_class c ON c.oid = i.indexrelid
                        WHERE c.relname = 'ix_delivery_note_used_exchange_rate'
                        """).query(String.class).single())
                .contains("status", "POSTED", "REVERSED");

        assertThat(triggerNames(jdbc, "master_data", "finished_good"))
                .contains("trg_finished_good_00_usage_coordination", "trg_finished_good_usage_transition");
        assertThat(triggerNames(jdbc, "sales", "buyer_order_item"))
                .contains(
                        "trg_buyer_order_item_insert_00_usage_coordination",
                        "trg_buyer_order_item_update_00_usage_coordination",
                        "trg_buyer_order_item_insert_active_finished_good",
                        "trg_buyer_order_item_update_active_finished_good");
    }

    @Test
    void enforcesRefreshTokenUniquenessStatusAndSessionExpiry() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID userId = createUser(jdbc, "migration-constraints");
        UUID sessionId = createSession(jdbc, userId);
        byte[] tokenHash = new byte[32];
        tokenHash[0] = 42;

        UUID rootTokenId = UUID.randomUUID();
        insertRefreshToken(jdbc, rootTokenId, sessionId, null, tokenHash, "ACTIVE");

        assertThatThrownBy(() -> insertRefreshToken(
                jdbc, UUID.randomUUID(), sessionId, null, tokenHash, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertRefreshToken(
                jdbc, UUID.randomUUID(), sessionId, null, differentHash(tokenHash), "UNKNOWN"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID otherSessionId = createSession(jdbc, userId);
        assertThatThrownBy(() -> insertRefreshToken(
                jdbc,
                UUID.randomUUID(),
                otherSessionId,
                rootTokenId,
                differentHash(differentHash(tokenHash)),
                "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID selfParentTokenId = UUID.randomUUID();
        assertThatThrownBy(() -> insertRefreshToken(
                jdbc,
                selfParentTokenId,
                sessionId,
                selfParentTokenId,
                hashWithMarker(9),
                "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO identity.auth_session (
                            id, user_id, absolute_expires_at, idle_expires_at, user_agent, client_ip
                        ) VALUES (:id, :userId, :absoluteExpiry, :idleExpiry, :userAgent, CAST(:clientIp AS inet))
                        """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("absoluteExpiry", OffsetDateTime.now().plusHours(1))
                .param("idleExpiry", OffsetDateTime.now().plusHours(2))
                .param("userAgent", "invalid-expiry-test")
                .param("clientIp", "127.0.0.1")
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void grantsTheApprovedSaleMasterAndTransactionBaseline() {
        JdbcClient jdbc = JdbcClient.create(dataSource);

        Set<String> salePermissions = jdbc.sql("""
                        SELECT p.module_code || ':' || p.action_code
                        FROM identity.role_permission rp
                        JOIN identity.permission p ON p.id = rp.permission_id
                        WHERE rp.role_id = :roleId
                        ORDER BY p.module_code, p.action_code
                        """)
                .param("roleId", SALE_ROLE_ID)
                .query(String.class)
                .set();

        assertThat(salePermissions).containsExactlyInAnyOrder(
                "RAW_MATERIAL:VIEW", "RAW_MATERIAL:CREATE", "RAW_MATERIAL:UPDATE",
                "FINISHED_GOODS:VIEW", "FINISHED_GOODS:CREATE", "FINISHED_GOODS:UPDATE",
                "CUSTOMER:VIEW", "CUSTOMER:CREATE", "CUSTOMER:UPDATE",
                "SUPPLIER:VIEW", "SUPPLIER:CREATE", "SUPPLIER:UPDATE",
                "PROCESS:VIEW", "PROCESS:CREATE", "PROCESS:UPDATE",
                "ETC:VIEW", "ETC:CREATE", "ETC:UPDATE",
                "BUYER_ORDER:VIEW", "BUYER_ORDER:CREATE", "BUYER_ORDER:UPDATE",
                "BUYER_ORDER:CONFIRM", "BUYER_ORDER:REOPEN",
                "PRODUCTION:VIEW", "PRODUCTION:GROUP", "PRODUCTION:CONFIGURE", "PRODUCTION:FINISH",
                "DELIVERY:VIEW", "DELIVERY:CREATE", "DELIVERY:POST", "DELIVERY:REVERSE",
                "DELIVERY:PRINT", "DELIVERY:EXPORT",
                "STOCK:VIEW", "STOCK:RETURN", "STOCK:DISPOSE");
        assertThat(salePermissions).noneMatch(permission -> permission.endsWith(":ARCHIVE"));
        assertThat(salePermissions).noneMatch(permission -> permission.startsWith("ADMIN:"));
    }

    @Test
    void createsTheFencedTransactionFoundationAndHistoryFields() {
        JdbcClient jdbc = JdbcClient.create(dataSource);

        assertThat(columns(jdbc, "system", "idempotency_record")).contains(
                "scope_digest", "actor_user_id", "request_method", "normalized_path", "command_id",
                "response_status", "response_content_type", "response_body", "completed_at",
                "lease_owner", "lease_generation", "lease_expires_at");
        assertThat(columns(jdbc, "sales", "buyer_order")).contains("active_revision");
        assertThat(columns(jdbc, "sales", "buyer_order_item")).contains("revision", "active_revision", "status");
        assertThat(columns(jdbc, "production", "production_order")).contains("status");

        assertThat(jdbc.sql("SELECT count(*) FROM identity.role_permission rp JOIN identity.permission p ON p.id = rp.permission_id WHERE rp.role_id = :roleId AND p.module_code IN ('BUYER_ORDER', 'PRODUCTION', 'DELIVERY', 'STOCK')")
                .param("roleId", SALE_ROLE_ID)
                .query(Integer.class)
                .single()).isEqualTo(18);
    }

    @Test
    void preservesAppendOnlyAuditAndLoginHistory() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID userId = createUser(jdbc, "append-only-owner");
        UUID loginEventId = UUID.randomUUID();
        UUID auditEventId = UUID.randomUUID();

        jdbc.sql("""
                        INSERT INTO identity.login_event (
                            id, user_id, login_id_attempted, outcome
                        ) VALUES (:id, :userId, :loginId, 'SUCCESS')
                        """)
                .param("id", loginEventId)
                .param("userId", userId)
                .param("loginId", "append-only-owner")
                .update();
        jdbc.sql("""
                        INSERT INTO audit.audit_event (
                            id, actor_user_id, actor_type, action, entity_type, entity_id
                        ) VALUES (:id, :userId, 'USER', 'TEST', 'TEST_ENTITY', :entityId)
                        """)
                .param("id", auditEventId)
                .param("userId", userId)
                .param("entityId", userId)
                .update();

        assertThatThrownBy(() -> jdbc.sql(
                        "UPDATE identity.login_event SET outcome = 'ERROR' WHERE id = :id")
                .param("id", loginEventId)
                .update()).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.sql(
                        "DELETE FROM audit.audit_event WHERE id = :id")
                .param("id", auditEventId)
                .update()).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void everyForwardObjectMigrationDeclaresRuntimePrivilegeHandling() throws Exception {
        String v011 = migration("V011__add_identity_sessions_and_sale_master_grants.sql");
        String v012 = migration("V012__index_exchange_rate_usage.sql");
        String v013 = migration("V013__guard_finished_good_usage.sql");

        assertThat(v011)
                .contains("current_setting('erp.runtime_role', true)")
                .contains("REVOKE ALL ON identity.auth_session, identity.refresh_token FROM PUBLIC")
                .contains("GRANT SELECT, INSERT, UPDATE ON identity.auth_session, identity.refresh_token");
        assertThat(v012)
                .contains("CREATE INDEX ix_delivery_note_used_exchange_rate")
                .contains("MASTER_GUARD_MIGRATION_INVALID:")
                .contains("IN SHARE ROW EXCLUSIVE MODE NOWAIT")
                .contains("IN SHARE ROW EXCLUSIVE MODE NOWAIT")
                .contains("pg_catalog.pg_advisory_xact_lock(20260730, 1)")
                .doesNotContain("pg_catalog.pg_advisory_xact_lock(20260730, 2)")
                .doesNotContain("pg_catalog.pg_advisory_xact_lock(20260730, 3)")
                .contains("SET search_path = pg_catalog")
                .contains("REVOKE ALL ON FUNCTION master_data.require_active_customer_references() FROM PUBLIC")
                .contains("REVOKE ALL ON FUNCTION master_data.coordinate_guarded_master_usage() FROM PUBLIC")
                .doesNotContain("GRANT SELECT, INSERT, UPDATE ON ALL TABLES");
        assertThat(v013)
                .contains("MASTER_GUARD_MIGRATION_INVALID:")
                .contains("IN SHARE ROW EXCLUSIVE MODE NOWAIT")
                .contains("master_data.coordinate_guarded_master_usage()")
                .doesNotContain("pg_catalog.pg_advisory_xact_lock(20260730, 2)")
                .contains("SET search_path = pg_catalog")
                .contains("REVOKE ALL ON FUNCTION master_data.require_active_finished_good_references() FROM PUBLIC")
                .contains("REVOKE ALL ON FUNCTION master_data.validate_finished_good_usage_transition() FROM PUBLIC")
                .doesNotContain("GRANT SELECT, INSERT, UPDATE ON ALL TABLES");
    }

    private static String migration(String name) throws Exception {
        return new String(java.util.Objects.requireNonNull(DatabaseMigrationIT.class.getClassLoader()
                .getResourceAsStream("db/migration/" + name)).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void keepsSystemPrincipalInDatabaseButOutsideAdministrableProjection() {
        JdbcClient jdbc = JdbcClient.create(dataSource);

        assertThat(jdbc.sql("SELECT count(*) FROM identity.app_user WHERE id = :id AND kind = 'SYSTEM'")
                .param("id", SYSTEM_USER_ID)
                .query(Integer.class)
                .single()).isOne();

        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM identity.app_user
                        WHERE kind IN ('USER', 'STAFF')
                          AND id = :id
                        """)
                .param("id", SYSTEM_USER_ID)
                .query(Integer.class)
                .single()).isZero();
    }

    private static Set<String> columns(JdbcClient jdbc, String schema, String table) {
        return jdbc.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = :schema AND table_name = :table
                        """)
                .param("schema", schema)
                .param("table", table)
                .query(String.class)
                .set();
    }

    private static Set<String> triggerNames(JdbcClient jdbc, String schema, String table) {
        return jdbc.sql("""
                        SELECT t.tgname
                        FROM pg_trigger t
                        JOIN pg_class c ON c.oid = t.tgrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = :schema AND c.relname = :table AND NOT t.tgisinternal
                        """)
                .param("schema", schema)
                .param("table", table)
                .query(String.class)
                .set();
    }

    private static Set<String> indexNames(JdbcClient jdbc, String schema, String table) {
        return jdbc.sql("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = :schema AND tablename = :table
                        """)
                .param("schema", schema)
                .param("table", table)
                .query(String.class)
                .set();
    }

    private static UUID createUser(JdbcClient jdbc, String loginId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status
                        ) VALUES (:id, 'USER', :loginId, '{argon2}test-placeholder', 'QA', 'Migration Test', 'ACTIVE')
                        """)
                .param("id", id)
                .param("loginId", loginId + '-' + id)
                .update();
        return id;
    }

    private static UUID createSession(JdbcClient jdbc, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.auth_session (
                            id, user_id, absolute_expires_at, idle_expires_at, user_agent, client_ip
                        ) VALUES (:id, :userId, :absoluteExpiry, :idleExpiry, :userAgent, CAST(:clientIp AS inet))
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("absoluteExpiry", OffsetDateTime.now().plusHours(8))
                .param("idleExpiry", OffsetDateTime.now().plusHours(1))
                .param("userAgent", "database-migration-test")
                .param("clientIp", "127.0.0.1")
                .update();
        return id;
    }

    private static void insertRefreshToken(
            JdbcClient jdbc,
            UUID id,
            UUID sessionId,
            UUID parentTokenId,
            byte[] tokenHash,
            String status) {
        jdbc.sql("""
                        INSERT INTO identity.refresh_token (
                            id, auth_session_id, token_hash, parent_token_id, status, expires_at
                        ) VALUES (:id, :sessionId, :tokenHash, :parentTokenId, :status, :expiresAt)
                        """)
                .param("id", id)
                .param("sessionId", sessionId)
                .param("tokenHash", tokenHash)
                .param("parentTokenId", parentTokenId, java.sql.Types.OTHER)
                .param("status", status)
                .param("expiresAt", OffsetDateTime.now().plusHours(1))
                .update();
    }

    private static byte[] differentHash(byte[] hash) {
        byte[] different = hash.clone();
        different[1]++;
        return different;
    }

    private static byte[] hashWithMarker(int marker) {
        byte[] hash = new byte[32];
        hash[0] = (byte) marker;
        return hash;
    }
}
