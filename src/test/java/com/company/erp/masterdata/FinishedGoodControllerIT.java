package com.company.erp.masterdata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FinishedGoodControllerIT extends MasterDataControllerITSupport {

    @Test
    void finishedGoodCompositeKeyIsCanonicalAndPriceRequiresCurrencyTogether() throws Exception {
        UUID uomId = insertUom("FGU" + UUID.randomUUID());
        String style = "FG-" + Integer.toHexString(UUID.randomUUID().hashCode());

        Map<String, Object> request = request(uomId, style, "  Printed tape  ");
        request.put("referencePrice", "9.500000");
        request.put("currencyCode", "USD");
        mockMvc.perform(post("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productKind").value("PRINT"))
                .andExpect(jsonPath("$.styleNo").value(style))
                .andExpect(jsonPath("$.name").value("Printed tape"))
                .andExpect(jsonPath("$.referencePrice").value("9.500000"))
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.uomId").value(uomId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0));

        Map<String, Object> canonicalDuplicate = request(uomId, style.toLowerCase(), "printed tape");
        mockMvc.perform(post("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(canonicalDuplicate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_KEY"));

        Map<String, Object> differentSize = request(uomId, style, "Printed tape");
        differentSize.put("size", "L");
        mockMvc.perform(post("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(differentSize)))
                .andExpect(status().isCreated());

        Map<String, Object> priceWithoutCurrency = request(uomId, style + "-P", "Price only");
        priceWithoutCurrency.put("referencePrice", "1.000000");
        mockMvc.perform(post("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(priceWithoutCurrency)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Map<String, Object> currencyWithoutPrice = request(uomId, style + "-C", "Currency only");
        currencyWithoutPrice.put("currencyCode", "USD");
        mockMvc.perform(post("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(currencyWithoutPrice)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Map<String, Object> unknownKind = request(uomId, style + "-K", "Unknown kind");
        unknownKind.put("productKind", "KNIT");
        mockMvc.perform(post("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(unknownKind)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Map<String, Object> unknownUom = request(UUID.randomUUID(), style + "-U", "Unknown uom");
        mockMvc.perform(post("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(unknownUom)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void finishedGoodRejectsMediaAndUsageOverpostingAndKeepsImageColumnNull() throws Exception {
        UUID uomId = insertUom("FGM" + UUID.randomUUID());
        String style = "FGM-" + Integer.toHexString(UUID.randomUUID().hashCode());

        mockMvc.perform(post("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productKind":"PRINT","styleNo":"%s","name":"Media","uomId":"%s",
                                 "imageDataUrl":"data:image/png;base64,AAAA"}
                                """.formatted(style, uomId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productKind":"PRINT","styleNo":"%s","name":"Media","uomId":"%s",
                                 "imageAssetId":"%s"}
                                """.formatted(style, uomId, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productKind":"PRINT","styleNo":"%s","name":"Usage","uomId":"%s",
                                 "usedInBuyerOrder":false}
                                """.formatted(style, uomId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        MvcResult created = mockMvc.perform(post("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(uomId, style, "Clean finished good"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID finishedGoodId = id(created);

        assertThat(jdbc.sql("SELECT image_asset_id FROM master_data.finished_good WHERE id = :id")
                .param("id", finishedGoodId)
                .query(UUID.class)
                .optional()).isEmpty();
        assertThat(created.getResponse().getContentAsString()).doesNotContain("imageAssetId", "imageDataUrl");
    }

    @Test
    void finishedGoodUsedByBuyerOrderItemCannotBeUpdatedOrArchived() throws Exception {
        UUID uomId = insertUom("FGX" + UUID.randomUUID());
        String style = "FGX-" + Integer.toHexString(UUID.randomUUID().hashCode());
        UUID finishedGoodId = insertFinishedGood("PRINT", style, "Used finished good", uomId);
        referenceFinishedGood(finishedGoodId);

        Map<String, Object> renamed = request(uomId, style, "Renamed finished good");
        mockMvc.perform(put("/api/v1/master-data/finished-goods/{id}", finishedGoodId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(renamed)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MASTER_IN_USE"));

        mockMvc.perform(post("/api/v1/master-data/finished-goods/{id}/archive", finishedGoodId)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MASTER_IN_USE"));

        assertThat(jdbc.sql("SELECT status FROM master_data.finished_good WHERE id = :id")
                .param("id", finishedGoodId)
                .query(String.class)
                .single()).isEqualTo("ACTIVE");
    }

    @Test
    void finishedGoodListFiltersByKindAndOnlyAdminArchivesUnusedRecords() throws Exception {
        UUID uomId = insertUom("FGL" + UUID.randomUUID());
        String suffix = Integer.toHexString(UUID.randomUUID().hashCode()).toUpperCase();
        UUID woven = insertFinishedGood("WOVEN", "FGW-" + suffix, "Woven label " + suffix, uomId);
        insertFinishedGood("PRINT", "FGP-" + suffix, "Printed label " + suffix, uomId);

        mockMvc.perform(get("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .param("productKind", "WOVEN")
                        .param("name", "label " + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.size").value(25))
                .andExpect(jsonPath("$.items[0].id").value(woven.toString()))
                .andExpect(jsonPath("$.filters.productKind").value("WOVEN"));

        mockMvc.perform(get("/api/v1/master-data/finished-goods")
                        .with(saleJwt())
                        .param("sort", "referencePrice,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/master-data/finished-goods/{id}/archive", woven)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/master-data/finished-goods/{id}/archive", woven)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(get("/api/v1/master-data/finished-goods/{id}", UUID.randomUUID()).with(saleJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private Map<String, Object> request(UUID uomId, String styleNo, String name) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("productKind", "PRINT");
        request.put("styleNo", styleNo);
        request.put("name", name);
        request.put("uomId", uomId.toString());
        return request;
    }

    private UUID id(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
