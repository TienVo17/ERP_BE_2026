package com.company.erp.masterdata;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.company.erp.masterdata.api.MasterDataModels.ExchangeRateRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReferenceMasterControllerIT extends MasterDataControllerITSupport {

    @Autowired
    private com.company.erp.masterdata.application.ReferenceMasterService referenceMasterService;

    @Test
    void currencyIsReadOnlyAndSupportsAnOptionalActiveFilter() throws Exception {
        jdbc.sql("INSERT INTO master_data.currency (code, name, active) VALUES ('JPY', 'Japanese Yen', false)")
                .update();
        grantSalePermissions("ETC:VIEW");

        mockMvc.perform(get("/api/v1/master-data/currencies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/v1/master-data/currencies")
                        .with(saleJwt())
                        .param("active", "true")
                        .param("sort", "code,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].active").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))))
                .andExpect(jsonPath("$.filters.active").value(true))
                .andExpect(jsonPath("$.sort[0]").value("code,asc"));

        mockMvc.perform(post("/api/v1/master-data/currencies")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"EUR\",\"name\":\"Euro\"}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void currencyNameSortUsesCodeAsStableTieBreaker() throws Exception {
        jdbc.sql("INSERT INTO master_data.currency (code, name, active) VALUES ('AAA', 'Same Name', true), ('ZZZ', 'Same Name', true)").update();
        grantSalePermissions("ETC:VIEW");

        mockMvc.perform(get("/api/v1/master-data/currencies").with(saleJwt()).param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sort[0]").value("name,asc"))
                .andExpect(jsonPath("$.sort[1]").value("code,asc"));
        var codes = jdbc.sql("SELECT code FROM master_data.currency WHERE name='Same Name' ORDER BY lower(name), code")
                .query(String.class).list();
        assertThat(codes).containsExactly("AAA", "ZZZ");
    }

    @Test
    void uomCanonicalizesAndProtectsItsVersionWhileArchiveStillAllowsReferences() throws Exception {
        grantSalePermissions("ETC:VIEW", "ETC:CREATE", "ETC:UPDATE");

        mockMvc.perform(post("/api/v1/master-data/uoms")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", "  box ", "name", "Box"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("BOX"))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(post("/api/v1/master-data/uoms")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", " ea "))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_KEY"));

        mockMvc.perform(post("/api/v1/master-data/uoms")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(put("/api/v1/master-data/uoms/{id}", SEEDED_UOM_ID)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"99\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", "EA", "name", "Each updated"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        UUID referencedUomId = insertUom("REFERENCED-" + UUID.randomUUID());
        referenceUom(referencedUomId);
        mockMvc.perform(post("/api/v1/master-data/uoms/{id}/archive", referencedUomId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/master-data/uoms/{id}/archive", referencedUomId)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void missingUomIsNotFound() throws Exception {
        grantSalePermissions("ETC:VIEW");

        mockMvc.perform(get("/api/v1/master-data/uoms/{id}", UUID.randomUUID()).with(saleJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void exchangeRatesNormalizeMonthValidatePositiveStringRatesAndUseBusinessGuards() throws Exception {
        grantSalePermissions("ETC:VIEW", "ETC:CREATE", "ETC:UPDATE");

        mockMvc.perform(post("/api/v1/master-data/exchange-rates")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "month", "2026-03",
                                "vndUsdRate", "25000.125",
                                "wonUsdRate", "1300.5",
                                "source", "Central bank"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.month").value("2026-03"))
                .andExpect(jsonPath("$.vndUsdRate").value("25000.125000"))
                .andExpect(jsonPath("$.wonUsdRate").value("1300.500000"));

        UUID juneRateId = insertExchangeRate(LocalDate.of(2026, 6, 1));
        mockMvc.perform(post("/api/v1/master-data/exchange-rates")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "month", "2026-06", "vndUsdRate", "25000", "wonUsdRate", "1300"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_KEY"));

        mockMvc.perform(post("/api/v1/master-data/exchange-rates")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "month", "2026-07", "vndUsdRate", "0", "wonUsdRate", "1300"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(put("/api/v1/master-data/exchange-rates/{id}", juneRateId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "month", "2026-06", "vndUsdRate", "25001", "wonUsdRate", "1301"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        referenceExchangeRateWithPostedDelivery(juneRateId);
        mockMvc.perform(put("/api/v1/master-data/exchange-rates/{id}", juneRateId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "month", "2026-06", "vndUsdRate", "25001", "wonUsdRate", "1301"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MASTER_IN_USE"));
        mockMvc.perform(post("/api/v1/master-data/exchange-rates/{id}/archive", juneRateId)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MASTER_IN_USE"));
    }

    @Test
    void exactV007ExchangeRateImmutabilityRaceMapsToMasterInUseButOther55000DoesNot() throws Exception {
        UUID rateId = insertExchangeRate(LocalDate.of(2026, 8, 1));
        SQLException exactRace = new SQLException(
                "Exchange Rate used by a posted Delivery cannot be changed or deleted", "55000");
        var translated = com.company.erp.masterdata.application.MasterDataSupport.mapUsageRace(
                new UncategorizedSQLException("rate update", "UPDATE master_data.monthly_exchange_rate", exactRace),
                "Exchange rate");
        assertThat(translated.errorCode()).isEqualTo(com.company.erp.api.ApiErrorCode.MASTER_IN_USE);

        SQLException unrelated = new SQLException("Reversed Delivery Note is immutable", "55000");
        assertThatThrownBy(() -> com.company.erp.masterdata.application.MasterDataSupport.mapUsageRace(
                new UncategorizedSQLException("unrelated update", "UPDATE delivery.delivery_note", unrelated),
                "Exchange rate"))
                .isInstanceOf(UncategorizedSQLException.class);

        assertThat(referenceMasterService.rate(rateId).status()).isEqualTo("ACTIVE");
    }

    @Test
    void uomWritesRejectUnknownFieldsAndAuditTheAuthenticatedActor() throws Exception {
        grantSalePermissions("ETC:CREATE");

        mockMvc.perform(post("/api/v1/master-data/uoms")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AUDIT\",\"createdBy\":\"forged\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        MvcResult result = mockMvc.perform(post("/api/v1/master-data/uoms")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", "AUDIT"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID uomId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        var audit = jdbc.sql("""
                        SELECT actor_user_id, action, entity_type, after_data::text
                        FROM audit.audit_event
                        WHERE entity_id = :entityId
                        ORDER BY occurred_at DESC
                        LIMIT 1
                        """)
                .param("entityId", uomId)
                .query((rs, rowNumber) -> Map.of(
                        "actor", rs.getObject("actor_user_id", UUID.class),
                        "action", rs.getString("action"),
                        "entityType", rs.getString("entity_type"),
                        "after", rs.getString("after_data")))
                .single();
        assertThat(audit.get("actor")).isEqualTo(saleId);
        assertThat(audit.get("action")).isEqualTo("CREATE");
        assertThat(audit.get("entityType")).isEqualTo("UOM");
        assertThat(audit.get("after").toString()).contains("AUDIT").doesNotContain("createdBy");
    }
}
