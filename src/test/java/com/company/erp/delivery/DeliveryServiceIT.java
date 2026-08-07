package com.company.erp.delivery;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeliveryServiceIT extends DeliveryTestSupport {

    @BeforeEach
    void prepare() {
        prepareProductionReferences();
        ensureDeliveryMonthRate();
    }

    @Test
    void postConsumesStockAllocatesNumberAndSnapshotsTheMonthlyRate() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        UUID deliveryId = createDraft(draftRequest(positionId, "2.0000"));
        String key = "post-" + UUID.randomUUID();
        Map<String, Object> command = Map.of("reason", "shipping today");

        MvcResult first = mockMvc.perform(post("/api/v1/delivery-notes/{id}/post", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON)
                        .content(json(command)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.deliveryNo").value(
                        org.hamcrest.Matchers.matchesPattern("DN-2026-[0-9]{6}")))
                .andExpect(jsonPath("$.vndUsdRate").value("25000.000000"))
                .andExpect(jsonPath("$.wonUsdRate").value("1300.000000"))
                .andExpect(jsonPath("$.totalQty").value("2.0000"))
                .andReturn();

        // Same key replays the stored response without consuming stock twice.
        MvcResult replay = mockMvc.perform(post("/api/v1/delivery-notes/{id}/post", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON)
                        .content(json(command)))
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsByteArray())
                .containsExactly(first.getResponse().getContentAsByteArray());

        assertThat(jdbc.sql("SELECT current_qty FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(BigDecimal.class).single())
                .isEqualByComparingTo("3");
        assertThat(jdbc.sql("SELECT delivered_qty FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(BigDecimal.class).single())
                .isEqualByComparingTo("2");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM inventory.stock_movement
                        WHERE stock_position_id = :id AND movement_type = 'DELIVERY'
                        """).param("id", positionId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM delivery.delivery_note_item
                        WHERE delivery_note_id = :id AND delivery_movement_id IS NOT NULL
                        """).param("id", deliveryId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT reconciled FROM inventory.stock_ledger_reconciliation WHERE stock_position_id = :id")
                .param("id", positionId).query(Boolean.class).single()).isTrue();
    }

    /** The rate comes from deliveryDate, so a month without a rate must fail before any effect. */
    @Test
    void postWithoutAMonthlyRateIsRefusedWithoutConsumingStock() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        Map<String, Object> request = draftRequest(positionId, "2.0000");
        request.put("deliveryDate", "2031-12-05");
        UUID deliveryId = createDraft(request);

        mockMvc.perform(post("/api/v1/delivery-notes/{id}/post", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "no-rate-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "shipping"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXCHANGE_RATE_MISSING"));

        assertThat(jdbc.sql("SELECT current_qty FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(BigDecimal.class).single())
                .isEqualByComparingTo("5");
        assertThat(jdbc.sql("SELECT status FROM delivery.delivery_note WHERE id = :id")
                .param("id", deliveryId).query(String.class).single()).isEqualTo("DRAFT");
    }

    /** Capacity is re-checked under the lock: a draft written before a disposal must not oversell. */
    @Test
    void postRevalidatesCapacityAgainstStockConsumedAfterTheDraftWasWritten() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        UUID deliveryId = createDraft(draftRequest(positionId, "5.0000"));

        mockMvc.perform(post("/api/v1/stock-positions/{id}/disposals", positionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "dispose-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("quantity", "3.0000",
                                "businessDate", "2026-08-12", "reason", "damaged"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/delivery-notes/{id}/post", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "post-stale-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "shipping"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(jdbc.sql("SELECT status FROM delivery.delivery_note WHERE id = :id")
                .param("id", deliveryId).query(String.class).single()).isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT current_qty FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(BigDecimal.class).single())
                .isEqualByComparingTo("2");
    }

    @Test
    void postedDeliveryCannotBeUpdatedOrPostedAgain() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        UUID deliveryId = createDraft(draftRequest(positionId, "1.0000"));
        mockMvc.perform(post("/api/v1/delivery-notes/{id}/post", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "post-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "shipping"))))
                .andExpect(status().isOk());

        long version = jdbc.sql("SELECT version FROM delivery.delivery_note WHERE id = :id")
                .param("id", deliveryId).query(Long.class).single();
        mockMvc.perform(post("/api/v1/delivery-notes/{id}/post", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                        .header(IDEMPOTENCY, "repost-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "again"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_ALREADY_POSTED"));

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM inventory.stock_movement
                        WHERE stock_position_id = :id AND movement_type = 'DELIVERY'
                        """).param("id", positionId).query(Long.class).single()).isOne();
    }

    /** Posting several lines is one transaction: every position moves or none does. */
    @Test
    void multiLinePostConsumesEveryPositionInOneTransaction() throws Exception {
        UUID first = finishedStockPosition("5.0000");
        UUID second = finishedStockPosition("4.0000");
        Map<String, Object> request = draftRequest(first, "2.0000");
        request.put("items", List.of(
                deliveryItem(first, "2.0000"),
                deliveryItem(second, "3.0000")));
        UUID deliveryId = createDraft(request);

        mockMvc.perform(post("/api/v1/delivery-notes/{id}/post", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "multi-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "shipping"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQty").value("5.0000"))
                .andExpect(jsonPath("$.items.length()").value(2));

        assertThat(jdbc.sql("SELECT current_qty FROM inventory.stock_position WHERE id = :id")
                .param("id", first).query(BigDecimal.class).single()).isEqualByComparingTo("3");
        assertThat(jdbc.sql("SELECT current_qty FROM inventory.stock_position WHERE id = :id")
                .param("id", second).query(BigDecimal.class).single()).isEqualByComparingTo("1");
    }
}
