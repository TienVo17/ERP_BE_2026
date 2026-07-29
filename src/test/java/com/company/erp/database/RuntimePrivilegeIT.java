package com.company.erp.database;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class RuntimePrivilegeIT {

    @Autowired
    private DataSource dataSource;

    @Test
    void runtimeRoleCanUseV011SessionObjectsWithoutDeletePrivilege() throws Exception {
        UUID userId = createUser("runtime-session");
        UUID sessionId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();

        asRuntime(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO identity.auth_session (
                        id, user_id, absolute_expires_at, idle_expires_at, user_agent
                    ) VALUES (?, ?, ?, ?, ?)
                    """)) {
                statement.setObject(1, sessionId);
                statement.setObject(2, userId);
                statement.setObject(3, OffsetDateTime.now().plusHours(8));
                statement.setObject(4, OffsetDateTime.now().plusHours(1));
                statement.setString(5, "runtime-privilege-test");
                statement.executeUpdate();
            }

            try (var statement = connection.prepareStatement("""
                    INSERT INTO identity.refresh_token (
                        id, auth_session_id, token_hash, status, expires_at
                    ) VALUES (?, ?, ?, 'ACTIVE', ?)
                    """)) {
                statement.setObject(1, tokenId);
                statement.setObject(2, sessionId);
                statement.setBytes(3, Arrays.copyOf(
                        "runtime-token-hash".getBytes(StandardCharsets.UTF_8), 32));
                statement.setObject(4, OffsetDateTime.now().plusHours(1));
                statement.executeUpdate();
            }

            try (var statement = connection.prepareStatement("""
                    UPDATE identity.refresh_token SET status = 'USED', used_at = clock_timestamp() WHERE id = ?
                    """)) {
                statement.setObject(1, tokenId);
                assertThat(statement.executeUpdate()).isOne();
            }

            try (var statement = connection.prepareStatement(
                    "SELECT status FROM identity.refresh_token WHERE id = ?")) {
                statement.setObject(1, tokenId);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("USED");
                }
            }
        });

        assertThatThrownBy(() -> asRuntime(connection -> {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM identity.refresh_token WHERE id = ?")) {
                statement.setObject(1, tokenId);
                statement.executeUpdate();
            }
        })).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState())
                        .isEqualTo("42501"));
    }

    @Test
    void runtimeRoleCannotMutateAppendOnlyLoginOrAuditRows() throws Exception {
        UUID userId = createUser("runtime-append-only");
        UUID loginEventId = UUID.randomUUID();
        UUID auditEventId = UUID.randomUUID();

        asRuntime(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO identity.login_event (
                        id, user_id, login_id_attempted, outcome
                    ) VALUES (?, ?, ?, 'SUCCESS')
                    """)) {
                statement.setObject(1, loginEventId);
                statement.setObject(2, userId);
                statement.setString(3, "runtime-append-only");
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO audit.audit_event (
                        id, actor_user_id, actor_type, action, entity_type, entity_id
                    ) VALUES (?, ?, 'USER', 'TEST', 'TEST_ENTITY', ?)
                    """)) {
                statement.setObject(1, auditEventId);
                statement.setObject(2, userId);
                statement.setObject(3, userId);
                statement.executeUpdate();
            }
        });

        assertMutationDenied("UPDATE identity.login_event SET outcome = 'ERROR' WHERE id = '" + loginEventId + "'");
        assertMutationDenied("DELETE FROM identity.login_event WHERE id = '" + loginEventId + "'");
        assertMutationDenied("UPDATE audit.audit_event SET action = 'CHANGED' WHERE id = '" + auditEventId + "'");
        assertMutationDenied("DELETE FROM audit.audit_event WHERE id = '" + auditEventId + "'");
    }

    @Test
    void runtimeRoleDoesNotOwnMigrationHistory() {
        assertThatThrownBy(() -> asRuntime(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM public.flyway_schema_history");
            }
        })).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState())
                        .isEqualTo("42501"));
    }

    private UUID createUser(String loginId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status
                        ) VALUES (?, 'USER', ?, '{argon2}test-placeholder', 'QA', 'Runtime Test', 'ACTIVE')
                        """)) {
            statement.setObject(1, id);
            statement.setString(2, loginId + '-' + id);
            statement.executeUpdate();
        }
        return id;
    }

    private void assertMutationDenied(String sql) {
        assertThatThrownBy(() -> asRuntime(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        })).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState())
                        .isIn(
                                "42501",
                                PSQLState.OBJECT_NOT_IN_STATE.getState()));
    }

    private void asRuntime(SqlWork work) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL ROLE erp_runtime_test");
                work.execute(connection);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void execute(Connection connection) throws Exception;
    }
}
