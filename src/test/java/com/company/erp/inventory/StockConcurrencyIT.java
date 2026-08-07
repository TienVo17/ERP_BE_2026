package com.company.erp.inventory;

import com.company.erp.api.ApiException;
import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.inventory.api.StockModels.StockDisposalRequest;
import com.company.erp.inventory.application.StockService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StockConcurrencyIT extends StockTestSupport {

    @Autowired
    private StockService service;
    @Autowired
    private IdentityJdbcRepository identityRepository;

    @BeforeEach
    void prepare() {
        prepareProductionReferences();
    }

    @AfterEach
    void removeCommittedAdminRoleFixture() {
        jdbc.sql("DELETE FROM identity.user_role WHERE user_id = :adminId")
                .param("adminId", adminId)
                .update();
    }

    @Test
    void twoDisposalsSerializeAndCannotMakeStockNegative() throws Exception {
        UUID positionId = finishedStock("5.0000");
        ErpPrincipal actor = new ErpPrincipal(identityRepository.findById(saleId).orElseThrow(),
                saleSessionId, "session");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> dispose(positionId, actor, ready, start));
            Future<String> second = executor.submit(() -> dispose(positionId, actor, ready, start));
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("SUCCESS", "VERSION_CONFLICT");
        }
        assertThat(jdbc.sql("SELECT current_qty FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("2");
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.stock_movement WHERE stock_position_id = :id AND movement_type = 'DISPOSE'")
                .param("id", positionId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT reconciled FROM inventory.stock_ledger_reconciliation WHERE stock_position_id = :id")
                .param("id", positionId).query(Boolean.class).single()).isTrue();
    }

    /**
     * A Return and a Disposal touch the same balance from opposite directions. They must serialize
     * on the Position version so the ledger cannot record both against one stale read.
     */
    @Test
    void returnAndDisposalSerializeOnTheSamePositionVersion() throws Exception {
        UUID positionId = finishedStock("5.0000");
        UUID deliveryItemId = postedDeliveryItem(positionId, "2.0000");
        ErpPrincipal actor = principal();
        long version = positionVersion(positionId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> returned = executor.submit(
                    () -> recordReturn(positionId, deliveryItemId, version, "1.0000", actor, ready, start));
            Future<String> disposed = executor.submit(
                    () -> dispose(positionId, version, "1.0000", actor, ready, start));
            ready.await();
            start.countDown();
            assertThat(List.of(returned.get(), disposed.get()))
                    .containsExactlyInAnyOrder("SUCCESS", "VERSION_CONFLICT");
        }

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM inventory.stock_movement
                        WHERE stock_position_id = :id AND movement_type IN ('RETURN', 'DISPOSE')
                        """).param("id", positionId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT reconciled FROM inventory.stock_ledger_reconciliation WHERE stock_position_id = :id")
                .param("id", positionId).query(Boolean.class).single()).isTrue();
    }

    /** Two returns racing on one delivered line must never exceed the delivered quantity. */
    @Test
    void twoReturnsOnOneDeliveryLineCannotExceedDeliveredQuantity() throws Exception {
        UUID positionId = finishedStock("5.0000");
        UUID deliveryItemId = postedDeliveryItem(positionId, "2.0000");
        ErpPrincipal actor = principal();
        long version = positionVersion(positionId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(
                    () -> recordReturn(positionId, deliveryItemId, version, "2.0000", actor, ready, start));
            Future<String> second = executor.submit(
                    () -> recordReturn(positionId, deliveryItemId, version, "2.0000", actor, ready, start));
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("SUCCESS", "VERSION_CONFLICT");
        }

        assertThat(jdbc.sql("""
                        SELECT COALESCE(sum(quantity_signed), 0) FROM inventory.stock_movement
                        WHERE movement_type = 'RETURN' AND return_source_delivery_item_id = :id
                        """).param("id", deliveryItemId).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("2");
        assertThat(jdbc.sql("SELECT reconciled FROM inventory.stock_ledger_reconciliation WHERE stock_position_id = :id")
                .param("id", positionId).query(Boolean.class).single()).isTrue();
    }

    private ErpPrincipal principal() {
        return new ErpPrincipal(identityRepository.findById(saleId).orElseThrow(), saleSessionId, "session");
    }

    private long positionVersion(UUID positionId) {
        return jdbc.sql("SELECT version FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(Long.class).single();
    }

    private String recordReturn(UUID positionId, UUID deliveryItemId, long version, String quantity,
            ErpPrincipal actor, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            service.recordReturn(positionId, version,
                    new com.company.erp.inventory.api.StockModels.StockReturnRequest(
                            deliveryItemId, quantity, LocalDate.of(2026, 8, 13), "race return"),
                    UUID.randomUUID(), actor, null);
            return "SUCCESS";
        } catch (ApiException exception) {
            return exception.errorCode().name();
        } catch (RuntimeException exception) {
            return "ROLLED_BACK";
        }
    }

    private String dispose(UUID positionId, long version, String quantity, ErpPrincipal actor,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            service.dispose(positionId, version,
                    new StockDisposalRequest(quantity, LocalDate.of(2026, 8, 12), "race"),
                    UUID.randomUUID(), actor, null);
            return "SUCCESS";
        } catch (ApiException exception) {
            return exception.errorCode().name();
        } catch (RuntimeException exception) {
            return "ROLLED_BACK";
        }
    }

    private String dispose(UUID positionId, ErpPrincipal actor, CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            service.dispose(positionId, 0,
                    new StockDisposalRequest("3.0000", LocalDate.of(2026, 8, 12), "race"),
                    UUID.randomUUID(), actor, null);
            return "SUCCESS";
        } catch (ApiException exception) {
            return exception.errorCode().name();
        }
    }
}
