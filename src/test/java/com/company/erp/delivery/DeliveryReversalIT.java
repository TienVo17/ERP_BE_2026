package com.company.erp.delivery;

import java.math.BigDecimal;
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

class DeliveryReversalIT extends DeliveryTestSupport {

    @BeforeEach
    void prepare() {
        prepareProductionReferences();
        ensureDeliveryMonthRate();
    }

    @Test
    void reverseRestoresStockAndCreatesOneUnnumberedReplacementDraft() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        UUID deliveryId = postedDelivery(positionId, "2.0000");
        String key = "reverse-" + UUID.randomUUID();
        Map<String, Object> command = Map.of("reason", "wrong address");

        MvcResult first = mockMvc.perform(post("/api/v1/delivery-notes/{id}/reverse", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON)
                        .content(json(command)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversedDelivery.status").value("REVERSED"))
                .andExpect(jsonPath("$.reversedDelivery.deliveryNo").exists())
                .andExpect(jsonPath("$.replacementDraft.status").value("DRAFT"))
                .andExpect(jsonPath("$.replacementDraft.deliveryNo").doesNotExist())
                .andExpect(jsonPath("$.replacementDraft.replacesDeliveryId").value(deliveryId.toString()))
                .andExpect(jsonPath("$.replacementDraft.totalQty").value("2.0000"))
                .andReturn();

        MvcResult replay = mockMvc.perform(post("/api/v1/delivery-notes/{id}/reverse", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON)
                        .content(json(command)))
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsByteArray())
                .containsExactly(first.getResponse().getContentAsByteArray());

        // Stock is fully restored and the reversal is recorded, not erased.
        assertThat(jdbc.sql("SELECT current_qty FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(BigDecimal.class).single())
                .isEqualByComparingTo("5");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM inventory.stock_movement
                        WHERE stock_position_id = :id AND movement_type = 'DELIVERY_REVERSAL'
                        """).param("id", positionId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM delivery.delivery_note WHERE replaces_delivery_id = :id
                        """).param("id", deliveryId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT reconciled FROM inventory.stock_ledger_reconciliation WHERE stock_position_id = :id")
                .param("id", positionId).query(Boolean.class).single()).isTrue();
    }

    /** A returned line means the customer already sent goods back; reversing would double-count. */
    @Test
    void reverseIsRefusedAfterAReturnWithoutPartialEffects() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        UUID deliveryId = postedDelivery(positionId, "2.0000");
        UUID itemId = jdbc.sql("SELECT id FROM delivery.delivery_note_item WHERE delivery_note_id = :id")
                .param("id", deliveryId).query(UUID.class).single();
        long positionVersion = jdbc.sql("SELECT version FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(Long.class).single();
        mockMvc.perform(post("/api/v1/stock-positions/{id}/returns", positionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"" + positionVersion + "\"")
                        .header(IDEMPOTENCY, "return-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("deliveryNoteItemId", itemId.toString(),
                                "quantity", "1.0000", "businessDate", "2026-08-25",
                                "reason", "customer return"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/delivery-notes/{id}/reverse", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "reverse-returned-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "too late"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_HAS_RETURNS"));

        assertThat(jdbc.sql("SELECT status FROM delivery.delivery_note WHERE id = :id")
                .param("id", deliveryId).query(String.class).single()).isEqualTo("POSTED");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM delivery.delivery_note WHERE replaces_delivery_id = :id
                        """).param("id", deliveryId).query(Long.class).single()).isZero();
    }

    @Test
    void draftAndReversedDeliveriesCannotBeReversed() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        UUID draftId = createDraft(draftRequest(positionId, "1.0000"));

        mockMvc.perform(post("/api/v1/delivery-notes/{id}/reverse", draftId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "reverse-draft-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "not posted"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        UUID postedId = postedDelivery(finishedStockPosition("4.0000"), "1.0000");
        mockMvc.perform(post("/api/v1/delivery-notes/{id}/reverse", postedId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "reverse-once-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "first"))))
                .andExpect(status().isOk());
        long version = jdbc.sql("SELECT version FROM delivery.delivery_note WHERE id = :id")
                .param("id", postedId).query(Long.class).single();
        mockMvc.perform(post("/api/v1/delivery-notes/{id}/reverse", postedId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                        .header(IDEMPOTENCY, "reverse-twice-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "second"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_ALREADY_REVERSED"));

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM delivery.delivery_note WHERE replaces_delivery_id = :id
                        """).param("id", postedId).query(Long.class).single()).isOne();
    }

    /** A -> B -> C is legitimate history: a replacement that was posted may itself be reversed. */
    @Test
    void aPostedReplacementCanBeReversedAgainFormingALinearChain() throws Exception {
        UUID positionId = finishedStockPosition("6.0000");
        UUID original = postedDelivery(positionId, "2.0000");
        mockMvc.perform(post("/api/v1/delivery-notes/{id}/reverse", original)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "reverse-a-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "reissue"))))
                .andExpect(status().isOk());

        UUID replacement = jdbc.sql("SELECT id FROM delivery.delivery_note WHERE replaces_delivery_id = :id")
                .param("id", original).query(UUID.class).single();
        mockMvc.perform(post("/api/v1/delivery-notes/{id}/post", replacement)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "post-b-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "shipping"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/delivery-notes/{id}/reverse", replacement)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "reverse-b-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "reissue again"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacementDraft.replacesDeliveryId").value(replacement.toString()));

        // A -> B -> C, each with exactly one successor, and the stock is whole again.
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM delivery.delivery_note WHERE replaces_delivery_id IS NOT NULL
                        """).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT current_qty FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(BigDecimal.class).single())
                .isEqualByComparingTo("6");
    }

    private UUID postedDelivery(UUID positionId, String quantity) throws Exception {
        UUID deliveryId = createDraft(draftRequest(positionId, quantity));
        mockMvc.perform(post("/api/v1/delivery-notes/{id}/post", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "post-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "shipping"))))
                .andExpect(status().isOk());
        return deliveryId;
    }
}
