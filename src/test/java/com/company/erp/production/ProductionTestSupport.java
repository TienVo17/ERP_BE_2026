package com.company.erp.production;

import com.company.erp.masterdata.MasterDataControllerITSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class ProductionTestSupport extends MasterDataControllerITSupport {

    protected static final String IDEMPOTENCY = "Idempotency-Key";
    protected UUID customerId;
    protected UUID contactId;
    protected UUID uomId;
    protected UUID finishedGoodId;

    protected void prepareProductionReferences() {
        String suffix = UUID.randomUUID().toString();
        customerId = insertCustomer("PO-" + suffix.substring(0, 8));
        contactId = insertCustomerContact(customerId, "Production contact", true);
        uomId = insertUom("PO" + suffix.substring(0, 8));
        finishedGoodId = insertFinishedGood("PRINT", "STYLE-" + suffix, "Production finished good", uomId);
    }

    protected Map<String, Object> standardItem(UUID finishedGood, String quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("isCustom", false);
        item.put("finishedGoodId", finishedGood.toString());
        item.put("orderQty", quantity);
        item.put("unitPrice", "1.000000");
        item.put("remark", "production fixture");
        return item;
    }

    protected Map<String, Object> customItem(String style, String quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("isCustom", true);
        item.put("productKind", "WOVEN");
        item.put("styleNo", style);
        item.put("name", "Custom woven fixture");
        item.put("uomId", uomId.toString());
        item.put("orderQty", quantity);
        item.put("unitPrice", "1.000000");
        item.put("currencyCode", "USD");
        return item;
    }

    protected UUID confirmedOrder(List<Map<String, Object>> items) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderType", "STANDARD");
        request.put("customerId", customerId.toString());
        request.put("customerContactId", contactId.toString());
        request.put("picSource", "MASTER");
        request.put("picName", "Production contact");
        request.put("buyerPo", "PO-" + UUID.randomUUID());
        request.put("poDate", LocalDate.of(2026, 8, 1).toString());
        request.put("deliveryDate", LocalDate.of(2026, 8, 15).toString());
        request.put("items", items);
        MvcResult created = mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt()).header(IDEMPOTENCY, "create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isCreated()).andReturn();
        UUID orderId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(post("/api/v1/buyer-orders/{id}/confirm", orderId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "confirm-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "ready"))))
                .andExpect(status().isOk());
        return orderId;
    }

    protected List<UUID> productionIds(UUID buyerOrderId) {
        return jdbc.sql("SELECT id FROM production.production_order WHERE buyer_order_id = :id ORDER BY id")
                .param("id", buyerOrderId).query(UUID.class).list();
    }

    protected Map<String, Object> member(UUID id, long version) {
        return Map.of("productionOrderId", id.toString(), "expectedVersion", version);
    }

    protected Map<String, Object> printConfiguration(UUID processId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("productKind", "PRINT");
        request.put("materialSource", "STOCK");
        request.put("orderKind", "NEW_ORDER");
        request.put("processes", List.of(Map.of(
                "processId", processId.toString(),
                "sequenceNo", 1,
                "speed", "12.5000")));
        request.put("yarnLines", List.of());
        request.put("remark", "print configuration");
        return request;
    }

    protected Map<String, Object> wovenConfiguration(UUID processId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("productKind", "WOVEN");
        request.put("bim", "WHITE");
        request.put("xNgangMm", "10.5000");
        request.put("yDocMm", "20.0000");
        request.put("hoPercent", "5.2500");
        request.put("weaveTypes", List.of("THUONG"));
        request.put("processes", List.of(Map.of(
                "processId", processId.toString(),
                "sequenceNo", 1)));
        request.put("yarnLines", List.of(Map.of(
                "yarn", "Polyester",
                "yarnCode", "P-01",
                "denier", "75D")));
        request.put("remark", "woven configuration");
        return request;
    }
}
