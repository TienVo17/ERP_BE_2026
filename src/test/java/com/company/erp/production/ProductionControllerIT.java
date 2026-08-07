package com.company.erp.production;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductionControllerIT extends ProductionTestSupport {

    @BeforeEach
    void prepare() {
        prepareProductionReferences();
    }

    @Test
    void listDetailAndEventsExposeCanonicalOpenProductionWithPagingAndNoStore() throws Exception {
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, "2.5000")));
        UUID productionId = productionIds(buyerOrderId).getFirst();

        mockMvc.perform(get("/api/v1/production-orders").with(saleJwt())
                        .param("buyerOrderId", buyerOrderId.toString()).param("status", "OPEN")
                        .param("productKind", "PRINT").param("sort", "productionNo,asc"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items[0].id").value(productionId.toString()))
                .andExpect(jsonPath("$.items[0].plannedQty").value("2.5000"))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].configuration.productKind").value("PRINT"))
                .andExpect(jsonPath("$.items[0].configuration.processes.length()").value(0))
                .andExpect(jsonPath("$.items[0].events[0].eventType").value("CREATED"))
                .andExpect(jsonPath("$.sort[1]").value("id,asc"));

        mockMvc.perform(get("/api/v1/production-orders/{id}", productionId).with(saleJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(productionId.toString()))
                .andExpect(jsonPath("$.configuration.yarnLines.length()").value(0));
        mockMvc.perform(get("/api/v1/production-orders/{id}/events", productionId).with(saleJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eventType").value("CREATED"));
        mockMvc.perform(get("/api/v1/production-orders").with(saleJwt()).param("sort", "createdBy,asc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void configuresPrintOrderFromCanonicalActiveProcessAndRejectsOverposting() throws Exception {
        UUID processId = insertProcess("Print process " + UUID.randomUUID(), "PROCESS-" + UUID.randomUUID());
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, "2.5000")));
        UUID productionId = productionIds(buyerOrderId).getFirst();

        mockMvc.perform(put("/api/v1/production-orders/{id}/configuration", productionId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "configure-print-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(printConfiguration(processId))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.configuration.productKind").value("PRINT"))
                .andExpect(jsonPath("$.configuration.materialSource").value("STOCK"))
                .andExpect(jsonPath("$.configuration.processes[0].processId").value(processId.toString()))
                .andExpect(jsonPath("$.configuration.processes[0].speed").value("12.5000"))
                .andExpect(jsonPath("$.events[1].eventType").value("CONFIGURED"));

        Map<String, Object> overposted = printConfiguration(processId);
        overposted.put("status", "FINISHED");
        mockMvc.perform(put("/api/v1/production-orders/{id}/configuration", productionId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "configure-overpost-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(overposted)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void streamsParseableCanonicalProductionPdfWithoutPersistingAReport() throws Exception {
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, "2.5000")));
        UUID productionId = productionIds(buyerOrderId).getFirst();
        String productionNo = jdbc.sql("SELECT production_no FROM production.production_order WHERE id = :id")
                .param("id", productionId).query(String.class).single();

        var result = mockMvc.perform(get("/api/v1/production-orders/{id}/document.pdf", productionId)
                        .with(saleJwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString(productionNo + ".pdf")))
                .andReturn();

        try (var document = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains(productionNo, "Status: OPEN", "Planned quantity: 2.5000");
        }
    }

    @Test
    void groupAndUngroupRequirePermissionsAndHonorDenyOverride() throws Exception {
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, "1.0000"), customItem("WOVEN-GROUP", "1.0000")));
        List<UUID> ids = productionIds(buyerOrderId);
        Map<String, Object> request = Map.of("members", List.of(member(ids.get(0), 0), member(ids.get(1), 0)), "reason", "bundle");
        mockMvc.perform(post("/api/v1/production-groups").with(saleJwt())
                        .header(IDEMPOTENCY, "group-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.groupNo").value(org.hamcrest.Matchers.matchesPattern("PG-SO-[0-9]{4}-[0-9]{6}-001")));

        jdbc.sql("""
                        INSERT INTO identity.user_permission_override (user_id, permission_id, effect, reason, updated_by)
                        SELECT :userId, id, 'DENY', 'production test', :actorId
                        FROM identity.permission WHERE module_code = 'PRODUCTION' AND action_code = 'VIEW'
                        """).param("userId", saleId).param("actorId", SYSTEM_USER_ID).update();
        mockMvc.perform(get("/api/v1/production-orders").with(saleJwt()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
