package com.company.erp.database;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUpgradeIT {

    @Test
    void upgradesExistingV010DatabaseToV011WithoutLosingIdentityRows() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("erp_upgrade_test")
                .withUsername("erp_migration_upgrade")
                .withPassword("erp_migration_upgrade")
                .withInitScript("db/testcontainer/init-upgrade-runtime-role.sql")) {
            postgres.start();

            Flyway flyway = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target("010")
                    .load();
            flyway.migrate();

            UUID preservedUserId = UUID.randomUUID();
            try (var connection = java.sql.DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    var statement = connection.prepareStatement("""
                            INSERT INTO identity.app_user (
                                id, kind, login_id, password_hash, position, name, status
                            ) VALUES (?, 'USER', ?, '{argon2}upgrade-placeholder', 'QA', 'Upgrade Fixture', 'ACTIVE')
                            """)) {
                statement.setObject(1, preservedUserId);
                statement.setString(2, "upgrade-fixture-" + preservedUserId);
                statement.executeUpdate();
            }

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .load()
                    .migrate();

            try (var connection = java.sql.DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    var statement = connection.prepareStatement("""
                            SELECT must_change_password, password_generation
                            FROM identity.app_user
                            WHERE id = ?
                            """)) {
                statement.setObject(1, preservedUserId);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean("must_change_password")).isFalse();
                    assertThat(result.getLong("password_generation")).isZero();
                }
            }

            assertThat(Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .load()
                    .info()
                    .current()
                    .getVersion()
                    .toString()).isEqualTo("011");
        }
    }
}
