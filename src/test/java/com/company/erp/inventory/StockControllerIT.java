package com.company.erp.inventory;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockControllerIT extends StockTestSupport {

    @BeforeEach
    void prepare() {
        prepareProductionReferences();
    }

    @Test
    void listsDetailsAndMovementsWithCanonicalStringsAndNoStore() throws Exception {
        UUID positionId = finishedStock("5.0000");
        // Committed fixtures from the concurrency suite share this database, so scope the page to
        // this position's own Production Order rather than assuming it is the only row.
        UUID productionOrderId = jdbc.sql("SELECT production_order_id FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(UUID.class).single();
        mockMvc.perform(get("/api/v1/stock-positions").with(saleJwt())
                        .param("inStock", "true")
                        .param("productionOrderId", productionOrderId.toString())
                        .param("sort", "currentQty,desc"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items[0].id").value(positionId.toString()))
                .andExpect(jsonPath("$.items[0].currentQty").value("5.0000"))
                .andExpect(jsonPath("$.sort[1]").value("id,asc"));
        mockMvc.perform(get("/api/v1/stock-positions/{id}", positionId).with(saleJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.producedQty").value("5.0000"));
        mockMvc.perform(get("/api/v1/stock-positions/{id}/movements", positionId).with(saleJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].movementType").value("PRODUCTION"))
                .andExpect(jsonPath("$.items[0].quantitySigned").value("5.0000"));
        mockMvc.perform(get("/api/v1/stock-positions").with(saleJwt()).param("sort", "createdBy,asc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void disposesStockAndRejectsExcessWithStableConflict() throws Exception {
        UUID positionId = finishedStock("5.0000");
        Map<String, Object> request = Map.of(
                "quantity", "2.0000",
                "businessDate", LocalDate.of(2026, 8, 12).toString(),
                "reason", "damaged");
        mockMvc.perform(post("/api/v1/stock-positions/{id}/disposals", positionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "dispose-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockPosition.currentQty").value("3.0000"))
                .andExpect(jsonPath("$.movement.quantitySigned").value("-2.0000"));
        mockMvc.perform(post("/api/v1/stock-positions/{id}/disposals", positionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "dispose-excess-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "quantity", "4.0000",
                                "businessDate", "2026-08-12",
                                "reason", "damaged"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPOSAL_EXCEEDS_STOCK"));
    }

    @Test
    void returnsAgainstMinimalPostedDeliverySourceWithReturnPermissionAlone() throws Exception {
        UUID positionId = finishedStock("5.0000");
        UUID deliveryItemId = postedDeliveryItem(positionId, "2.0000");
        jdbc.sql("""
                        INSERT INTO identity.user_permission_override (user_id, permission_id, effect, reason, updated_by)
                        SELECT :userId, id, 'DENY', 'stock return test', :actor
                        FROM identity.permission
                        WHERE module_code = 'DELIVERY' AND action_code = 'VIEW'
                        """).param("userId", saleId).param("actor", SYSTEM_USER_ID).update();

        mockMvc.perform(get("/api/v1/stock-positions/{id}/eligible-return-sources", positionId)
                        .with(saleJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].deliveryNoteItemId").value(deliveryItemId.toString()))
                .andExpect(jsonPath("$.items[0].netReturnableQty").value("2.0000"))
                .andExpect(jsonPath("$.items[0].customerName").doesNotExist());
        mockMvc.perform(post("/api/v1/stock-positions/{id}/returns", positionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "return-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "deliveryNoteItemId", deliveryItemId.toString(),
                                "quantity", "1.0000",
                                "businessDate", "2026-08-13",
                                "reason", "customer return"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockPosition.currentQty").value("4.0000"))
                .andExpect(jsonPath("$.movement.movementType").value("RETURN"));
    }
}
