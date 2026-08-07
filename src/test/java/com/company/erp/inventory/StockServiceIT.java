package com.company.erp.inventory;

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

class StockServiceIT extends StockTestSupport {

    @BeforeEach
    void prepare() {
        prepareProductionReferences();
    }

    @Test
    void disposalReplayIsByteIdenticalAndUsesTrustedCommandIdentity() throws Exception {
        UUID positionId = finishedStock("5.0000");
        String key = "dispose-replay-" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "quantity", "2.0000", "businessDate", "2026-08-12", "reason", "damaged");

        MvcResult first = mockMvc.perform(post("/api/v1/stock-positions/{id}/disposals", positionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/stock-positions/{id}/disposals", positionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk()).andReturn();

        assertThat(replay.getResponse().getContentAsByteArray())
                .containsExactly(first.getResponse().getContentAsByteArray());
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.stock_movement WHERE stock_position_id = :id AND movement_type = 'DISPOSE'")
                .param("id", positionId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT sm.idempotency_key = ir.command_id::text
                        FROM inventory.stock_movement sm
                        JOIN system.idempotency_record ir ON ir.idempotency_key = :key
                        WHERE sm.stock_position_id = :positionId AND sm.movement_type = 'DISPOSE'
                        """).param("key", key).param("positionId", positionId)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void returnLimitConflictLeavesPositionAndLedgerUnchanged() throws Exception {
        UUID positionId = finishedStock("5.0000");
        UUID deliveryItemId = postedDeliveryItem(positionId, "2.0000");

        mockMvc.perform(post("/api/v1/stock-positions/{id}/returns", positionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "return-limit-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "deliveryNoteItemId", deliveryItemId.toString(),
                                "quantity", "2.0001", "businessDate", "2026-08-13",
                                "reason", "customer return"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_LIMIT_EXCEEDED"));

        assertThat(jdbc.sql("SELECT returned_qty FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.stock_movement WHERE stock_position_id = :id AND movement_type = 'RETURN'")
                .param("id", positionId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT reconciled FROM inventory.stock_ledger_reconciliation WHERE stock_position_id = :id")
                .param("id", positionId).query(Boolean.class).single()).isTrue();
    }
}
