package com.company.erp.production;

import com.company.erp.api.ApiException;
import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.production.api.ProductionModels.ProductionCommandRequest;
import com.company.erp.production.api.ProductionModels.ProductionGroupCreateRequest;
import com.company.erp.production.api.ProductionModels.ProductionGroupMemberRequest;
import com.company.erp.production.application.ProductionService;
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
class ProductionConcurrencyIT extends ProductionTestSupport {

    @Autowired private ProductionService service;
    @Autowired private IdentityJdbcRepository identityRepository;
    @Autowired private PlatformTransactionManager transactionManager;

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
    void oppositeMemberOrderHasOneCoherentWinnerWithoutOneMemberGroup() throws Exception {
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, "1.0000"), customItem("WOVEN-RACE", "1.0000")));
        List<UUID> ids = productionIds(buyerOrderId);
        ErpPrincipal actor = new ErpPrincipal(identityRepository.findById(saleId).orElseThrow(), saleSessionId, "session");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> groupOutcome(ids, actor, ready, start));
            Future<String> second = executor.submit(() -> groupOutcome(List.of(ids.get(1), ids.get(0)), actor, ready, start));
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("SUCCESS", "VERSION_CONFLICT");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_group WHERE buyer_order_id = :id")
                .param("id", buyerOrderId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_order WHERE buyer_order_id = :id AND production_group_id IS NOT NULL")
                .param("id", buyerOrderId).query(Long.class).single()).isEqualTo(2);
    }

    /**
     * Finish is the only path that creates stock. Two racing finishes must leave exactly one
     * FINISHED order, one Stock Position and one PRODUCTION movement.
     */
    @Test
    void doubleFinishCreatesStockExactlyOnce() throws Exception {
        UUID processId = insertProcess("Race process " + UUID.randomUUID(), "PROCESS-" + UUID.randomUUID());
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, "4.0000")));
        UUID productionId = productionIds(buyerOrderId).getFirst();
        ErpPrincipal actor = principal();
        configure(productionId, processId, actor);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> finishOutcome(productionId, actor, ready, start));
            Future<String> second = executor.submit(() -> finishOutcome(productionId, actor, ready, start));
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).contains("SUCCESS");
            assertThat(List.of(first.get(), second.get()))
                    .containsAnyOf("VERSION_CONFLICT", "PRODUCTION_ALREADY_FINISHED");
        }

        assertThat(jdbc.sql("SELECT count(*) FROM inventory.stock_position WHERE production_order_id = :id")
                .param("id", productionId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM inventory.stock_movement
                        WHERE source_id = :id AND movement_type = 'PRODUCTION'
                        """).param("id", productionId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM production.production_event
                        WHERE production_order_id = :id AND event_type = 'FINISHED'
                        """).param("id", productionId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT reconciled FROM inventory.stock_ledger_reconciliation
                        WHERE production_order_id = :id
                        """).param("id", productionId).query(Boolean.class).single()).isTrue();
    }

    /** Configure and finish contend for the same order version; the loser must not corrupt either state. */
    @Test
    void finishVersusConfigureLeavesOneCoherentProductionState() throws Exception {
        UUID processId = insertProcess("Config race " + UUID.randomUUID(), "PROCESS-" + UUID.randomUUID());
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, "2.0000")));
        UUID productionId = productionIds(buyerOrderId).getFirst();
        ErpPrincipal actor = principal();
        configure(productionId, processId, actor);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> finish = executor.submit(() -> finishOutcome(productionId, actor, ready, start));
            Future<String> configure = executor.submit(() -> configureOutcome(productionId, processId, actor, ready, start));
            ready.await();
            start.countDown();
            List<String> outcomes = List.of(finish.get(), configure.get());
            assertThat(outcomes).contains("SUCCESS");
            assertThat(outcomes.stream().filter("SUCCESS"::equals).count()).isLessThanOrEqualTo(2);
        }

        String status = jdbc.sql("SELECT status FROM production.production_order WHERE id = :id")
                .param("id", productionId).query(String.class).single();
        long positions = jdbc.sql("SELECT count(*) FROM inventory.stock_position WHERE production_order_id = :id")
                .param("id", productionId).query(Long.class).single();
        assertThat(positions).isEqualTo("FINISHED".equals(status) ? 1 : 0);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM production.production_print_config
                        WHERE production_order_id = :id
                        """).param("id", productionId).query(Long.class).single()).isOne();
    }

    private ErpPrincipal principal() {
        return new ErpPrincipal(identityRepository.findById(saleId).orElseThrow(), saleSessionId, "session");
    }

    private void configure(UUID productionId, UUID processId, ErpPrincipal actor) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.configure(productionId, 0, printConfigurationRequest(processId), actor, null));
    }

    private String finishOutcome(UUID productionId, ErpPrincipal actor, CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    service.finish(productionId, 1, new ProductionCommandRequest("race"), UUID.randomUUID(),
                            actor, null, java.time.LocalDate.of(2026, 8, 12)));
            return "SUCCESS";
        } catch (ApiException exception) {
            return exception.errorCode().name();
        } catch (RuntimeException exception) {
            return "ROLLED_BACK";
        }
    }

    private String configureOutcome(UUID productionId, UUID processId, ErpPrincipal actor,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    service.configure(productionId, 1, printConfigurationRequest(processId), actor, null));
            return "SUCCESS";
        } catch (ApiException exception) {
            return exception.errorCode().name();
        } catch (RuntimeException exception) {
            return "ROLLED_BACK";
        }
    }

    private static com.company.erp.production.api.ProductionModels.ProductionConfigurationRequest
            printConfigurationRequest(UUID processId) {
        return new com.company.erp.production.api.ProductionModels.ProductionConfigurationRequest(
                "PRINT", "STOCK", "NEW_ORDER", null, null, null, null, null, null, null, null, null, null,
                List.of(),
                List.of(new com.company.erp.production.api.ProductionModels.ProductionProcessRequest(
                        processId, 1, "12.5000")),
                List.of(), "race configuration");
    }

    private String groupOutcome(List<UUID> ids, ErpPrincipal actor, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.executeWithoutResult(status -> service.group(new ProductionGroupCreateRequest(List.of(
                    new ProductionGroupMemberRequest(ids.get(0), 0), new ProductionGroupMemberRequest(ids.get(1), 0)), "race"),
                    actor, null, "race-" + UUID.randomUUID()));
            return "SUCCESS";
        } catch (ApiException exception) {
            return exception.errorCode().name();
        }
    }
}
