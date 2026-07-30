package com.company.erp.masterdata.application;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.company.erp.api.ApiException;
import com.company.erp.identity.domain.AppUser;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.masterdata.api.MasterDataModels.ContactCreateRequest;
import com.company.erp.masterdata.api.MasterDataModels.ContactUpdateRequest;
import com.company.erp.masterdata.api.MasterDataModels.CustomerCreateRequest;
import com.company.erp.masterdata.api.MasterDataModels.CustomerUpdateRequest;
import com.company.erp.masterdata.api.MasterDataModels.ExchangeRateRequest;
import com.company.erp.masterdata.api.MasterDataModels.ProcessCreateRequest;
import com.company.erp.masterdata.application.PartyMasterService.PartyType;
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
class PartyMasterServiceTest {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired PartyMasterService service;
    @Autowired ProcessMasterService processService;
    @Autowired ReferenceMasterService referenceService;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;

    private ErpPrincipal actor;

    @BeforeEach
    void createActor() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO identity.app_user(id,kind,login_id,password_hash,position,name,status,created_by,updated_by) VALUES(:id,'USER',:login,'{argon2}fixture','QA','Master Test','ACTIVE',:system,:system)")
                .param("id", id).param("login", "party-service-" + id).param("system", SYSTEM_USER_ID).update();
        actor = new ErpPrincipal(new AppUser(id, "USER", "party-service-" + id, "redacted", "Master Test", "ACTIVE", false, 0), UUID.randomUUID(), "access");
    }

    @Test
    void concurrentDefaultPromotionsKeepExactlyOneActiveDefault() throws Exception {
        UUID customerId = service.createCustomer(new CustomerCreateRequest("CONCURRENT-" + UUID.randomUUID(), "Concurrent", null, null, "USD"), actor, null).id();
        UUID first = service.createContact(PartyType.CUSTOMER, customerId, contact("First"), actor, null).id();
        UUID second = service.createContact(PartyType.CUSTOMER, customerId, contact("Second"), actor, null).id();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> promote(start, customerId, first, "First promoted"));
            var secondResult = executor.submit(() -> promote(start, customerId, second, "Second promoted"));
            start.countDown();
            firstResult.get(10, TimeUnit.SECONDS);
            secondResult.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM master_data.customer_contact WHERE customer_id=:id AND status='ACTIVE' AND is_default")
                .param("id", customerId).query(Long.class).single()).isOne();
    }

    @Test
    void concurrentDuplicateAndVersionWritesHaveOneWinner() throws Exception {
        String key = "DUP-" + UUID.randomUUID();
        var start = new CountDownLatch(1);
        List<Boolean> duplicateResults;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createCustomer(start, key));
            var second = executor.submit(() -> createCustomer(start, key));
            start.countDown();
            duplicateResults = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertThat(duplicateResults).containsExactlyInAnyOrder(true, false);

        UUID customerId = service.createCustomer(new CustomerCreateRequest("VERSION-" + UUID.randomUUID(), "Version", null, null, "USD"), actor, null).id();
        var versionStart = new CountDownLatch(1);
        List<Boolean> versionResults;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> updateCustomer(versionStart, customerId, "First"));
            var second = executor.submit(() -> updateCustomer(versionStart, customerId, "Second"));
            versionStart.countDown();
            versionResults = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertThat(versionResults).containsExactlyInAnyOrder(true, false);
        assertThat(service.customer(customerId).version()).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackBusinessMutationInSameTransaction() {
        UUID customerId = service.createCustomer(new CustomerCreateRequest("ROLLBACK-" + UUID.randomUUID(), "Before", "Old address", "111", "USD"), actor, null).id();
        long auditCount = auditCount(customerId);

        assertThatThrownBy(() -> service.updateCustomer(customerId, 0,
                new CustomerUpdateRequest("After", "New address", "222", "VND"), actor, "x".repeat(121)))
                .isInstanceOf(DataAccessException.class);

        var persisted = service.customer(customerId);
        assertThat(persisted.name()).isEqualTo("Before");
        assertThat(persisted.address()).isEqualTo("Old address");
        assertThat(persisted.telephone()).isEqualTo("111");
        assertThat(persisted.currencyCode()).isEqualTo("USD");
        assertThat(persisted.version()).isZero();
        assertThat(auditCount(customerId)).isEqualTo(auditCount);
    }

    @Test
    void bulkCustomerRelationshipAndReverseOrderLifecycleUpdatesDoNotDeadlock() throws Exception {
        UUID firstCustomerId = service.createCustomer(new CustomerCreateRequest(
                "BULK-FIRST-" + UUID.randomUUID(), "Bulk first", null, null, "USD"), actor, null).id();
        UUID secondCustomerId = service.createCustomer(new CustomerCreateRequest(
                "BULK-SECOND-" + UUID.randomUUID(), "Bulk second", null, null, "USD"), actor, null).id();

        try (Connection relationship = dataSource.getConnection();
                Connection lifecycle = dataSource.getConnection()) {
            relationship.setAutoCommit(false);
            lifecycle.setAutoCommit(false);
            setConcurrencyTimeouts(relationship);
            setConcurrencyTimeouts(lifecycle);

            try (var executor = Executors.newFixedThreadPool(2)) {
                var relationshipResult = executor.submit(() -> insertBuyerOrders(
                        relationship, List.of(secondCustomerId, firstCustomerId)));
                assertThat(awaitRelationshipLock(relationship)).isTrue();
                var lifecycleResult = executor.submit(() -> archiveCustomers(
                        lifecycle, List.of(firstCustomerId, secondCustomerId)));

                relationship.commit();
                assertThat(relationshipResult.get(10, TimeUnit.SECONDS)).isEqualTo(2);
                assertThatThrownBy(() -> lifecycleResult.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasRootCauseInstanceOf(SQLException.class)
                        .rootCause()
                        .hasMessageContaining("MASTER_IN_USE:")
                        .hasMessageNotContaining("deadlock detected")
                        .hasMessageNotContaining("lock timeout");
                lifecycle.rollback();
            }
        }

        assertThat(service.customer(firstCustomerId).status()).isEqualTo("ACTIVE");
        assertThat(service.customer(secondCustomerId).status()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM sales.buyer_order WHERE customer_id IN (:first, :second)")
                .param("first", firstCustomerId)
                .param("second", secondCustomerId)
                .query(Long.class)
                .single()).isEqualTo(2);
    }

    @Test
    void relationshipWritersThatBothRequestLifecycleShareLockDoNotDeadlock() throws Exception {
        UUID firstCustomerId = service.createCustomer(new CustomerCreateRequest(
                "UF-" + UUID.randomUUID(), "Upgrade first", null, null, "USD"), actor, null).id();
        UUID secondCustomerId = service.createCustomer(new CustomerCreateRequest(
                "US-" + UUID.randomUUID(), "Upgrade second", null, null, "USD"), actor, null).id();

        try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            setConcurrencyTimeouts(first);
            setConcurrencyTimeouts(second);
            insertBuyerOrders(first, List.of(firstCustomerId));

            try (var executor = Executors.newSingleThreadExecutor()) {
                var secondRelationship = executor.submit(() -> insertBuyerOrders(second, List.of(secondCustomerId)));
                Thread.sleep(200);
                assertThat(secondRelationship.isDone()).isFalse();

                SqlFailure firstFailure = archiveCustomersCapturingFailure(
                        new CountDownLatch(0), first, List.of(firstCustomerId));
                assertThat(firstFailure.sqlState()).isEqualTo("23514");
                assertThat(firstFailure.message()).contains("MASTER_IN_USE:").doesNotContain("deadlock detected");
                first.rollback();

                assertThat(secondRelationship.get(10, TimeUnit.SECONDS)).isOne();
                SqlFailure secondFailure = archiveCustomersCapturingFailure(
                        new CountDownLatch(0), second, List.of(secondCustomerId));
                assertThat(secondFailure.sqlState()).isEqualTo("23514");
                assertThat(secondFailure.message()).contains("MASTER_IN_USE:").doesNotContain("deadlock detected");
                second.rollback();
            } finally {
                first.rollback();
                second.rollback();
            }
        }
    }

    @Test
    void mixedRateThenDeliveryAndDeliveryThenRateWritesUseOneGlobalLockWithoutDeadlock() throws Exception {
        var firstRate = referenceService.createRate(
                new ExchangeRateRequest("2032-01", "25000", "1300", "first"), actor, null);
        var secondRate = referenceService.createRate(
                new ExchangeRateRequest("2032-02", "25000", "1300", "second"), actor, null);
        UUID customerId = service.createCustomer(new CustomerCreateRequest(
                "MIXED-" + UUID.randomUUID(), "Mixed", null, null, "USD"), actor, null).id();

        try (Connection rateThenDelivery = dataSource.getConnection();
                Connection deliveryThenRate = dataSource.getConnection()) {
            rateThenDelivery.setAutoCommit(false);
            deliveryThenRate.setAutoCommit(false);
            setConcurrencyTimeouts(rateThenDelivery);
            setConcurrencyTimeouts(deliveryThenRate);

            assertThat(updateRateSource(rateThenDelivery, firstRate.id(), "held-first")).isOne();
            try (var executor = Executors.newSingleThreadExecutor()) {
                var deliveryWrite = executor.submit(() -> insertDraftDelivery(
                        deliveryThenRate, customerId, secondRate.id()));
                Thread.sleep(200);
                assertThat(deliveryWrite.isDone()).isFalse();

                assertThat(insertDraftDelivery(rateThenDelivery, customerId, firstRate.id())).isOne();
                rateThenDelivery.rollback();

                assertThat(deliveryWrite.get(10, TimeUnit.SECONDS)).isOne();
                assertThat(updateRateSource(deliveryThenRate, secondRate.id(), "held-second")).isOne();
                deliveryThenRate.rollback();
            }
        }
    }

    @Test
    void mixedCustomerAndProcessWritesUseOneGlobalLockWithoutDeadlock() throws Exception {
        UUID customerId = service.createCustomer(new CustomerCreateRequest(
                "MIXED-CP-" + UUID.randomUUID(), "Mixed customer", null, null, "USD"), actor, null).id();
        UUID processId = processService.create(new ProcessCreateRequest(
                "Mixed process " + UUID.randomUUID(), 1, "MIXED-QR-" + UUID.randomUUID()), actor, null).id();

        try (Connection customer = dataSource.getConnection(); Connection process = dataSource.getConnection()) {
            customer.setAutoCommit(false);
            process.setAutoCommit(false);
            setConcurrencyTimeouts(customer);
            setConcurrencyTimeouts(process);
            assertThat(renameCustomer(customer, customerId, "Held customer")).isOne();

            try (var executor = Executors.newSingleThreadExecutor()) {
                var processWrite = executor.submit(() -> renameProcess(process, processId, "Held process"));
                Thread.sleep(200);
                assertThat(processWrite.isDone()).isFalse();
                customer.rollback();
                assertThat(processWrite.get(10, TimeUnit.SECONDS)).isOne();
                process.rollback();
            }
        }
    }

    @Test
    void customerArchiveAndDirectRelationshipInsertCannotBothCommit() throws Exception {
        UUID customerId = service.createCustomer(new CustomerCreateRequest("RACE-" + UUID.randomUUID(), "Race", null, null, "USD"), actor, null).id();
        try (Connection archive = dataSource.getConnection()) {
            archive.setAutoCommit(false);
            try (var statement = archive.prepareStatement("UPDATE master_data.customer SET status='ARCHIVED',version=version+1,updated_by=? WHERE id=?")) {
                statement.setObject(1, actor.user().id());
                statement.setObject(2, customerId);
                assertThat(statement.executeUpdate()).isOne();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var relationship = executor.submit(() -> insertBuyerOrder(customerId));
                Thread.sleep(200);
                assertThat(relationship.isDone()).isFalse();
                archive.commit();
                assertThat(relationship.get(10, TimeUnit.SECONDS)).isFalse();
            }
        }
        assertThat(service.customer(customerId).status()).isEqualTo("ARCHIVED");
        assertThat(jdbc.sql("SELECT count(*) FROM sales.buyer_order WHERE customer_id=:id").param("id", customerId).query(Long.class).single()).isZero();
    }

    @Test
    void processArchiveAndDirectRelationshipInsertCannotBothCommit() throws Exception {
        UUID processId = processService.create(new ProcessCreateRequest("Race " + UUID.randomUUID(), 1, "QR-" + UUID.randomUUID()), actor, null).id();
        ProductionFixture fixture = productionFixture();
        try (Connection archive = dataSource.getConnection()) {
            archive.setAutoCommit(false);
            try (var statement = archive.prepareStatement("UPDATE master_data.process_master SET status='ARCHIVED',version=version+1,updated_by=? WHERE id=?")) {
                statement.setObject(1, actor.user().id());
                statement.setObject(2, processId);
                assertThat(statement.executeUpdate()).isOne();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var relationship = executor.submit(() -> insertProcessUsage(fixture.productionOrderId(), processId));
                Thread.sleep(200);
                assertThat(relationship.isDone()).isFalse();
                archive.commit();
                assertThat(relationship.get(10, TimeUnit.SECONDS)).isFalse();
            }
        }
        assertThat(processService.get(processId).status()).isEqualTo("ARCHIVED");
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_order_process WHERE process_id=:id")
                .param("id", processId).query(Long.class).single()).isZero();
    }

    @Test
    void exchangeRateArchiveAndDirectDeliveryReferenceCannotBothCommit() throws Exception {
        var rate = referenceService.createRate(new ExchangeRateRequest("2031-01", "25000", "1300", "race"), actor, null);
        UUID customerId = service.createCustomer(new CustomerCreateRequest("RATE-RACE-" + UUID.randomUUID(), "Rate race", null, null, "USD"), actor, null).id();
        try (Connection archive = dataSource.getConnection()) {
            archive.setAutoCommit(false);
            try (var statement = archive.prepareStatement("UPDATE master_data.monthly_exchange_rate SET status='ARCHIVED',version=version+1,updated_by=? WHERE id=?")) {
                statement.setObject(1, actor.user().id());
                statement.setObject(2, rate.id());
                assertThat(statement.executeUpdate()).isOne();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var relationship = executor.submit(() -> insertDraftDelivery(customerId, rate.id()));
                Thread.sleep(200);
                assertThat(relationship.isDone()).isFalse();
                archive.commit();
                assertThat(relationship.get(10, TimeUnit.SECONDS)).isFalse();
            }
        }
        assertThat(referenceService.rate(rate.id()).status()).isEqualTo("ARCHIVED");
        assertThat(jdbc.sql("SELECT count(*) FROM delivery.delivery_note WHERE exchange_rate_id=:id")
                .param("id", rate.id()).query(Long.class).single()).isZero();
    }

    private ContactCreateRequest contact(String name) {
        return new ContactCreateRequest(null, name, null, null, null, false);
    }

    private boolean promote(CountDownLatch start, UUID customerId, UUID contactId, String name) {
        await(start);
        service.updateContact(PartyType.CUSTOMER, customerId, contactId, 0,
                new ContactUpdateRequest(null, name, null, null, null, true), actor, null);
        return true;
    }

    private boolean createCustomer(CountDownLatch start, String key) {
        await(start);
        try {
            service.createCustomer(new CustomerCreateRequest(key, "Duplicate", null, null, "USD"), actor, null);
            return true;
        } catch (ApiException | DataAccessException exception) {
            return false;
        }
    }

    private boolean updateCustomer(CountDownLatch start, UUID id, String name) {
        await(start);
        try {
            service.updateCustomer(id, 0, new CustomerUpdateRequest(name, null, null, "USD"), actor, null);
            return true;
        } catch (ApiException exception) {
            return false;
        }
    }

    private boolean insertBuyerOrder(UUID customerId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertBuyerOrders(connection, List.of(customerId));
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

    private int insertBuyerOrders(Connection connection, List<UUID> customerIds) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO sales.buyer_order (
                    id, sys_po_no, order_type, customer_id, customer_name_snapshot,
                    customer_short_name_snapshot, pic_source, pic_name_snapshot, buyer_po,
                    po_date, delivery_date, created_by, updated_by
                )
                SELECT gen_random_uuid(), 'SO-2026-' || lpad(
                           (abs(hashtext(gen_random_uuid()::text)) % 1000000)::text, 6, '0'),
                       'STANDARD', fixture.customer_id, 'Bulk race', 'BULK', 'CUSTOM', 'PIC', 'PO',
                       CURRENT_DATE, CURRENT_DATE, ?, ?
                FROM unnest(?::uuid[]) AS fixture(customer_id)
                """)) {
            statement.setObject(1, actor.user().id());
            statement.setObject(2, actor.user().id());
            statement.setArray(3, connection.createArrayOf("uuid", customerIds.toArray()));
            return statement.executeUpdate();
        }
    }

    private int archiveCustomers(Connection connection, List<UUID> customerIds) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE master_data.customer
                SET status = 'ARCHIVED', version = version + 1, updated_by = ?
                WHERE id IN (SELECT unnest(?::uuid[]))
                """)) {
            statement.setObject(1, actor.user().id());
            statement.setArray(2, connection.createArrayOf("uuid", customerIds.toArray()));
            return statement.executeUpdate();
        }
    }

    private SqlFailure archiveCustomersCapturingFailure(
            CountDownLatch start, Connection connection, List<UUID> customerIds) {
        await(start);
        try {
            archiveCustomers(connection, customerIds);
            return new SqlFailure(null, "success");
        } catch (SQLException exception) {
            return new SqlFailure(exception.getSQLState(), exception.getMessage());
        }
    }

    private static void setConcurrencyTimeouts(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL deadlock_timeout = '100ms'");
            statement.execute("SET LOCAL lock_timeout = '3s'");
        }
    }

    private boolean awaitRelationshipLock(Connection relationship) throws SQLException {
        long backendPid;
        try (var statement = relationship.createStatement();
                var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            backendPid = result.getLong(1);
        }
        for (int attempt = 0; attempt < 100; attempt++) {
            Boolean locked = jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1
                                FROM pg_locks
                                WHERE pid = :pid
                                  AND relation = 'sales.buyer_order'::regclass
                                  AND mode = 'RowExclusiveLock'
                                  AND granted
                            )
                            """)
                    .param("pid", backendPid)
                    .query(Boolean.class)
                    .single();
            if (Boolean.TRUE.equals(locked)) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for relationship table lock", exception);
            }
        }
        return false;
    }

    private ProductionFixture productionFixture() {
        UUID customerId = service.createCustomer(new CustomerCreateRequest("PROCESS-RACE-" + UUID.randomUUID(), "Process race", null, null, "USD"), actor, null).id();
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID productionId = UUID.randomUUID();
        jdbc.sql("INSERT INTO sales.buyer_order(id,sys_po_no,order_type,customer_id,customer_name_snapshot,customer_short_name_snapshot,pic_source,pic_name_snapshot,buyer_po,po_date,delivery_date,created_by,updated_by) VALUES(:id,:no,'STANDARD',:customer,'Process race','PROCESS','CUSTOM','PIC','PO',CURRENT_DATE,CURRENT_DATE,:actor,:actor)")
                .param("id", orderId).param("no", number("SO")).param("customer", customerId).param("actor", actor.user().id()).update();
        jdbc.sql("INSERT INTO sales.buyer_order_item(id,buyer_order_id,line_no,is_custom,product_kind_snapshot,style_no_snapshot,name_snapshot,uom_id,uom_code_snapshot,order_qty,production_qty,unit_price,currency_code,amount,created_by,updated_by) VALUES(:id,:order,1,true,'PRINT','STYLE','Fixture','10000000-0000-0000-0000-000000000002','EA',1,1,0,'USD',0,:actor,:actor)")
                .param("id", itemId).param("order", orderId).param("actor", actor.user().id()).update();
        jdbc.sql("INSERT INTO production.production_order(id,production_no,buyer_order_item_id,buyer_order_id,product_kind_snapshot,product_no,qr_value,planned_qty,created_by,updated_by) VALUES(:id,:no,:item,:order,'PRINT','FIXTURE',:qr,1,:actor,:actor)")
                .param("id", productionId).param("no", number("PR")).param("item", itemId).param("order", orderId)
                .param("qr", "PROD-" + UUID.randomUUID()).param("actor", actor.user().id()).update();
        return new ProductionFixture(productionId);
    }

    private boolean insertProcessUsage(UUID productionOrderId, UUID processId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("INSERT INTO production.production_order_process(production_order_id,process_id,sequence_no) VALUES(?,?,1)")) {
                statement.setObject(1, productionOrderId);
                statement.setObject(2, processId);
                statement.executeUpdate();
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

    private boolean insertDraftDelivery(UUID customerId, UUID rateId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertDraftDelivery(connection, customerId, rateId);
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

    private int insertDraftDelivery(Connection connection, UUID customerId, UUID rateId) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO delivery.delivery_note(id,customer_id,customer_name_snapshot,delivery_date,currency_code,exchange_rate_id,total_qty,total_amount,created_by,updated_by) VALUES(?,?,'Rate race',DATE '2031-01-15','USD',?,0,0,?,?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, customerId);
            statement.setObject(3, rateId);
            statement.setObject(4, actor.user().id());
            statement.setObject(5, actor.user().id());
            return statement.executeUpdate();
        }
    }

    private int updateRateSource(Connection connection, UUID rateId, String source) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE master_data.monthly_exchange_rate SET source=?, updated_by=? WHERE id=?")) {
            statement.setString(1, source);
            statement.setObject(2, actor.user().id());
            statement.setObject(3, rateId);
            return statement.executeUpdate();
        }
    }

    private int renameCustomer(Connection connection, UUID customerId, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE master_data.customer SET name=?, updated_by=? WHERE id=?")) {
            statement.setString(1, name);
            statement.setObject(2, actor.user().id());
            statement.setObject(3, customerId);
            return statement.executeUpdate();
        }
    }

    private int renameProcess(Connection connection, UUID processId, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE master_data.process_master SET name=?, updated_by=? WHERE id=?")) {
            statement.setString(1, name);
            statement.setObject(2, actor.user().id());
            statement.setObject(3, processId);
            return statement.executeUpdate();
        }
    }

    private static String number(String prefix) {
        return "%s-2026-%06d".formatted(prefix, Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000));
    }

    private record ProductionFixture(UUID productionOrderId) {}

    private record SqlFailure(String sqlState, String message) {}

    private long auditCount(UUID entityId) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE entity_id=:id").param("id", entityId).query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test did not start");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test interrupted", exception);
        }
    }
}
