package com.company.erp.sales;

import com.company.erp.masterdata.MasterDataControllerITSupport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class BuyerOrderTestSupport extends MasterDataControllerITSupport {

    protected static final String IDEMPOTENCY = "Idempotency-Key";

    protected UUID customerId;
    protected UUID contactId;
    protected UUID uomId;
    protected UUID finishedGoodId;

    protected void prepareBuyerOrderReferences() {
        String suffix = UUID.randomUUID().toString();
        customerId = insertCustomer("BO-" + suffix.substring(0, 8));
        contactId = insertCustomerContact(customerId, "Buyer contact", true);
        uomId = insertUom("BO" + suffix.substring(0, 8));
        finishedGoodId = insertFinishedGood("PRINT", "STYLE-" + suffix, "Printed label", uomId);
    }

    protected Map<String, Object> standardRequest() {
        return orderRequest(List.of(standardItem(finishedGoodId, "2.5000", "3.333333")));
    }

    protected Map<String, Object> orderRequest(List<Map<String, Object>> items) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderType", "STANDARD");
        request.put("customerId", customerId.toString());
        request.put("customerContactId", contactId.toString());
        request.put("picSource", "MASTER");
        request.put("picName", "Buyer contact");
        request.put("buyerPo", "PO-" + UUID.randomUUID());
        request.put("poDate", LocalDate.of(2026, 8, 1).toString());
        request.put("deliveryDate", LocalDate.of(2026, 8, 15).toString());
        request.put("items", items);
        return request;
    }

    protected Map<String, Object> standardItem(
            UUID referencedFinishedGoodId, String orderQty, String unitPrice) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("isCustom", false);
        item.put("finishedGoodId", referencedFinishedGoodId.toString());
        item.put("orderQty", orderQty);
        item.put("unitPrice", unitPrice);
        item.put("remark", "standard line");
        return item;
    }

    protected Map<String, Object> customItem(String styleNo, String orderQty, String unitPrice) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("isCustom", true);
        item.put("productKind", "WOVEN");
        item.put("styleNo", styleNo);
        item.put("name", "Custom woven label");
        item.put("size", "L");
        item.put("color", "Blue");
        item.put("uomId", uomId.toString());
        item.put("orderQty", orderQty);
        item.put("unitPrice", unitPrice);
        item.put("currencyCode", "USD");
        item.put("remark", "custom line");
        return item;
    }

    protected UUID createOrder(Map<String, Object> request) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .header(IDEMPOTENCY, "create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    protected static List<Map<String, Object>> mutableItems(Map<String, Object>... items) {
        return new ArrayList<>(List.of(items));
    }
}
