package com.company.erp.production;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductionServiceIT extends ProductionTestSupport {

    @BeforeEach
    void prepare() {
        prepareProductionReferences();
    }

    @Test
    void wovenConfigurationReplacesChildrenAndReplaysByteForByte() throws Exception {
        UUID processId = insertProcess("Woven process " + UUID.randomUUID(), "PROCESS-" + UUID.randomUUID());
        UUID buyerOrderId = confirmedOrder(List.of(customItem("WOVEN-CONFIG", "3.0000")));
        UUID productionId = productionIds(buyerOrderId).getFirst();
        String key = "configure-woven-" + UUID.randomUUID();

        MvcResult first = mockMvc.perform(put("/api/v1/production-orders/{id}/configuration", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON)
                        .content(json(wovenConfiguration(processId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuration.weaveTypes[0]").value("THUONG"))
                .andExpect(jsonPath("$.configuration.yarnLines[0].yarnCode").value("P-01"))
                .andReturn();
        MvcResult replay = mockMvc.perform(put("/api/v1/production-orders/{id}/configuration", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON)
                        .content(json(wovenConfiguration(processId))))
                .andExpect(status().isOk()).andReturn();

        assertThat(replay.getResponse().getContentAsByteArray())
                .containsExactly(first.getResponse().getContentAsByteArray());
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_woven_config WHERE production_order_id = :id")
                .param("id", productionId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_event WHERE production_order_id = :id AND event_type = 'CONFIGURED'")
                .param("id", productionId).query(Long.class).single()).isOne();
    }

    @Test
    void rejectsCrossSubtypeAndArchivedProcessWithoutPartialConfiguration() throws Exception {
        UUID processId = insertProcess("Archived process " + UUID.randomUUID(), "PROCESS-" + UUID.randomUUID());
        UUID buyerOrderId = confirmedOrder(List.of(customItem("WOVEN-INVALID-CONFIG", "1.0000")));
        UUID productionId = productionIds(buyerOrderId).getFirst();

        mockMvc.perform(put("/api/v1/production-orders/{id}/configuration", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "cross-subtype-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(printConfiguration(processId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        jdbc.sql("UPDATE master_data.process_master SET status = 'ARCHIVED', version = version + 1, updated_by = :actor WHERE id = :id")
                .param("actor", SYSTEM_USER_ID).param("id", processId).update();
        mockMvc.perform(put("/api/v1/production-orders/{id}/configuration", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "archived-process-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(wovenConfiguration(processId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_woven_config WHERE production_order_id = :id")
                .param("id", productionId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_event WHERE production_order_id = :id AND event_type = 'CONFIGURED'")
                .param("id", productionId).query(Long.class).single()).isZero();
    }

    @Test
    void finishCreatesOneReconciledStockPositionUsingTrustedCommandIdentityAndReplays() throws Exception {
        UUID processId = insertProcess("Finish process " + UUID.randomUUID(), "PROCESS-" + UUID.randomUUID());
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, "4.0000")));
        UUID productionId = productionIds(buyerOrderId).getFirst();
        mockMvc.perform(put("/api/v1/production-orders/{id}/configuration", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "finish-config-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(printConfiguration(processId))))
                .andExpect(status().isOk());
        String key = "finish-" + UUID.randomUUID();
        Map<String, Object> command = Map.of("reason", "completed production");

        MvcResult first = mockMvc.perform(post("/api/v1/production-orders/{id}/finish", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON).content(json(command)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productionOrder.status").value("FINISHED"))
                .andExpect(jsonPath("$.productionOrder.producedQty").value("4.0000"))
                .andExpect(jsonPath("$.stockPosition.currentQty").value("4.0000"))
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/production-orders/{id}/finish", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON).content(json(command)))
                .andExpect(status().isOk()).andReturn();

        assertThat(replay.getResponse().getContentAsByteArray())
                .containsExactly(first.getResponse().getContentAsByteArray());
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.stock_position WHERE production_order_id = :id")
                .param("id", productionId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.stock_movement WHERE source_id = :id AND movement_type = 'PRODUCTION'")
                .param("id", productionId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT sm.idempotency_key = ir.command_id::text
                        FROM inventory.stock_movement sm
                        JOIN system.idempotency_record ir ON ir.idempotency_key = :key
                        WHERE sm.source_id = :productionId
                        """).param("key", key).param("productionId", productionId)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT reconciled FROM inventory.stock_ledger_reconciliation WHERE production_order_id = :id")
                .param("id", productionId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void finishRejectsClientProducedQuantityAndMissingConfiguration() throws Exception {
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, "1.0000")));
        UUID productionId = productionIds(buyerOrderId).getFirst();
        mockMvc.perform(post("/api/v1/production-orders/{id}/finish", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "finish-overpost-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "done", "producedQty", "1.0000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/v1/production-orders/{id}/finish", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "finish-no-config-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "done"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.stock_position WHERE production_order_id = :id")
                .param("id", productionId).query(Long.class).single()).isZero();
    }

    @Test
    void compactFiftyMemberGroupResponseStaysWithinReplayBoundary() throws Exception {
        UUID groupId = UUID.randomUUID();
        UUID buyerOrderId = UUID.randomUUID();
        var members = java.util.stream.IntStream.range(0, 50)
                .mapToObj(index -> new com.company.erp.production.api.ProductionModels.ProductionGroupMemberResponse(
                        UUID.randomUUID(), 1, "PR-2026-%06d".formatted(index + 1),
                        "P".repeat(120), groupId, "PG-SO-2026-000001-001", buyerOrderId,
                        UUID.randomUUID(), index % 2 == 0 ? "PRINT" : "WOVEN", "99999999999999.0000", "OPEN"))
                .toList();
        var response = new com.company.erp.production.api.ProductionModels.ProductionGroupResponse(
                groupId, 0, "PG-SO-2026-000001-001", buyerOrderId, members);

        assertThat(objectMapper.writeValueAsBytes(response).length).isLessThanOrEqualTo(256 * 1024);
    }

    @Test
    void groupReplaysByteForByteAndUngroupClearsMembersAndAddsEvents() throws Exception {
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, "1.0000"), customItem("WOVEN-UNGROUP", "1.0000")));
        List<UUID> ids = productionIds(buyerOrderId);
        String key = "group-replay-" + UUID.randomUUID();
        Map<String, Object> request = Map.of("members", List.of(member(ids.get(0), 0), member(ids.get(1), 0)), "reason", "bundle");
        MvcResult first = mockMvc.perform(post("/api/v1/production-groups").with(saleJwt())
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/production-groups").with(saleJwt())
                        .header(IDEMPOTENCY, key).contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(replay.getResponse().getContentAsByteArray()).containsExactly(first.getResponse().getContentAsByteArray());
        UUID groupId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_group WHERE buyer_order_id = :id")
                .param("id", buyerOrderId).query(Long.class).single()).isOne();
        mockMvc.perform(post("/api/v1/production-groups/{id}/ungroup", groupId).with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"").header(IDEMPOTENCY, "ungroup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "separate"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(groupId.toString()))
                .andExpect(jsonPath("$.members[0].groupId").doesNotExist());
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_group WHERE id = :id")
                .param("id", groupId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_event WHERE production_order_id IN (:ids) AND event_type = 'UNGROUPED'")
                .param("ids", ids).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidMembershipAndStaleVersionWithoutPartialMutation() throws Exception {
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, "1.0000"), customItem("WOVEN-INVALID", "1.0000")));
        List<UUID> ids = productionIds(buyerOrderId);
        Map<String, Object> stale = Map.of("members", List.of(member(ids.get(0), 1), member(ids.get(1), 0)));
        mockMvc.perform(post("/api/v1/production-groups").with(saleJwt()).header(IDEMPOTENCY, "stale-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(stale)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        Map<String, Object> duplicate = Map.of("members", List.of(member(ids.get(0), 0), member(ids.get(0), 0)));
        mockMvc.perform(post("/api/v1/production-groups").with(saleJwt()).header(IDEMPOTENCY, "duplicate-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(duplicate)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GROUP_MEMBERSHIP_INVALID"));
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_order WHERE buyer_order_id = :id AND production_group_id IS NOT NULL")
                .param("id", buyerOrderId).query(Long.class).single()).isZero();
    }
}
