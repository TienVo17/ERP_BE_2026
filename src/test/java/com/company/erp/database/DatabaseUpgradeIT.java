package com.company.erp.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
    void upgradesCleanV011DatabaseToV015WithoutLosingExistingRows() throws Exception {
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
            assertThat(currentVersion(postgres)).isEqualTo("016");
        }
    }

    /**
     * A V014 idempotency row carries no stored response, so V015 cannot honour a replay for it. The
     * migration must say so rather than invent one: a finished command is quarantined until its own
     * retention runs out, and anything unfinished simply becomes retryable straight away.
     */
    @Test
    void quarantinesFinishedLegacyIdempotencyRowsAndReleasesTheRest() throws Exception {
        try (PostgreSQLContainer postgres = postgres("erp_upgrade_idempotency")) {
            postgres.start();
            migrateToV011(postgres);

            UUID completedId = insertLegacyIdempotencyRecord(postgres, "COMPLETED", "legacy-completed");
            UUID inProgressId = insertLegacyIdempotencyRecord(postgres, "IN_PROGRESS", "legacy-active");
            UUID failedId = insertLegacyIdempotencyRecord(postgres, "FAILED", "legacy-failed");
            OffsetDateTime completedExpiry = expiresAt(postgres, completedId);

            migrateToLatest(postgres);

            assertThat(legacyStatus(postgres, completedId)).isEqualTo("QUARANTINED");
            assertThat(legacyStatus(postgres, inProgressId)).isEqualTo("RETRYABLE");
            assertThat(legacyStatus(postgres, failedId)).isEqualTo("RETRYABLE");

            // The quarantined row keeps its own retention window; the released ones expire now.
            assertThat(expiresAt(postgres, completedId)).isEqualTo(completedExpiry);
            assertThat(expiresAt(postgres, inProgressId)).isBefore(OffsetDateTime.now().plusMinutes(1));

            // No response was fabricated for any of them.
            try (Connection connection = connection(postgres);
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT count(*)
                            FROM system.idempotency_record
                            WHERE response_body IS NOT NULL
                               OR response_status IS NOT NULL
                               OR completed_at IS NOT NULL
                               OR lease_owner IS NOT NULL
                            """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
            assertThat(currentVersion(postgres)).isEqualTo("016");
        }
    }

    /**
     * The stock ledger is append-only, so V016's movement_sequence backfill has to suspend that
     * guard. An empty ledger never exercises the row trigger, so this seeds real movements first.
     */
    @Test
    void backfillsMovementSequenceOnAPopulatedLedgerWithoutBreakingAppendOnly() throws Exception {
        try (PostgreSQLContainer postgres = postgres("erp_upgrade_ledger")) {
            postgres.start();
            migrateToV011(postgres);
            UUID customerId = insertCustomer(postgres, "LEDGER-CUSTOMER");
            UUID buyerOrderId = insertBuyerOrder(postgres, customerId);
            UUID positionId = insertFinishedStockWithMovements(postgres, buyerOrderId, customerId);

            migrateToLatest(postgres);

            assertThat(currentVersion(postgres)).isEqualTo("016");
            try (Connection connection = connection(postgres);
                    var statement = connection.prepareStatement("""
                            SELECT movement_sequence
                            FROM inventory.stock_movement
                            WHERE stock_position_id = ?
                            ORDER BY occurred_at, id
                            """)) {
                statement.setObject(1, positionId);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isOne();
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(2);
                    assertThat(result.next()).isFalse();
                }
            }

            // The guard must be active again once the migration has finished.
            try (Connection connection = connection(postgres);
                    var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE inventory.stock_movement SET balance_after = 0 WHERE stock_position_id = '"
                                + positionId + "'"))
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("55000");
            }
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
            assertThat(currentVersion(postgres)).isEqualTo("016");
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
            assertThat(currentVersion(postgres)).isEqualTo("016");
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

    private static UUID insertLegacyIdempotencyRecord(
            PostgreSQLContainer postgres, String status, String key) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection(postgres);
                var statement = connection.prepareStatement("""
                        INSERT INTO system.idempotency_record (
                            id, scope, idempotency_key, request_hash, status, expires_at
                        ) VALUES (?, 'legacy-scope', ?, 'legacy-hash', ?, clock_timestamp() + interval '6 hours')
                        """)) {
            statement.setObject(1, id);
            statement.setString(2, key + '-' + id);
            statement.setString(3, status);
            statement.executeUpdate();
        }
        return id;
    }

    private static String legacyStatus(PostgreSQLContainer postgres, UUID id) throws SQLException {
        try (Connection connection = connection(postgres);
                var statement = connection.prepareStatement(
                        "SELECT status FROM system.idempotency_record WHERE id = ?")) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static OffsetDateTime expiresAt(PostgreSQLContainer postgres, UUID id) throws SQLException {
        try (Connection connection = connection(postgres);
                var statement = connection.prepareStatement(
                        "SELECT expires_at FROM system.idempotency_record WHERE id = ?")) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getObject(1, OffsetDateTime.class);
            }
        }
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

    /** Seeds a FINISHED Production Order, its Stock Position and two movements at V011 shape. */
    private static UUID insertFinishedStockWithMovements(
            PostgreSQLContainer postgres, UUID buyerOrderId, UUID customerId) throws SQLException {
        UUID itemId = UUID.randomUUID();
        UUID productionId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        try (Connection connection = connection(postgres)) {
            connection.setAutoCommit(false);
            try {
                try (var item = connection.prepareStatement("""
                        INSERT INTO sales.buyer_order_item (
                            id, buyer_order_id, line_no, is_custom, product_kind_snapshot,
                            style_no_snapshot, name_snapshot, uom_id, uom_code_snapshot, order_qty,
                            production_qty, unit_price, currency_code, amount, created_by, updated_by
                        ) VALUES (?, ?, 1, true, 'PRINT', 'STYLE', 'Ledger Item',
                                  '10000000-0000-0000-0000-000000000002', 'EA', 10, 10, 0, 'USD', 0, ?, ?)
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
                            product_no, qr_value, planned_qty, produced_qty, status, finished_at,
                            finished_by, created_by, updated_by
                        ) VALUES (?, ?, ?, ?, 'PRINT', 'LEDGER', ?, 10, 10, 'FINISHED',
                                  clock_timestamp(), ?, ?, ?)
                        """)) {
                    production.setObject(1, productionId);
                    production.setString(2, number("PR"));
                    production.setObject(3, itemId);
                    production.setObject(4, buyerOrderId);
                    production.setString(5, "LEDGER-PRODUCTION-" + productionId);
                    production.setObject(6, SYSTEM_USER_ID);
                    production.setObject(7, SYSTEM_USER_ID);
                    production.setObject(8, SYSTEM_USER_ID);
                    production.executeUpdate();
                }
                try (var config = connection.prepareStatement("""
                        INSERT INTO production.production_print_config (production_order_id, updated_by)
                        VALUES (?, ?)
                        """)) {
                    config.setObject(1, productionId);
                    config.setObject(2, SYSTEM_USER_ID);
                    config.executeUpdate();
                }
                try (var position = connection.prepareStatement("""
                        INSERT INTO inventory.stock_position (
                            id, production_order_id, buyer_order_item_id, customer_id, currency_code,
                            uom_id, order_qty, produced_qty, delivered_qty, returned_qty, disposed_qty,
                            current_qty, order_balance_qty, created_by, updated_by
                        ) VALUES (?, ?, ?, ?, 'USD', '10000000-0000-0000-0000-000000000002',
                                  10, 10, 0, 0, 4, 6, 10, ?, ?)
                        """)) {
                    position.setObject(1, positionId);
                    position.setObject(2, productionId);
                    position.setObject(3, itemId);
                    position.setObject(4, customerId);
                    position.setObject(5, SYSTEM_USER_ID);
                    position.setObject(6, SYSTEM_USER_ID);
                    position.executeUpdate();
                }
                insertLegacyMovement(connection, positionId, productionId, "PRODUCTION", "10", "10",
                        "PRODUCTION", null, "2031-01-01 00:00:00+00");
                insertLegacyMovement(connection, positionId, positionId, "DISPOSE", "-4", "6",
                        "STOCK_ADJUSTMENT", "ledger fixture", "2031-01-02 00:00:00+00");
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return positionId;
    }

    private static void insertLegacyMovement(
            Connection connection, UUID positionId, UUID sourceId, String type, String quantity,
            String balance, String sourceType, String reason, String occurredAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO inventory.stock_movement (
                    id, stock_position_id, movement_type, quantity_signed, balance_after,
                    business_date, source_type, source_id, idempotency_key, reason, created_by,
                    occurred_at
                ) VALUES (?, ?, ?, CAST(? AS numeric), CAST(? AS numeric), DATE '2031-01-01', ?, ?, ?, ?, ?,
                          CAST(? AS timestamptz))
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, positionId);
            statement.setString(3, type);
            statement.setString(4, quantity);
            statement.setString(5, balance);
            statement.setString(6, sourceType);
            statement.setObject(7, sourceId);
            statement.setString(8, "ledger-" + UUID.randomUUID());
            statement.setString(9, reason);
            statement.setObject(10, SYSTEM_USER_ID);
            statement.setString(11, occurredAt);
            statement.executeUpdate();
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
