package com.company.erp.sales;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BuyerOrderServiceIT extends BuyerOrderTestSupport {

    @BeforeEach
    void prepare() {
        prepareBuyerOrderReferences();
    }

    @Test
    void confirmCreatesOneOpenProductionOrderPerActiveLineAndNoStock() throws Exception {
        UUID orderId = createOrder(orderRequest(mutableItems(
                standardItem(finishedGoodId, "2.0000", "4.500000"),
                customItem("WOVEN-CUSTOM", "3.0000", "2.000000"))));

        MvcResult result = mockMvc.perform(post("/api/v1/buyer-orders/{id}/confirm", orderId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "confirm-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "approved for production"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerOrder.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.buyerOrder.version").value(1))
                .andExpect(jsonPath("$.productionOrders.length()").value(2))
                .andExpect(jsonPath("$.productionOrders[0].productionNo")
                        .value(org.hamcrest.Matchers.matchesPattern("PR-[0-9]{4}-[0-9]{6}")))
                .andExpect(jsonPath("$.productionOrders[0].qrValue")
                        .value(org.hamcrest.Matchers.matchesPattern("PR-[0-9]{4}-[0-9]{6}")))
                .andExpect(jsonPath("$.productionOrders[0].productNo")
                        .value(org.hamcrest.Matchers.startsWith("STYLE-")))
                .andExpect(jsonPath("$.productionOrders[0].plannedQty").value("2.0000"))
                .andExpect(jsonPath("$.productionOrders[0].status").value("OPEN"))
                .andExpect(jsonPath("$.productionOrders[0].configuration.productKind").value("PRINT"))
                .andExpect(jsonPath("$.productionOrders[0].configuration.processes.length()").value(0))
                .andReturn();

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_order WHERE buyer_order_id = :id")
                .param("id", orderId).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM production.production_event pe
                        JOIN production.production_order po ON po.id = pe.production_order_id
                        WHERE po.buyer_order_id = :id AND pe.event_type = 'CREATED'
                        """).param("id", orderId).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.stock_position sp JOIN production.production_order po ON po.id = sp.production_order_id WHERE po.buyer_order_id = :id")
                .param("id", orderId).query(Long.class).single()).isZero();

        var response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.at("/productionOrders/0/productionNo").asText())
                .isEqualTo(response.at("/productionOrders/0/qrValue").asText());
    }

    @Test
    void sameConfirmKeyReplaysExactBodyWithoutDuplicateEffects() throws Exception {
        UUID orderId = createOrder(standardRequest());
        String key = "confirm-replay-" + UUID.randomUUID();

        MvcResult first = confirm(orderId, key, "\"0\"");
        MvcResult replay = confirm(orderId, key, "\"0\"");

        assertThat(replay.getResponse().getStatus()).isEqualTo(first.getResponse().getStatus());
        assertThat(replay.getResponse().getContentAsByteArray())
                .containsExactly(first.getResponse().getContentAsByteArray());
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_order WHERE buyer_order_id = :id")
                .param("id", orderId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event
                        WHERE entity_type = 'BUYER_ORDER' AND entity_id = :id AND action = 'CONFIRM'
                        """).param("id", orderId).query(Long.class).single()).isOne();
    }

    @Test
    void reopenCancelsProductionAndRetainsPriorRevisionThenReconfirmCreatesFreshHistory() throws Exception {
        UUID orderId = createOrder(standardRequest());
        confirm(orderId, "initial-confirm-" + UUID.randomUUID(), "\"0\"");
        UUID oldItem = jdbc.sql("SELECT id FROM sales.buyer_order_item WHERE buyer_order_id = :id AND active_revision")
                .param("id", orderId).query(UUID.class).single();
        UUID oldProduction = jdbc.sql("SELECT id FROM production.production_order WHERE buyer_order_id = :id")
                .param("id", orderId).query(UUID.class).single();

        mockMvc.perform(post("/api/v1/buyer-orders/{id}/reopen", orderId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "reopen-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "buyer changed artwork"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STANDBY"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].activeRevision").value(false))
                .andExpect(jsonPath("$.items[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[1].activeRevision").value(true));

        assertThat(jdbc.sql("SELECT status FROM production.production_order WHERE id = :id")
                .param("id", oldProduction).query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_event WHERE production_order_id = :id AND event_type = 'CANCELLED'")
                .param("id", oldProduction).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT status FROM sales.buyer_order_item WHERE id = :id")
                .param("id", oldItem).query(String.class).single()).isEqualTo("CANCELLED");

        MvcResult reconfirm = confirm(orderId, "reconfirm-" + UUID.randomUUID(), "\"2\"");
        assertThat(objectMapper.readTree(reconfirm.getResponse().getContentAsString())
                .at("/productionOrders/0/buyerOrderItemId").asText()).isNotEqualTo(oldItem.toString());
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_order WHERE buyer_order_id = :id")
                .param("id", orderId).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void confirmRevalidatesThatMasterContactIsStillActive() throws Exception {
        UUID orderId = createOrder(standardRequest());
        jdbc.sql("UPDATE master_data.customer_contact SET status = 'ARCHIVED', is_default = false WHERE id = :id")
                .param("id", contactId).update();

        mockMvc.perform(post("/api/v1/buyer-orders/{id}/confirm", orderId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "archived-contact-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "must revalidate"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(jdbc.sql("SELECT status FROM sales.buyer_order WHERE id = :id")
                .param("id", orderId).query(String.class).single()).isEqualTo("STANDBY");
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_order WHERE buyer_order_id = :id")
                .param("id", orderId).query(Long.class).single()).isZero();
    }

    @Test
    void reopenRefusesGroupedProductionWithoutPartialCancellation() throws Exception {
        UUID orderId = createOrder(orderRequest(List.of(
                standardItem(finishedGoodId, "1.0000", "1.000000"),
                customItem("SECOND", "1.0000", "1.000000"))));
        confirm(orderId, "group-confirm-" + UUID.randomUUID(), "\"0\"");
        UUID groupId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO production.production_group (
                            id, buyer_order_id, group_no, created_by, updated_by
                        ) VALUES (:id, :orderId, :groupNo, :actorId, :actorId)
                        """)
                .param("id", groupId)
                .param("orderId", orderId)
                .param("groupNo", "PG-TEST-" + UUID.randomUUID().toString().substring(0, 12))
                .param("actorId", SYSTEM_USER_ID)
                .update();
        jdbc.sql("UPDATE production.production_order SET production_group_id = :groupId WHERE buyer_order_id = :orderId")
                .param("groupId", groupId).param("orderId", orderId).update();

        mockMvc.perform(post("/api/v1/buyer-orders/{id}/reopen", orderId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "blocked-reopen-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "should be refused"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_ACTIVITY_EXISTS"));

        assertThat(jdbc.sql("SELECT status FROM sales.buyer_order WHERE id = :id")
                .param("id", orderId).query(String.class).single()).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_order WHERE buyer_order_id = :id AND status = 'OPEN'")
                .param("id", orderId).query(Long.class).single()).isEqualTo(2);
    }

    private MvcResult confirm(UUID orderId, String key, String version) throws Exception {
        return mockMvc.perform(post("/api/v1/buyer-orders/{id}/confirm", orderId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, version)
                        .header(IDEMPOTENCY, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "approved"))))
                .andExpect(status().isOk())
                .andReturn();
    }
}
