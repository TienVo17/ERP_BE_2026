package com.company.erp.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseUpgradeIT {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void upgradesCleanV011DatabaseToV014WithoutLosingExistingRows() throws Exception {
        try (PostgreSQLContainer postgres = postgres("erp_upgrade_test")) {
            postgres.start();
            migrateToV011(postgres);

            UUID preservedUserId = UUID.randomUUID();
            UUID customerId = insertCustomer(postgres, "VALID-UPGRADE");
            UUID buyerOrderId = insertBuyerOrder(postgres, customerId);
            try (Connection connection = connection(postgres);
                    var statement = connection.prepareStatement("""
                            INSERT INTO identity.app_user (
                                id, kind, login_id, password_hash, position, name, status
                            ) VALUES (?, 'USER', ?, '{argon2}upgrade-placeholder', 'QA', 'Upgrade Fixture', 'ACTIVE')
                            """)) {
                statement.setObject(1, preservedUserId);
                statement.setString(2, "upgrade-fixture-" + preservedUserId);
                statement.executeUpdate();
            }

            migrateToLatest(postgres);

            try (Connection connection = connection(postgres);
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
            assertThat(count(postgres, "sales.buyer_order", buyerOrderId)).isOne();
            assertThat(currentVersion(postgres)).isEqualTo("014");
        }
    }

    @Test
    void migrationFailsFastForConcurrentV011WriterThenSucceedsAfterWriterEnds() throws Exception {
        try (PostgreSQLContainer postgres = postgres("erp_upgrade_writer_window")) {
            postgres.start();
            migrateToV011(postgres);
            UUID customerId = insertCustomer(postgres, "WINDOW-CUSTOMER");

            try (Connection writer = connection(postgres)) {
                writer.setAutoCommit(false);
                insertBuyerOrder(writer, customerId);

                long startedAt = System.nanoTime();
                assertThatThrownBy(() -> migrateToLatest(postgres))
                        .isInstanceOf(FlywayException.class)
                        .hasRootCauseInstanceOf(SQLException.class)
                        .rootCause()
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("55P03");
                assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)).isLessThan(2);
                writer.rollback();
            }

            migrateToLatest(postgres);
            assertThat(currentVersion(postgres)).isEqualTo("014");
            try (Connection connection = connection(postgres);
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT count(*)
                            FROM pg_trigger
                            WHERE tgname = 'trg_customer_00_usage_coordination'
                              AND NOT tgisinternal
                            """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isOne();
            }
        }
    }

    @Test
    void rejectsV011UpgradeWhenBusinessDataReferencesArchivedCustomer() throws Exception {
        try (PostgreSQLContainer postgres = postgres("erp_upgrade_customer_violation")) {
            postgres.start();
            migrateToV011(postgres);
            UUID customerId = insertCustomer(postgres, "ARCHIVED-CUSTOMER");
            UUID buyerOrderId = insertBuyerOrder(postgres, customerId);
            updateStatus(postgres, "customer", customerId, "ARCHIVED");

            assertUpgradeRejected(postgres, "business data references an ARCHIVED Customer");

            assertThat(count(postgres, "sales.buyer_order", buyerOrderId)).isOne();
            assertThat(status(postgres, "customer", customerId)).isEqualTo("ARCHIVED");
            assertThat(currentVersion(postgres)).isEqualTo("011");
        }
    }

    @Test
    void rejectsV011UpgradeWhenBusinessDataReferencesArchivedProcess() throws Exception {
        try (PostgreSQLContainer postgres = postgres("erp_upgrade_process_violation")) {
            postgres.start();
            migrateToV011(postgres);
            UUID customerId = insertCustomer(postgres, "PROCESS-CUSTOMER");
            UUID buyerOrderId = insertBuyerOrder(postgres, customerId);
            UUID processId = insertProcess(postgres);
            insertProductionProcess(postgres, buyerOrderId, processId);
            updateStatus(postgres, "process_master", processId, "ARCHIVED");

            assertUpgradeRejected(postgres, "business data references an ARCHIVED Process");

            assertThat(status(postgres, "process_master", processId)).isEqualTo("ARCHIVED");
            assertThat(currentVersion(postgres)).isEqualTo("011");
        }
    }

    @Test
    void rejectsV011UpgradeWhenPostedDeliveryReferencesArchivedExchangeRate() throws Exception {
        try (PostgreSQLContainer postgres = postgres("erp_upgrade_rate_violation")) {
            postgres.start();
            migrateToV011(postgres);
            UUID customerId = insertCustomer(postgres, "RATE-CUSTOMER");
            UUID rateId = insertExchangeRate(postgres);
            updateStatus(postgres, "monthly_exchange_rate", rateId, "ARCHIVED");
            UUID deliveryId = insertPostedDelivery(postgres, customerId, rateId);

            assertUpgradeRejected(postgres, "posted or reversed Delivery references an ARCHIVED Exchange Rate");

            assertThat(count(postgres, "delivery.delivery_note", deliveryId)).isOne();
            assertThat(status(postgres, "monthly_exchange_rate", rateId)).isEqualTo("ARCHIVED");
            assertThat(currentVersion(postgres)).isEqualTo("011");
        }
    }

    @Test
    void rejectsV011UpgradeWhenBuyerOrderItemReferencesArchivedFinishedGood() throws Exception {
        try (PostgreSQLContainer postgres = postgres("erp_upgrade_finished_good_violation")) {
            postgres.start();
            migrateToV011(postgres);
            UUID customerId = insertCustomer(postgres, "FG-CUSTOMER");
            UUID buyerOrderId = insertBuyerOrder(postgres, customerId);
            UUID finishedGoodId = insertFinishedGood(postgres);
            insertBuyerOrderItem(postgres, buyerOrderId, finishedGoodId);
            updateStatus(postgres, "finished_good", finishedGoodId, "ARCHIVED");

            assertUpgradeRejected(postgres, "business data references an ARCHIVED Finished Good");

            assertThat(status(postgres, "finished_good", finishedGoodId)).isEqualTo("ARCHIVED");
            assertThat(currentVersion(postgres)).isEqualTo("012");
        }
    }

    @Test
    void migrationFailsFastForConcurrentBuyerOrderItemWriterThenSucceedsAfterWriterEnds() throws Exception {
        try (PostgreSQLContainer postgres = postgres("erp_upgrade_item_writer_window")) {
            postgres.start();
            migrateToV011(postgres);
            UUID customerId = insertCustomer(postgres, "ITEM-CUSTOMER");
            UUID buyerOrderId = insertBuyerOrder(postgres, customerId);
            UUID finishedGoodId = insertFinishedGood(postgres);

            try (Connection writer = connection(postgres)) {
                writer.setAutoCommit(false);
                insertBuyerOrderItem(writer, buyerOrderId, finishedGoodId);

                long startedAt = System.nanoTime();
                assertThatThrownBy(() -> migrateToLatest(postgres))
                        .isInstanceOf(FlywayException.class)
                        .hasRootCauseInstanceOf(SQLException.class)
                        .rootCause()
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("55P03");
                assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)).isLessThan(2);
                assertThat(currentVersion(postgres)).isEqualTo("012");
                writer.rollback();
            }

            migrateToLatest(postgres);
            assertThat(currentVersion(postgres)).isEqualTo("014");
            try (Connection connection = connection(postgres);
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT count(*)
                            FROM pg_trigger
                            WHERE tgname = 'trg_finished_good_usage_transition'
                              AND NOT tgisinternal
                            """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isOne();
            }
        }
    }

    private static PostgreSQLContainer postgres(String databaseName) {
        return new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("erp_upgrade_test")
                .withUsername("erp_migration_upgrade")
                .withPassword("erp_migration_upgrade")
                .withInitScript("db/testcontainer/init-upgrade-runtime-role.sql");
    }

    private static void migrateToV011(PostgreSQLContainer postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("011")
                .load()
                .migrate();
    }

    private static void migrateToLatest(PostgreSQLContainer postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    private static void assertUpgradeRejected(PostgreSQLContainer postgres, String marker) {
        assertThatThrownBy(() -> migrateToLatest(postgres))
                .isInstanceOf(FlywayException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause()
                .hasMessageContaining("MASTER_GUARD_MIGRATION_INVALID:")
                .hasMessageContaining(marker);
    }

    private static UUID insertCustomer(PostgreSQLContainer postgres, String key) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection(postgres);
                var statement = connection.prepareStatement("""
                        INSERT INTO master_data.customer (
                            id, short_name, name, currency_code, created_by, updated_by
                        ) VALUES (?, ?, 'Upgrade Customer', 'USD', ?, ?)
                        """)) {
            statement.setObject(1, id);
            statement.setString(2, (key + '-' + id).substring(0, Math.min((key + '-' + id).length(), 50)));
            statement.setObject(3, SYSTEM_USER_ID);
            statement.setObject(4, SYSTEM_USER_ID);
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertBuyerOrder(PostgreSQLContainer postgres, UUID customerId) throws SQLException {
        try (Connection connection = connection(postgres)) {
            return insertBuyerOrder(connection, customerId);
        }
    }

    private static UUID insertBuyerOrder(Connection connection, UUID customerId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var statement = connection.prepareStatement("""
                INSERT INTO sales.buyer_order (
                    id, sys_po_no, order_type, customer_id, customer_name_snapshot,
                    customer_short_name_snapshot, pic_source, pic_name_snapshot, buyer_po,
                    po_date, delivery_date, created_by, updated_by
                ) VALUES (?, ?, 'STANDARD', ?, 'Upgrade Customer', 'UPGRADE', 'CUSTOM', 'PIC',
                          'PO', CURRENT_DATE, CURRENT_DATE, ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setString(2, number("SO"));
            statement.setObject(3, customerId);
            statement.setObject(4, SYSTEM_USER_ID);
            statement.setObject(5, SYSTEM_USER_ID);
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertFinishedGood(PostgreSQLContainer postgres) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection(postgres);
                var statement = connection.prepareStatement("""
                        INSERT INTO master_data.finished_good (
                            id, product_kind, style_no, name, uom_id, created_by, updated_by
                        ) VALUES (?, 'PRINT', ?, 'Upgrade Finished Good',
                                  '10000000-0000-0000-0000-000000000002', ?, ?)
                        """)) {
            statement.setObject(1, id);
            statement.setString(2, "UPGRADE-STYLE-" + id);
            statement.setObject(3, SYSTEM_USER_ID);
            statement.setObject(4, SYSTEM_USER_ID);
            statement.executeUpdate();
        }
        return id;
    }

    private static void insertBuyerOrderItem(
            PostgreSQLContainer postgres, UUID buyerOrderId, UUID finishedGoodId) throws SQLException {
        try (Connection connection = connection(postgres)) {
            insertBuyerOrderItem(connection, buyerOrderId, finishedGoodId);
        }
    }

    private static void insertBuyerOrderItem(
            Connection connection, UUID buyerOrderId, UUID finishedGoodId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO sales.buyer_order_item (
                    id, buyer_order_id, line_no, is_custom, finished_good_id, product_kind_snapshot,
                    style_no_snapshot, name_snapshot, uom_id, uom_code_snapshot, order_qty,
                    production_qty, unit_price, currency_code, amount, created_by, updated_by
                ) VALUES (?, ?, 1, false, ?, 'PRINT', 'STYLE', 'Upgrade Item',
                          '10000000-0000-0000-0000-000000000002', 'EA', 1, 1, 0, 'USD', 0, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, buyerOrderId);
            statement.setObject(3, finishedGoodId);
            statement.setObject(4, SYSTEM_USER_ID);
            statement.setObject(5, SYSTEM_USER_ID);
            statement.executeUpdate();
        }
    }

    private static UUID insertProcess(PostgreSQLContainer postgres) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection(postgres);
                var statement = connection.prepareStatement("""
                        INSERT INTO master_data.process_master (
                            id, name, sequence_no, qr_value, created_by, updated_by
                        ) VALUES (?, ?, 1, ?, ?, ?)
                        """)) {
            statement.setObject(1, id);
            statement.setString(2, "Upgrade Process " + id);
            statement.setString(3, "UPGRADE-PROCESS-" + id);
            statement.setObject(4, SYSTEM_USER_ID);
            statement.setObject(5, SYSTEM_USER_ID);
            statement.executeUpdate();
        }
        return id;
    }

    private static void insertProductionProcess(
            PostgreSQLContainer postgres, UUID buyerOrderId, UUID processId) throws SQLException {
        UUID itemId = UUID.randomUUID();
        UUID productionId = UUID.randomUUID();
        try (Connection connection = connection(postgres)) {
            connection.setAutoCommit(false);
            try {
                try (var item = connection.prepareStatement("""
                        INSERT INTO sales.buyer_order_item (
                            id, buyer_order_id, line_no, is_custom, product_kind_snapshot,
                            style_no_snapshot, name_snapshot, uom_id, uom_code_snapshot, order_qty,
                            production_qty, unit_price, currency_code, amount, created_by, updated_by
                        ) VALUES (?, ?, 1, true, 'PRINT', 'STYLE', 'Upgrade Item',
                                  '10000000-0000-0000-0000-000000000002', 'EA', 1, 1, 0, 'USD', 0, ?, ?)
                        """)) {
                    item.setObject(1, itemId);
                    item.setObject(2, buyerOrderId);
                    item.setObject(3, SYSTEM_USER_ID);
                    item.setObject(4, SYSTEM_USER_ID);
                    item.executeUpdate();
                }
                try (var production = connection.prepareStatement("""
                        INSERT INTO production.production_order (
                            id, production_no, buyer_order_item_id, buyer_order_id, product_kind_snapshot,
                            product_no, qr_value, planned_qty, created_by, updated_by
                        ) VALUES (?, ?, ?, ?, 'PRINT', 'UPGRADE', ?, 1, ?, ?)
                        """)) {
                    production.setObject(1, productionId);
                    production.setString(2, number("PR"));
                    production.setObject(3, itemId);
                    production.setObject(4, buyerOrderId);
                    production.setString(5, "UPGRADE-PRODUCTION-" + productionId);
                    production.setObject(6, SYSTEM_USER_ID);
                    production.setObject(7, SYSTEM_USER_ID);
                    production.executeUpdate();
                }
                try (var process = connection.prepareStatement("""
                        INSERT INTO production.production_order_process (
                            production_order_id, process_id, sequence_no
                        ) VALUES (?, ?, 1)
                        """)) {
                    process.setObject(1, productionId);
                    process.setObject(2, processId);
                    process.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static UUID insertExchangeRate(PostgreSQLContainer postgres) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection(postgres);
                var statement = connection.prepareStatement("""
                        INSERT INTO master_data.monthly_exchange_rate (
                            id, effective_month, vnd_usd_rate, won_usd_rate, created_by, updated_by
                        ) VALUES (?, DATE '2031-01-01', 25000, 1300, ?, ?)
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, SYSTEM_USER_ID);
            statement.setObject(3, SYSTEM_USER_ID);
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertPostedDelivery(
            PostgreSQLContainer postgres, UUID customerId, UUID rateId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection(postgres);
                var disableTrigger = connection.createStatement();
                var statement = connection.prepareStatement("""
                        INSERT INTO delivery.delivery_note (
                            id, delivery_no, customer_id, customer_name_snapshot, delivery_date,
                            currency_code, exchange_rate_id, vnd_usd_rate_snapshot, won_usd_rate_snapshot,
                            total_qty, total_amount, status, posted_at, posted_by, created_by, updated_by
                        ) VALUES (?, ?, ?, 'Upgrade Customer', DATE '2031-01-15', 'USD', ?, 25000, 1300,
                                  0, 0, 'POSTED', clock_timestamp(), ?, ?, ?)
                        """)) {
            disableTrigger.execute("ALTER TABLE delivery.delivery_note DISABLE TRIGGER trg_delivery_note_integrity");
            try {
                statement.setObject(1, id);
                statement.setString(2, number("DN"));
                statement.setObject(3, customerId);
                statement.setObject(4, rateId);
                statement.setObject(5, SYSTEM_USER_ID);
                statement.setObject(6, SYSTEM_USER_ID);
                statement.setObject(7, SYSTEM_USER_ID);
                statement.executeUpdate();
            } finally {
                disableTrigger.execute("ALTER TABLE delivery.delivery_note ENABLE TRIGGER trg_delivery_note_integrity");
            }
        }
        return id;
    }

    private static void updateStatus(
            PostgreSQLContainer postgres, String table, UUID id, String status) throws SQLException {
        String qualifiedTable = masterTable(table);
        try (Connection connection = connection(postgres);
                var statement = connection.prepareStatement(
                        "UPDATE " + qualifiedTable + " SET status = ?, updated_by = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setObject(2, SYSTEM_USER_ID);
            statement.setObject(3, id);
            statement.executeUpdate();
        }
    }

    private static String status(PostgreSQLContainer postgres, String table, UUID id) throws SQLException {
        try (Connection connection = connection(postgres);
                var statement = connection.prepareStatement(
                        "SELECT status FROM " + masterTable(table) + " WHERE id = ?")) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static String masterTable(String table) {
        return switch (table) {
            case "customer" -> "master_data.customer";
            case "process_master" -> "master_data.process_master";
            case "monthly_exchange_rate" -> "master_data.monthly_exchange_rate";
            case "finished_good" -> "master_data.finished_good";
            default -> throw new IllegalArgumentException("Unsupported upgrade fixture table");
        };
    }

    private static long count(PostgreSQLContainer postgres, String table, UUID id) throws SQLException {
        String qualifiedTable = switch (table) {
            case "sales.buyer_order", "delivery.delivery_note" -> table;
            default -> throw new IllegalArgumentException("Unsupported upgrade fixture table");
        };
        try (Connection connection = connection(postgres);
                var statement = connection.prepareStatement(
                        "SELECT count(*) FROM " + qualifiedTable + " WHERE id = ?")) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static String currentVersion(PostgreSQLContainer postgres) throws SQLException {
        try (Connection connection = connection(postgres);
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static Connection connection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String number(String prefix) {
        return "%s-2031-%06d".formatted(prefix, Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000));
    }
}
