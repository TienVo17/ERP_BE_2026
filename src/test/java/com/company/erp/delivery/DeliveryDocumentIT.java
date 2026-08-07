package com.company.erp.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeliveryDocumentIT extends DeliveryTestSupport {

    @BeforeEach
    void prepare() {
        prepareProductionReferences();
        ensureDeliveryMonthRate();
    }

    @Test
    void streamsTheOfficialDocumentForAPostedDelivery() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        UUID deliveryId = postedDelivery(positionId, "2.0000");
        String deliveryNo = jdbc.sql("SELECT delivery_no FROM delivery.delivery_note WHERE id = :id")
                .param("id", deliveryId).query(String.class).single();

        var result = mockMvc.perform(get("/api/v1/delivery-notes/{id}/document.pdf", deliveryId)
                        .with(saleJwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString(deliveryNo + ".pdf")))
                .andReturn();

        try (var document = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains(deliveryNo, "Status: POSTED", "Total quantity: 2.0000");
        }
    }

    /** A draft has no official number, so it has no official document either. */
    @Test
    void refusesTheDocumentForADraftDelivery() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        UUID draftId = createDraft(draftRequest(positionId, "1.0000"));

        mockMvc.perform(get("/api/v1/delivery-notes/{id}/document.pdf", draftId).with(saleJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void documentRequiresThePrintPermission() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        UUID deliveryId = postedDelivery(positionId, "2.0000");
        jdbc.sql("""
                        INSERT INTO identity.user_permission_override (user_id, permission_id, effect, reason, updated_by)
                        SELECT :userId, id, 'DENY', 'delivery print test', :actor
                        FROM identity.permission
                        WHERE module_code = 'DELIVERY' AND action_code = 'PRINT'
                        """).param("userId", saleId).param("actor", SYSTEM_USER_ID).update();

        mockMvc.perform(get("/api/v1/delivery-notes/{id}/document.pdf", deliveryId).with(saleJwt()))
                .andExpect(status().isForbidden());
    }

    /** A 100-line delivery must render every line rather than silently losing its tail. */
    @Test
    void rendersEveryLineOfAMaximumSizeDelivery() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            items.add(deliveryItem(finishedStockPosition("2.0000"), "1.0000"));
        }
        Map<String, Object> request = draftRequest(UUID.randomUUID(), "1.0000");
        request.put("items", items);
        UUID deliveryId = createDraft(request);
        mockMvc.perform(post("/api/v1/delivery-notes/{id}/post", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "post-max-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "shipping"))))
                .andExpect(status().isOk());

        var result = mockMvc.perform(get("/api/v1/delivery-notes/{id}/document.pdf", deliveryId)
                        .with(saleJwt()))
                .andExpect(status().isOk())
                .andReturn();

        try (var document = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            assertThat(text).contains("Line 1:", "Line 100:");
        }
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
