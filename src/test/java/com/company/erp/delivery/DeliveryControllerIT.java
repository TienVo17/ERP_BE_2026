package com.company.erp.delivery;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeliveryControllerIT extends DeliveryTestSupport {

    @BeforeEach
    void prepare() {
        prepareProductionReferences();
    }

    /** DELIVERY:CREATE alone authorizes the source list, and it exposes only composition fields. */
    @Test
    void listsRedactedDeliverySourcesUnderCreatePermissionAlone() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        jdbc.sql("""
                        INSERT INTO identity.user_permission_override (user_id, permission_id, effect, reason, updated_by)
                        SELECT :userId, id, 'DENY', 'delivery source test', :actor
                        FROM identity.permission
                        WHERE module_code = 'DELIVERY' AND action_code = 'VIEW'
                        """).param("userId", saleId).param("actor", SYSTEM_USER_ID).update();

        mockMvc.perform(get("/api/v1/delivery-sources").with(saleJwt())
                        .param("inStock", "true")
                        .param("customerId", customerId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items[0].stockPositionId").value(positionId.toString()))
                .andExpect(jsonPath("$.items[0].availableQty").value("5.0000"))
                .andExpect(jsonPath("$.items[0].sysPoNo").exists())
                .andExpect(jsonPath("$.items[0].currentQty").doesNotExist())
                .andExpect(jsonPath("$.sort[1]").value("stockPositionId,asc"));
    }

    @Test
    void createsAndUpdatesDraftWithServerDerivedSnapshotsAndTotals() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");

        var created = mockMvc.perform(post("/api/v1/delivery-notes")
                        .with(saleJwt()).header(IDEMPOTENCY, "draft-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draftRequest(positionId, "2.0000"))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.deliveryNo").doesNotExist())
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.totalQty").value("2.0000"))
                .andExpect(jsonPath("$.totalAmount").value("5.00"))
                .andExpect(jsonPath("$.items[0].lineNo").value(1))
                .andExpect(jsonPath("$.items[0].amount").value("5.00"))
                .andExpect(jsonPath("$.items[0].sysPoNo").exists())
                .andExpect(jsonPath("$.events[0].eventType").value("CREATED"))
                .andReturn();
        UUID deliveryId = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(put("/api/v1/delivery-notes/{id}", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "update-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draftRequest(positionId, "3.0000"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.totalQty").value("3.0000"))
                .andExpect(jsonPath("$.totalAmount").value("7.50"))
                .andExpect(jsonPath("$.events[1].eventType").value("UPDATED_DRAFT"));

        // A draft reserves nothing: the position stays fully available until post.
        assertThat(jdbc.sql("SELECT current_qty FROM inventory.stock_position WHERE id = :id")
                .param("id", positionId).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("5");
    }

    @Test
    void rejectsOverpostedFieldsMissingHeadersAndMixedCustomers() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");

        Map<String, Object> overposted = draftRequest(positionId, "1.0000");
        overposted.put("deliveryNo", "DN-2026-000001");
        mockMvc.perform(post("/api/v1/delivery-notes")
                        .with(saleJwt()).header(IDEMPOTENCY, "overpost-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(overposted)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/delivery-notes")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draftRequest(positionId, "1.0000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        // A second position belonging to a different Customer cannot share one Delivery.
        UUID otherCustomer = insertCustomer("OTHER-" + UUID.randomUUID().toString().substring(0, 8));
        UUID otherContact = insertCustomerContact(otherCustomer, "Other contact", true);
        UUID previousCustomer = customerId;
        UUID previousContact = contactId;
        customerId = otherCustomer;
        contactId = otherContact;
        UUID otherPosition = finishedStockPosition("4.0000");
        customerId = previousCustomer;
        contactId = previousContact;

        Map<String, Object> mixed = draftRequest(positionId, "1.0000");
        mixed.put("items", List.of(
                deliveryItem(positionId, "1.0000"),
                deliveryItem(otherPosition, "1.0000")));
        mockMvc.perform(post("/api/v1/delivery-notes")
                        .with(saleJwt()).header(IDEMPOTENCY, "mixed-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(mixed)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsDraftQuantityBeyondAvailableStock() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");

        mockMvc.perform(post("/api/v1/delivery-notes")
                        .with(saleJwt()).header(IDEMPOTENCY, "over-capacity-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draftRequest(positionId, "6.0000"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }
}
