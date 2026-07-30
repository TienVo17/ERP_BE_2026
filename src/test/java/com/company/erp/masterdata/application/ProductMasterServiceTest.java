package com.company.erp.masterdata.application;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.company.erp.api.ApiException;
import com.company.erp.identity.domain.AppUser;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.masterdata.api.MasterDataModels.CustomerCreateRequest;
import com.company.erp.masterdata.api.MasterDataModels.FinishedGoodRequest;
import com.company.erp.masterdata.api.MasterDataModels.RawMaterialRequest;
import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class ProductMasterServiceTest {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SEEDED_UOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Autowired RawMaterialService rawMaterialService;
    @Autowired FinishedGoodService finishedGoodService;
    @Autowired PartyMasterService partyService;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;

    private ErpPrincipal actor;

    @BeforeEach
    void createActor() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO identity.app_user(id,kind,login_id,password_hash,position,name,status,created_by,updated_by) VALUES(:id,'USER',:login,'{argon2}fixture','QA','Product Test','ACTIVE',:system,:system)")
                .param("id", id).param("login", "product-service-" + id).param("system", SYSTEM_USER_ID).update();
        actor = new ErpPrincipal(
                new AppUser(id, "USER", "product-service-" + id, "redacted", "Product Test", "ACTIVE", false, 0),
                UUID.randomUUID(), "access");
    }

    @Test
    void concurrentDuplicateRawMaterialCodesHaveExactlyOneWinner() throws Exception {
        String code = "RM-RACE-" + UUID.randomUUID();
        var start = new CountDownLatch(1);
        List<Boolean> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createRawMaterial(start, code));
            var second = executor.submit(() -> createRawMaterial(start, code));
            start.countDown();
            results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.sql("SELECT count(*) FROM master_data.raw_material WHERE upper(btrim(code))=:code")
                .param("code", code.toUpperCase()).query(Long.class).single()).isOne();
    }

    @Test
    void concurrentFinishedGoodCompositeKeysAndStaleVersionsHaveExactlyOneWinner() throws Exception {
        String style = "FG-RACE-" + UUID.randomUUID();
        var start = new CountDownLatch(1);
        List<Boolean> duplicates;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createFinishedGood(start, style));
            var second = executor.submit(() -> createFinishedGood(start, style));
            start.countDown();
            duplicates = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertThat(duplicates).containsExactlyInAnyOrder(true, false);

        UUID id = finishedGoodService.create(finishedGood("FG-VERSION-" + UUID.randomUUID()), actor, null).id();
        var versionStart = new CountDownLatch(1);
        List<Boolean> versions;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> renameFinishedGood(versionStart, id, "First"));
            var second = executor.submit(() -> renameFinishedGood(versionStart, id, "Second"));
            versionStart.countDown();
            versions = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertThat(versions).containsExactlyInAnyOrder(true, false);
        assertThat(finishedGoodService.get(id).version()).isEqualTo(1);
    }

    @Test
    void finishedGoodArchiveAndDirectBuyerOrderItemInsertCannotBothCommit() throws Exception {
        UUID finishedGoodId = finishedGoodService.create(finishedGood("FG-ARCHIVE-" + UUID.randomUUID()), actor, null).id();
        UUID buyerOrderId = insertBuyerOrder();

        try (Connection archive = dataSource.getConnection()) {
            archive.setAutoCommit(false);
            try (var statement = archive.prepareStatement(
                    "UPDATE master_data.finished_good SET status='ARCHIVED',version=version+1,updated_by=? WHERE id=?")) {
                statement.setObject(1, actor.user().id());
                statement.setObject(2, finishedGoodId);
                assertThat(statement.executeUpdate()).isOne();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var relationship = executor.submit(() -> insertBuyerOrderItem(buyerOrderId, finishedGoodId));
                Thread.sleep(200);
                assertThat(relationship.isDone()).isFalse();
                archive.commit();
                assertThat(relationship.get(10, TimeUnit.SECONDS)).isFalse();
            }
        }

        assertThat(finishedGoodService.get(finishedGoodId).status()).isEqualTo("ARCHIVED");
        assertThat(jdbc.sql("SELECT count(*) FROM sales.buyer_order_item WHERE finished_good_id=:id")
                .param("id", finishedGoodId).query(Long.class).single()).isZero();
    }

    @Test
    void buyerOrderItemWriterBlocksLaterFinishedGoodLifecycleWithoutDeadlock() throws Exception {
        UUID finishedGoodId = finishedGoodService.create(finishedGood("FG-GUARD-" + UUID.randomUUID()), actor, null).id();
        UUID buyerOrderId = insertBuyerOrder();

        try (Connection relationship = dataSource.getConnection(); Connection lifecycle = dataSource.getConnection()) {
            relationship.setAutoCommit(false);
            lifecycle.setAutoCommit(false);
            setConcurrencyTimeouts(relationship);
            setConcurrencyTimeouts(lifecycle);
            assertThat(insertBuyerOrderItem(relationship, buyerOrderId, finishedGoodId)).isOne();

            try (var executor = Executors.newSingleThreadExecutor()) {
                var lifecycleWrite = executor.submit(() -> archiveFinishedGood(lifecycle, finishedGoodId));
                Thread.sleep(200);
                assertThat(lifecycleWrite.isDone()).isFalse();
                relationship.commit();

                SqlFailure failure = lifecycleWrite.get(10, TimeUnit.SECONDS);
                assertThat(failure.sqlState()).isEqualTo("23514");
                assertThat(failure.message())
                        .contains("MASTER_IN_USE:")
                        .doesNotContain("deadlock detected")
                        .doesNotContain("lock timeout");
                lifecycle.rollback();
            }
        }

        assertThat(finishedGoodService.get(finishedGoodId).status()).isEqualTo("ACTIVE");
    }

    @Test
    void finishedGoodArchiveLosingTheUsageRaceReportsMasterInUseInsteadOfADatabaseError() throws Exception {
        UUID finishedGoodId = finishedGoodService.create(finishedGood("FG-RACE-LOSER-" + UUID.randomUUID()), actor, null).id();
        UUID buyerOrderId = insertBuyerOrder();

        // The service reads usage before it holds the coordination lock, so an uncommitted writer
        // is invisible to that read and commits in between. The database guard must then surface
        // as MASTER_IN_USE rather than an unmapped database error.
        try (Connection writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            assertThat(insertBuyerOrderItem(writer, buyerOrderId, finishedGoodId)).isOne();

            try (var executor = Executors.newSingleThreadExecutor()) {
                var archive = executor.submit(() -> {
                    try {
                        finishedGoodService.archive(finishedGoodId, 0, actor, null);
                        return "archived";
                    } catch (ApiException exception) {
                        return exception.errorCode().name();
                    }
                });
                Thread.sleep(300);
                assertThat(archive.isDone()).isFalse();
                writer.commit();
                assertThat(archive.get(10, TimeUnit.SECONDS)).isEqualTo("MASTER_IN_USE");
            }
        }

        assertThat(finishedGoodService.get(finishedGoodId).status()).isEqualTo("ACTIVE");
    }

    @Test
    void mixedFinishedGoodAndCustomerWritesUseOneGlobalLockWithoutDeadlock() throws Exception {
        UUID finishedGoodId = finishedGoodService.create(finishedGood("FG-MIX-" + UUID.randomUUID()), actor, null).id();
        UUID customerId = partyService.createCustomer(
                new CustomerCreateRequest("FGMIX-" + UUID.randomUUID(), "Mixed", null, null, "USD"), actor, null).id();

        try (Connection product = dataSource.getConnection(); Connection customer = dataSource.getConnection()) {
            product.setAutoCommit(false);
            customer.setAutoCommit(false);
            setConcurrencyTimeouts(product);
            setConcurrencyTimeouts(customer);
            assertThat(renameFinishedGood(product, finishedGoodId, "Held finished good")).isOne();

            try (var executor = Executors.newSingleThreadExecutor()) {
                var customerWrite = executor.submit(() -> renameCustomer(customer, customerId, "Held customer"));
                Thread.sleep(200);
                assertThat(customerWrite.isDone()).isFalse();
                product.rollback();
                assertThat(customerWrite.get(10, TimeUnit.SECONDS)).isOne();
                customer.rollback();
            }
        }
    }

    @Test
    void auditFailureRollsBackRawMaterialMutationInSameTransaction() {
        var created = rawMaterialService.create(rawMaterial("RM-ROLLBACK-" + UUID.randomUUID()), actor, null);
        long auditCount = auditCount(created.id());

        assertThatThrownBy(() -> rawMaterialService.update(created.id(), 0,
                new RawMaterialRequest(null, created.code(), "Renamed", null, null, null,
                        SEEDED_UOM_ID, null, "USD", null, null, null),
                actor, "x".repeat(121)))
                .isInstanceOf(DataAccessException.class);

        var persisted = rawMaterialService.get(created.id());
        assertThat(persisted.name()).isEqualTo("Raw material");
        assertThat(persisted.version()).isZero();
        assertThat(auditCount(created.id())).isEqualTo(auditCount);
    }

    private RawMaterialRequest rawMaterial(String code) {
        return new RawMaterialRequest(null, code, "Raw material", null, null, null,
                SEEDED_UOM_ID, null, "USD", null, null, null);
    }

    private FinishedGoodRequest finishedGood(String styleNo) {
        return new FinishedGoodRequest("PRINT", styleNo, "Finished good", null, null, SEEDED_UOM_ID, null, null);
    }

    private boolean createRawMaterial(CountDownLatch start, String code) {
        await(start);
        try {
            rawMaterialService.create(rawMaterial(code), actor, null);
            return true;
        } catch (ApiException | DataAccessException exception) {
            return false;
        }
    }

    private boolean createFinishedGood(CountDownLatch start, String styleNo) {
        await(start);
        try {
            finishedGoodService.create(finishedGood(styleNo), actor, null);
            return true;
        } catch (ApiException | DataAccessException exception) {
            return false;
        }
    }

    private boolean renameFinishedGood(CountDownLatch start, UUID id, String name) {
        await(start);
        try {
            var current = finishedGoodService.get(id);
            finishedGoodService.update(id, 0, new FinishedGoodRequest(current.productKind(), current.styleNo(), name,
                    current.size(), current.color(), current.uomId(), null, null), actor, null);
            return true;
        } catch (ApiException exception) {
            return false;
        }
    }

    private UUID insertBuyerOrder() {
        UUID customerId = partyService.createCustomer(
                new CustomerCreateRequest("FGORD-" + UUID.randomUUID(), "Order", null, null, "USD"), actor, null).id();
        UUID orderId = UUID.randomUUID();
        jdbc.sql("INSERT INTO sales.buyer_order(id,sys_po_no,order_type,customer_id,customer_name_snapshot,customer_short_name_snapshot,pic_source,pic_name_snapshot,buyer_po,po_date,delivery_date,created_by,updated_by) VALUES(:id,:no,'STANDARD',:customer,'Order','FGORD','CUSTOM','PIC','PO',CURRENT_DATE,CURRENT_DATE,:actor,:actor)")
                .param("id", orderId).param("no", number()).param("customer", customerId)
                .param("actor", actor.user().id()).update();
        return orderId;
    }

    private boolean insertBuyerOrderItem(UUID buyerOrderId, UUID finishedGoodId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertBuyerOrderItem(connection, buyerOrderId, finishedGoodId);
                connection.commit();
                return true;
            } catch (Exception exception) {
                connection.rollback();
                return false;
            }
        } catch (Exception exception) {
            return false;
        }
    }

    private int insertBuyerOrderItem(Connection connection, UUID buyerOrderId, UUID finishedGoodId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO sales.buyer_order_item (
                    id, buyer_order_id, line_no, is_custom, finished_good_id, product_kind_snapshot,
                    style_no_snapshot, name_snapshot, uom_id, uom_code_snapshot, order_qty,
                    production_qty, unit_price, currency_code, amount, created_by, updated_by
                ) VALUES (?, ?, 1, false, ?, 'PRINT', 'STYLE', 'Race item', ?, 'EA', 1, 1, 0, 'USD', 0, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, buyerOrderId);
            statement.setObject(3, finishedGoodId);
            statement.setObject(4, SEEDED_UOM_ID);
            statement.setObject(5, actor.user().id());
            statement.setObject(6, actor.user().id());
            return statement.executeUpdate();
        }
    }

    private SqlFailure archiveFinishedGood(Connection connection, UUID id) {
        try (var statement = connection.prepareStatement(
                "UPDATE master_data.finished_good SET status='ARCHIVED',version=version+1,updated_by=? WHERE id=?")) {
            statement.setObject(1, actor.user().id());
            statement.setObject(2, id);
            statement.executeUpdate();
            return new SqlFailure(null, "success");
        } catch (SQLException exception) {
            return new SqlFailure(exception.getSQLState(), exception.getMessage());
        }
    }

    private int renameFinishedGood(Connection connection, UUID id, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE master_data.finished_good SET name=?, updated_by=? WHERE id=?")) {
            statement.setString(1, name);
            statement.setObject(2, actor.user().id());
            statement.setObject(3, id);
            return statement.executeUpdate();
        }
    }

    private int renameCustomer(Connection connection, UUID id, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE master_data.customer SET name=?, updated_by=? WHERE id=?")) {
            statement.setString(1, name);
            statement.setObject(2, actor.user().id());
            statement.setObject(3, id);
            return statement.executeUpdate();
        }
    }

    private static void setConcurrencyTimeouts(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL deadlock_timeout = '100ms'");
            statement.execute("SET LOCAL lock_timeout = '3s'");
        }
    }

    private long auditCount(UUID entityId) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE entity_id=:id")
                .param("id", entityId).query(Long.class).single();
    }

    private static String number() {
        return "SO-2026-%06d".formatted(Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test interrupted", exception);
        }
    }

    private record SqlFailure(String sqlState, String message) {}
}
