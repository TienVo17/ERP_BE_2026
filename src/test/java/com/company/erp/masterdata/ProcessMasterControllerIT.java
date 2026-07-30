package com.company.erp.masterdata;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProcessMasterControllerIT extends MasterDataControllerITSupport {

    @Test
    void processRequiresPositiveSequenceAndCanonicalUniqueNameAndQrValue() throws Exception {
        grantSalePermissions("PROCESS:VIEW", "PROCESS:CREATE", "PROCESS:UPDATE");

        MvcResult created = mockMvc.perform(post("/api/v1/master-data/processes")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  Weaving  ", "sequenceNo", 10, "qrValue", "PROCESS-WEAVING"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Weaving"))
                .andExpect(jsonPath("$.sequenceNo").value(10))
                .andExpect(jsonPath("$.qrValue").value("PROCESS-WEAVING"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        UUID processId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/master-data/processes")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "WEAVING", "sequenceNo", 20, "qrValue", "PROCESS-OTHER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_KEY"));

        mockMvc.perform(post("/api/v1/master-data/processes")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Dyeing", "sequenceNo", 5, "qrValue", "PROCESS-WEAVING"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_KEY"));

        mockMvc.perform(post("/api/v1/master-data/processes")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Invalid", "sequenceNo", 0, "qrValue", "PROCESS-INVALID"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(put("/api/v1/master-data/processes/{id}", processId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Weaving Updated\",\"sequenceNo\":11,\"qrValue\":\"FORGED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(put("/api/v1/master-data/processes/{id}", processId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"99\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Weaving Updated", "sequenceNo", 11))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void processListUsesSequenceSortAndOnlyAdminArchives() throws Exception {
        grantSalePermissions("PROCESS:VIEW");
        UUID firstProcessId = insertProcess("Sequence one " + UUID.randomUUID(), "QR-ONE-" + UUID.randomUUID());
        UUID secondProcessId = insertProcess("Sequence two " + UUID.randomUUID(), "QR-TWO-" + UUID.randomUUID());
        jdbc.sql("UPDATE master_data.process_master SET sequence_no = 20 WHERE id = :id")
                .param("id", firstProcessId)
                .update();
        jdbc.sql("UPDATE master_data.process_master SET sequence_no = 10 WHERE id = :id")
                .param("id", secondProcessId)
                .update();

        mockMvc.perform(get("/api/v1/master-data/processes")
                        .with(saleJwt())
                        .param("sort", "sequenceNo,asc")
                        .param("name", "sequence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(secondProcessId.toString()))
                .andExpect(jsonPath("$.items[1].id").value(firstProcessId.toString()))
                .andExpect(jsonPath("$.sort[0]").value("sequenceNo,asc"));

        mockMvc.perform(post("/api/v1/master-data/processes/{id}/archive", firstProcessId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/master-data/processes/{id}/archive", firstProcessId)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void processUsedByProductionCannotBeUpdatedOrArchivedAndAbsentIsNotFound() throws Exception {
        grantSalePermissions("PROCESS:VIEW", "PROCESS:UPDATE");
        UUID processId = insertProcess("Used process " + UUID.randomUUID(), "QR-USED-" + UUID.randomUUID());
        referenceProcess(processId);

        mockMvc.perform(put("/api/v1/master-data/processes/{id}", processId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Used process changed", "sequenceNo", 99))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MASTER_IN_USE"));
        mockMvc.perform(post("/api/v1/master-data/processes/{id}/archive", processId)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MASTER_IN_USE"));
        mockMvc.perform(get("/api/v1/master-data/processes/{id}", UUID.randomUUID()).with(saleJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
