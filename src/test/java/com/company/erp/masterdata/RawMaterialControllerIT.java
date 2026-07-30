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

class RawMaterialControllerIT extends MasterDataControllerITSupport {

    @Test
    void rawMaterialCanonicalizesCodeRoundTripsDecimalsAndRejectsOverposting() throws Exception {
        UUID uomId = insertUom("RMU" + UUID.randomUUID());
        UUID supplierId = insertSupplier("Raw supplier " + UUID.randomUUID());
        String suffix = Integer.toHexString(UUID.randomUUID().hashCode());
        Map<String, Object> request = request(uomId, supplierId);
        request.put("code", "  rm-" + suffix + "  ");
        request.put("name", "  Cotton yarn  ");
        request.put("referencePrice", "12.345678");
        request.put("safetyStockQty", "10.5");

        mockMvc.perform(post("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("RM-" + suffix.toUpperCase()))
                .andExpect(jsonPath("$.name").value("Cotton yarn"))
                .andExpect(jsonPath("$.referencePrice").value("12.345678"))
                .andExpect(jsonPath("$.safetyStockQty").value("10.5000"))
                .andExpect(jsonPath("$.supplierId").value(supplierId.toString()))
                .andExpect(jsonPath("$.supplierName").exists())
                .andExpect(jsonPath("$.uomId").value(uomId.toString()))
                .andExpect(jsonPath("$.uomCode").exists())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0));

        Map<String, Object> duplicate = request(uomId, supplierId);
        duplicate.put("code", "rm-" + suffix);
        mockMvc.perform(post("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(duplicate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_KEY"));

        mockMvc.perform(post("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"RM-OVERPOST","name":"Overposted","uomId":"%s","currencyCode":"USD",
                                 "inventory":5}
                                """.formatted(uomId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"RM-OVERPOST","name":"Overposted","uomId":"%s","currencyCode":"USD",
                                 "status":"ARCHIVED"}
                                """.formatted(uomId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        Map<String, Object> withoutUom = request(uomId, null);
        withoutUom.remove("uomId");
        withoutUom.put("code", "RM-NO-UOM-" + suffix);
        mockMvc.perform(post("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(withoutUom)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Map<String, Object> negativePrice = request(uomId, null);
        negativePrice.put("code", "RM-NEG-" + suffix);
        negativePrice.put("referencePrice", "-1");
        mockMvc.perform(post("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(negativePrice)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rawMaterialRejectsUnknownOrNewlyArchivedReferencesButKeepsHistoricalOnes() throws Exception {
        UUID uomId = insertUom("RMR" + UUID.randomUUID());
        UUID supplierId = insertSupplier("History supplier " + UUID.randomUUID());
        String suffix = Integer.toHexString(UUID.randomUUID().hashCode());

        Map<String, Object> unknownUom = request(UUID.randomUUID(), null);
        unknownUom.put("code", "RM-UNKNOWN-" + suffix);
        mockMvc.perform(post("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(unknownUom)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Map<String, Object> unknownSupplier = request(uomId, UUID.randomUUID());
        unknownSupplier.put("code", "RM-UNKNOWN-SUP-" + suffix);
        mockMvc.perform(post("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(unknownSupplier)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Map<String, Object> created = request(uomId, supplierId);
        created.put("code", "RM-HISTORY-" + suffix);
        MvcResult result = mockMvc.perform(post("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(created)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID rawMaterialId = id(result);

        archive("master_data.supplier", supplierId);
        archive("master_data.uom", uomId);

        Map<String, Object> newRecordWithArchivedUom = request(uomId, null);
        newRecordWithArchivedUom.put("code", "RM-ARCHIVED-UOM-" + suffix);
        mockMvc.perform(post("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(newRecordWithArchivedUom)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Map<String, Object> unchangedReferences = request(uomId, supplierId);
        unchangedReferences.put("code", "RM-HISTORY-" + suffix);
        unchangedReferences.put("remark", "Kept for history");
        mockMvc.perform(put("/api/v1/master-data/raw-materials/{id}", rawMaterialId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(unchangedReferences)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remark").value("Kept for history"))
                .andExpect(jsonPath("$.version").value(1));

        UUID otherArchivedSupplierId = insertSupplier("Other supplier " + UUID.randomUUID());
        archive("master_data.supplier", otherArchivedSupplierId);
        Map<String, Object> changedToArchived = request(uomId, otherArchivedSupplierId);
        changedToArchived.put("code", "RM-HISTORY-" + suffix);
        mockMvc.perform(put("/api/v1/master-data/raw-materials/{id}", rawMaterialId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(changedToArchived)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rawMaterialUpdateUsesVersionAndOnlyAdminArchivesAMasterWithoutUsageSource() throws Exception {
        UUID uomId = insertUom("RML" + UUID.randomUUID());
        String suffix = Integer.toHexString(UUID.randomUUID().hashCode());
        Map<String, Object> request = request(uomId, null);
        request.put("code", "RM-LIFECYCLE-" + suffix);
        MvcResult result = mockMvc.perform(post("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID rawMaterialId = id(result);

        Map<String, Object> renamed = request(uomId, null);
        renamed.put("code", "RM-LIFECYCLE-" + suffix);
        renamed.put("name", "Renamed raw material");
        mockMvc.perform(put("/api/v1/master-data/raw-materials/{id}", rawMaterialId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"99\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(renamed)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mockMvc.perform(put("/api/v1/master-data/raw-materials/{id}", rawMaterialId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(renamed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed raw material"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/api/v1/master-data/raw-materials/{id}/archive", rawMaterialId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/master-data/raw-materials/{id}/archive", rawMaterialId)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE entity_id = :id AND entity_type = 'RAW_MATERIAL'")
                .param("id", rawMaterialId)
                .query(Long.class)
                .single()).isEqualTo(3);

        mockMvc.perform(get("/api/v1/master-data/raw-materials/{id}", UUID.randomUUID()).with(saleJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rawMaterialListAppliesAllowlistedFiltersSortAndPageDefaults() throws Exception {
        UUID uomId = insertUom("RMS" + UUID.randomUUID());
        UUID supplierId = insertSupplier("List supplier " + UUID.randomUUID());
        String suffix = Integer.toHexString(UUID.randomUUID().hashCode()).toUpperCase();
        UUID second = insertRawMaterial("RMB-" + suffix, uomId, supplierId);
        UUID first = insertRawMaterial("RMA-" + suffix, uomId, supplierId);

        mockMvc.perform(get("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .param("sort", "code,asc")
                        .param("supplierId", supplierId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.items[1].id").value(second.toString()))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(25))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.sort[0]").value("code,asc"));

        mockMvc.perform(get("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .param("code", "rma-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()));

        mockMvc.perform(get("/api/v1/master-data/raw-materials")
                        .with(saleJwt())
                        .param("sort", "referencePrice,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private Map<String, Object> request(UUID uomId, UUID supplierId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("code", "RM-" + UUID.randomUUID());
        request.put("name", "Raw material");
        request.put("uomId", uomId == null ? null : uomId.toString());
        request.put("currencyCode", "USD");
        if (supplierId != null) {
            request.put("supplierId", supplierId.toString());
        }
        return request;
    }

    private void archive(String table, UUID id) {
        jdbc.sql("UPDATE " + table + " SET status = 'ARCHIVED', version = version + 1, updated_by = :actorId WHERE id = :id")
                .param("actorId", SYSTEM_USER_ID)
                .param("id", id)
                .update();
    }

    private UUID id(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
