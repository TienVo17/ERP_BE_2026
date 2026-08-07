package com.company.erp.delivery;

import com.company.erp.production.ProductionTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Builds real finished stock through the Production workflow so Delivery has genuine sources. */
public abstract class DeliveryTestSupport extends ProductionTestSupport {

    protected static final LocalDate DELIVERY_DATE = LocalDate.of(2026, 8, 20);

    /** Runs Buyer Order confirm, Production configure and finish to leave one Stock Position. */
    protected UUID finishedStockPosition(String quantity) throws Exception {
        UUID processId = insertProcess("Delivery process " + UUID.randomUUID(), "PROCESS-" + UUID.randomUUID());
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, quantity)));
        UUID productionId = productionIds(buyerOrderId).getFirst();
        mockMvc.perform(put("/api/v1/production-orders/{id}/configuration", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "delivery-config-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(printConfiguration(processId))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/production-orders/{id}/finish", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "delivery-finish-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "delivery fixture"))))
                .andExpect(status().isOk());
        return jdbc.sql("SELECT id FROM inventory.stock_position WHERE production_order_id = :id")
                .param("id", productionId).query(UUID.class).single();
    }

    protected Map<String, Object> draftRequest(UUID stockPositionId, String quantity) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("deliveryDate", DELIVERY_DATE.toString());
        request.put("vatPercent", "10.0000");
        request.put("remark", "delivery draft");
        request.put("items", List.of(deliveryItem(stockPositionId, quantity)));
        return request;
    }

    protected Map<String, Object> deliveryItem(UUID stockPositionId, String quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("stockPositionId", stockPositionId.toString());
        item.put("deliveryQty", quantity);
        item.put("unitPrice", "2.500000");
        return item;
    }

    protected UUID createDraft(Map<String, Object> request) throws Exception {
        var result = mockMvc.perform(post("/api/v1/delivery-notes")
                        .with(saleJwt()).header(IDEMPOTENCY, "draft-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    /** Posting derives the rate from deliveryDate, so the month must exist before the command. */
    protected void ensureDeliveryMonthRate() {
        LocalDate month = DELIVERY_DATE.withDayOfMonth(1);
        boolean exists = jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM master_data.monthly_exchange_rate WHERE effective_month = :month
                        )
                        """).param("month", month).query(Boolean.class).single();
        if (!exists) {
            insertExchangeRate(month);
        }
    }
}
