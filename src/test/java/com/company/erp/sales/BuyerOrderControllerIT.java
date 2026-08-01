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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BuyerOrderControllerIT extends BuyerOrderTestSupport {

    @BeforeEach
    void prepare() {
        prepareBuyerOrderReferences();
    }

    @Test
    void saleCreatesStandardAndCustomLinesFromCanonicalMasterSnapshots() throws Exception {
        Map<String, Object> request = orderRequest(mutableItems(
                standardItem(finishedGoodId, "2.5000", "3.333333"),
                customItem("  CUSTOM-01  ", "4", "1.250000")));

        MvcResult result = mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .header(IDEMPOTENCY, "create-lines-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("STANDBY"))
                .andExpect(jsonPath("$.sysPoNo").value(org.hamcrest.Matchers.matchesPattern("SO-[0-9]{4}-[0-9]{6}")))
                .andExpect(jsonPath("$.customerName").value("Fixture Customer"))
                .andExpect(jsonPath("$.customerShortName").exists())
                .andExpect(jsonPath("$.items[0].finishedGoodId").value(finishedGoodId.toString()))
                .andExpect(jsonPath("$.items[0].styleNo").value(org.hamcrest.Matchers.startsWith("STYLE-")))
                .andExpect(jsonPath("$.items[0].uomCode").exists())
                .andExpect(jsonPath("$.items[0].orderQty").value("2.5000"))
                .andExpect(jsonPath("$.items[0].useStockQty").value("0.0000"))
                .andExpect(jsonPath("$.items[0].productionQty").value("2.5000"))
                .andExpect(jsonPath("$.items[0].unitPrice").value("3.333333"))
                .andExpect(jsonPath("$.items[0].amount").value("8.33"))
                .andExpect(jsonPath("$.items[1].finishedGoodId").doesNotExist())
                .andExpect(jsonPath("$.items[1].styleNo").value("CUSTOM-01"))
                .andExpect(jsonPath("$.items[1].currencyCode").value("USD"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("createdBy", "updatedBy", "customerCurrencyCode");
    }

    @Test
    void listDetailUpdateAndCopyFollowPagingVersionAndIdentityContracts() throws Exception {
        UUID id = createOrder(standardRequest());

        mockMvc.perform(get("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .param("status", "STANDBY")
                        .param("customerId", customerId.toString())
                        .param("sort", "poDate,desc"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(25))
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.filters.status").value("STANDBY"))
                .andExpect(jsonPath("$.sort[0]").value("poDate,desc"))
                .andExpect(jsonPath("$.sort[1]").value("id,asc"));

        mockMvc.perform(get("/api/v1/buyer-orders").with(saleJwt()).param("sort", "createdBy,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Map<String, Object> updated = standardRequest();
        updated.put("buyerPo", "UPDATED-PO");
        updated.put("items", List.of(customItem("UPDATED-STYLE", "3.0000", "2.000000")));
        mockMvc.perform(put("/api/v1/buyer-orders/{id}", id)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "update-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.buyerPo").value("UPDATED-PO"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].styleNo").value("UPDATED-STYLE"));

        MvcResult copy = mockMvc.perform(post("/api/v1/buyer-orders/{id}/copy", id)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "copy-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("buyerPo", "COPIED-PO", "reason", "customer reorder"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(id.toString())))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.status").value("STANDBY"))
                .andExpect(jsonPath("$.buyerPo").value("COPIED-PO"))
                .andReturn();

        MvcResult detail = mockMvc.perform(get("/api/v1/buyer-orders/{id}", id).with(saleJwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        String originalNumber = objectMapper.readTree(detail.getResponse().getContentAsString())
                .get("sysPoNo").asText();
        var copiedJson = objectMapper.readTree(copy.getResponse().getContentAsString());
        String copiedNumber = copiedJson.get("sysPoNo").asText();
        UUID copiedId = UUID.fromString(copiedJson.get("id").asText());
        assertThat(copiedNumber).isNotEqualTo(originalNumber);
        assertThat(jdbc.sql("SELECT action FROM audit.audit_event WHERE entity_id = :id")
                .param("id", copiedId).query(String.class).list()).containsExactly("COPY");
    }

    @Test
    void rejectsOverpostingInvalidReferencesAndMissingCommandHeaders() throws Exception {
        Map<String, Object> overposted = standardRequest();
        overposted.put("status", "CONFIRMED");
        mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .header(IDEMPOTENCY, "overpost-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(overposted)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(standardRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        UUID otherCustomer = insertCustomer("OTHER-" + UUID.randomUUID());
        UUID otherContact = insertCustomerContact(otherCustomer, "Wrong contact", false);
        Map<String, Object> wrongContact = standardRequest();
        wrongContact.put("customerContactId", otherContact.toString());
        mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .header(IDEMPOTENCY, "wrong-contact-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(wrongContact)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        UUID id = createOrder(standardRequest());
        mockMvc.perform(put("/api/v1/buyer-orders/{id}", id)
                        .with(saleJwt())
                        .header(IDEMPOTENCY, "missing-version-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(standardRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
    }

    @Test
    void rejectsAmountsThatCannotFitThePersistedMonetaryRange() throws Exception {
        Map<String, Object> oversized = orderRequest(List.of(
                standardItem(finishedGoodId, "99999999999999", "999999999999")));

        mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .header(IDEMPOTENCY, "oversized-amount-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(oversized)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsResponsesThatCouldExceedTheReplayBoundaryBeforeDomainEffects() throws Exception {
        Map<String, Object> oversized = standardRequest();
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ((List<?>) oversized.get("items")).getFirst();
        item.put("remark", "x".repeat(201));

        mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .header(IDEMPOTENCY, "oversized-response-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(oversized)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void overlongCustomSnapshotFieldIsRejectedBeforeIdempotencyClaim() throws Exception {
        Map<String, Object> invalid = orderRequest(List.of(
                customItem("x".repeat(121), "1.0000", "1.000000")));
        String key = "overlong-custom-" + UUID.randomUUID();

        MvcResult first = mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .header(IDEMPOTENCY, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .header(IDEMPOTENCY, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(replay.getResponse().getContentAsByteArray())
                .containsExactly(first.getResponse().getContentAsByteArray());
        assertThat(jdbc.sql("SELECT count(*) FROM system.idempotency_record WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM sales.buyer_order WHERE created_by = :actor")
                .param("actor", saleId).query(Long.class).single()).isZero();
    }

    @Test
    void expectedValidationProblemIsCompletedAndReplayedByteForByte() throws Exception {
        Map<String, Object> invalid = standardRequest();
        invalid.put("customerId", UUID.randomUUID().toString());
        String key = "invalid-replay-" + UUID.randomUUID();

        MvcResult first = mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .header(IDEMPOTENCY, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .header(IDEMPOTENCY, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(replay.getResponse().getContentAsByteArray())
                .containsExactly(first.getResponse().getContentAsByteArray());
        assertThat(jdbc.sql("SELECT status FROM system.idempotency_record WHERE idempotency_key = :key")
                .param("key", key).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT count(*) FROM sales.buyer_order WHERE created_by = :actor")
                .param("actor", saleId).query(Long.class).single()).isZero();
    }

    @Test
    void permissionDenyOverridesSaleGrantForDirectApiCall() throws Exception {
        jdbc.sql("""
                        INSERT INTO identity.user_permission_override (
                            user_id, permission_id, effect, reason, updated_by
                        )
                        SELECT :userId, id, 'DENY', 'buyer order contract test', :actorId
                        FROM identity.permission
                        WHERE module_code = 'BUYER_ORDER' AND action_code = 'CREATE'
                        """)
                .param("userId", saleId)
                .param("actorId", SYSTEM_USER_ID)
                .update();

        mockMvc.perform(post("/api/v1/buyer-orders")
                        .with(saleJwt())
                        .header(IDEMPOTENCY, "denied-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(standardRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
