package com.company.erp.sales;

import com.company.erp.sales.application.BuyerOrderService;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderCommandRequest;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderUpdateRequest;
import com.company.erp.sales.api.BuyerOrderModels.StandardBuyerOrderItemRequest;
import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.identity.security.ErpPrincipal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BuyerOrderConcurrencyIT extends BuyerOrderTestSupport {

    @Autowired
    private BuyerOrderService service;

    @Autowired
    private IdentityJdbcRepository identityRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void prepare() {
        prepareBuyerOrderReferences();
    }

    @AfterEach
    void removeCommittedRecoveryCapability() {
        jdbc.sql("DELETE FROM identity.user_role WHERE user_id = :adminId")
                .param("adminId", adminId).update();
    }

    @Test
    void twoStaleUpdatesHaveExactlyOneWinnerAndOneVersionConflict() throws Exception {
        UUID orderId = createOrder(standardRequest());
        ErpPrincipal actor = new ErpPrincipal(identityRepository.findById(saleId).orElseThrow(), saleSessionId, "session");
        BuyerOrderUpdateRequest request = new BuyerOrderUpdateRequest(
                "STANDARD", customerId, contactId, "MASTER", "Buyer contact", "CONCURRENT-PO",
                java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 20),
                List.of(new StandardBuyerOrderItemRequest(false, finishedGoodId, "1.0000", "2.000000", null)));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> updateOutcome(orderId, request, actor, ready, start));
            Future<String> second = executor.submit(() -> updateOutcome(orderId, request, actor, ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("SUCCESS", "VERSION_CONFLICT");
        }

        assertThat(jdbc.sql("SELECT version FROM sales.buyer_order WHERE id = :id")
                .param("id", orderId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM sales.buyer_order_item WHERE buyer_order_id = :id AND active_revision")
                .param("id", orderId).query(Long.class).single()).isOne();
    }

    @Test
    void doubleConfirmWithDifferentKeysCreatesExactlyOneProductionSet() throws Exception {
        UUID orderId = createOrder(standardRequest());
        ErpPrincipal actor = new ErpPrincipal(
                identityRepository.findById(saleId).orElseThrow(), saleSessionId, "session");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> confirmOutcome(orderId, actor, ready, start));
            Future<String> second = executor.submit(() -> confirmOutcome(orderId, actor, ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("SUCCESS", "VERSION_CONFLICT");
        }

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_order WHERE buyer_order_id = :id")
                .param("id", orderId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM production.production_event pe
                        JOIN production.production_order po ON po.id = pe.production_order_id
                        WHERE po.buyer_order_id = :id AND pe.event_type = 'CREATED'
                        """).param("id", orderId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE entity_id = :id AND action = 'CONFIRM'")
                .param("id", orderId).query(Long.class).single()).isOne();
    }

    @Test
    void contactArchiveWaitsForConfirmMasterLock() throws Exception {
        UUID orderId = createOrder(standardRequest());
        ErpPrincipal actor = new ErpPrincipal(
                identityRepository.findById(saleId).orElseThrow(), saleSessionId, "session");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Void> confirm = executor.submit(() -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.executeWithoutResult(status -> {
                    service.confirm(orderId, 0, new BuyerOrderCommandRequest("master lock"), actor,
                            "lock-confirm-" + UUID.randomUUID(), "lock-confirm");
                    locked.countDown();
                    await(release);
                });
                return null;
            });
            locked.await();
            Future<Integer> archive = executor.submit(() -> jdbc.sql("""
                            UPDATE master_data.customer_contact
                            SET status = 'ARCHIVED', is_default = false
                            WHERE id = :id
                            """).param("id", contactId).update());

            assertThat(archive.isDone()).isFalse();
            release.countDown();
            confirm.get();
            assertThat(archive.get()).isOne();
        }

        assertThat(jdbc.sql("SELECT status FROM sales.buyer_order WHERE id = :id")
                .param("id", orderId).query(String.class).single()).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT status FROM master_data.customer_contact WHERE id = :id")
                .param("id", contactId).query(String.class).single()).isEqualTo("ARCHIVED");
    }

    @Test
    void updateAndConfirmRaceLeavesEitherOneUpdatedDraftOrOneCoherentConfirmation() throws Exception {
        UUID orderId = createOrder(standardRequest());
        ErpPrincipal actor = new ErpPrincipal(identityRepository.findById(saleId).orElseThrow(), saleSessionId, "session");
        BuyerOrderUpdateRequest update = new BuyerOrderUpdateRequest(
                "STANDARD", customerId, contactId, "MASTER", "Buyer contact", "RACE-UPDATE",
                java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 20),
                List.of(new StandardBuyerOrderItemRequest(false, finishedGoodId, "1.0000", "2.000000", null)));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> updateResult = executor.submit(() -> updateOutcome(orderId, update, actor, ready, start));
            Future<String> confirmResult = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.confirm(orderId, 0, new BuyerOrderCommandRequest("race confirm"), actor,
                            "race-confirm-" + UUID.randomUUID(), "race-confirm");
                    return "SUCCESS";
                } catch (com.company.erp.api.ApiException exception) {
                    return exception.errorCode().name();
                }
            });
            ready.await();
            start.countDown();

            assertThat(List.of(updateResult.get(), confirmResult.get()))
                    .anySatisfy(value -> assertThat(value).isEqualTo("SUCCESS"));
        }

        String status = jdbc.sql("SELECT status FROM sales.buyer_order WHERE id = :id")
                .param("id", orderId).query(String.class).single();
        long version = jdbc.sql("SELECT version FROM sales.buyer_order WHERE id = :id")
                .param("id", orderId).query(Long.class).single();
        long productionCount = jdbc.sql("SELECT count(*) FROM production.production_order WHERE buyer_order_id = :id")
                .param("id", orderId).query(Long.class).single();
        assertThat(version).isEqualTo(1);
        if ("CONFIRMED".equals(status)) {
            assertThat(productionCount).isOne();
        } else {
            assertThat(status).isEqualTo("STANDBY");
            assertThat(productionCount).isZero();
        }
    }

    private String confirmOutcome(
            UUID orderId, ErpPrincipal actor, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            service.confirm(orderId, 0, new BuyerOrderCommandRequest("competing confirm"), actor,
                    "double-confirm-" + UUID.randomUUID(), "double-confirm");
            return "SUCCESS";
        } catch (com.company.erp.api.ApiException exception) {
            return exception.errorCode().name();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the master reference lock", exception);
        }
    }

    private String updateOutcome(
            UUID orderId, BuyerOrderUpdateRequest request, ErpPrincipal actor,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            service.update(orderId, 0, request, actor, "race-update-" + UUID.randomUUID(), "race-update");
            return "SUCCESS";
        } catch (com.company.erp.api.ApiException exception) {
            return exception.errorCode().name();
        }
    }
}
