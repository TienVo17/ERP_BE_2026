package com.company.erp.masterdata;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Walks the whole Phase 1 master surface with one SALE principal and one ADMIN principal.
 *
 * <p>Each per-master controller test proves its own contract in depth. This class proves the two
 * properties no single one of them can: that the approved SALE baseline behaves identically across
 * every scoped master, and that the same principal is refused every archive and every
 * administration endpoint. A master added later without its baseline grants fails here.
 */
class PhaseOneMasterJourneyIT extends MasterDataControllerITSupport {

    /** The approved SALE baseline: read and write every business master, archive none. */
    private static final List<String> SALE_MODULES =
            List.of("RAW_MATERIAL", "FINISHED_GOODS", "CUSTOMER", "SUPPLIER", "PROCESS", "ETC");

    private UUID uomId;
    private UUID supplierId;

    @BeforeEach
    void prepareReferences() {
        uomId = insertUom("JOURNEY-" + UUID.randomUUID());
        supplierId = insertSupplier("Journey supplier " + UUID.randomUUID());
    }

    /**
     * V011 seeds the baseline, so a migrated database already grants it. This asserts the seeded
     * shape rather than granting it, which is what makes the rest of the class meaningful: the
     * journey below exercises the permissions an operator really receives.
     */
    @Test
    void theMigratedDatabaseAlreadyCarriesTheApprovedSaleBaseline() {
        List<String> granted = jdbc.sql("""
                        SELECT p.module_code || ':' || p.action_code
                        FROM identity.role_permission rp
                        JOIN identity.permission p ON p.id = rp.permission_id
                        WHERE rp.role_id = '20000000-0000-0000-0000-000000000001'
                        ORDER BY 1
                        """)
                .query(String.class)
                .list();

        List<String> expected = SALE_MODULES.stream()
                .flatMap(module -> java.util.stream.Stream.of(
                        module + ":CREATE", module + ":UPDATE", module + ":VIEW"))
                .sorted()
                .toList();
        org.assertj.core.api.Assertions.assertThat(granted).containsExactlyElementsOf(expected);
    }

    @Test
    void saleBaselineCreatesAndUpdatesEveryScopedMasterAndIsRefusedEveryArchive() throws Exception {
        for (Master master : masters()) {
            MvcResult created = mockMvc.perform(post(master.collection())
                            .with(saleJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(master.create())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.version").value(0))
                    .andReturn();
            UUID id = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                    .get("id").asText());

            mockMvc.perform(put(master.collection() + "/{id}", id)
                            .with(saleJwt())
                            .header(HttpHeaders.IF_MATCH, "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(master.update())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1));

            mockMvc.perform(post(master.collection() + "/{id}/archive", id)
                            .with(saleJwt())
                            .header(HttpHeaders.IF_MATCH, "\"1\""))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            mockMvc.perform(post(master.collection() + "/{id}/archive", id)
                            .with(adminJwt())
                            .header(HttpHeaders.IF_MATCH, "\"1\""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ARCHIVED"));
        }
    }

    @Test
    void saleBaselineReadsCurrencyButHasNoWritePathForIt() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/currencies").with(saleJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].code").exists());

        // Currency is reference data owned by the migration; no controller may create one.
        mockMvc.perform(post("/api/v1/master-data/currencies")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", "XXX", "name", "Invented"))))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void saleBaselineCannotReachAnyAdministrationEndpoint() throws Exception {
        for (String path : List.of(
                "/api/v1/admin/users",
                "/api/v1/admin/roles",
                "/api/v1/admin/permissions",
                "/api/v1/admin/ip-allowlist",
                "/api/v1/admin/login-events")) {
            mockMvc.perform(get(path).with(saleJwt()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void everyScopedListPagesAtTwentyFiveAndRefusesAnUnknownSortField() throws Exception {
        for (Master master : masters()) {
            mockMvc.perform(get(master.collection()).with(saleJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.number").value(0))
                    .andExpect(jsonPath("$.page.size").value(25))
                    .andExpect(jsonPath("$.page.totalElements").exists());

            // A page size above the documented maximum must name the parameter, not answer 500.
            mockMvc.perform(get(master.collection()).with(saleJwt()).param("size", "101"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));

            mockMvc.perform(get(master.collection()).with(saleJwt()).param("sort", "password_hash,asc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void administratorArchiveIsStillRefusedWhenBusinessDataReferencesTheRecord() throws Exception {
        UUID referenced = insertFinishedGood("PRINT", "FG-JOURNEY-" + UUID.randomUUID(), "Referenced", uomId);
        referenceFinishedGood(referenced);

        mockMvc.perform(post("/api/v1/master-data/finished-goods/{id}/archive", referenced)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MASTER_IN_USE"));

        // A UOM carried by an existing raw material stays archivable: the guard blocks selecting an
        // archived reference, it does not freeze rows that already carry one.
        UUID referencedUom = insertUom("USED-" + UUID.randomUUID());
        referenceUom(referencedUom);
        mockMvc.perform(post("/api/v1/master-data/uoms/{id}/archive", referencedUom)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    private List<Master> masters() {
        String suffix = UUID.randomUUID().toString();
        return List.of(
                new Master("/api/v1/master-data/uoms",
                        map("code", "U" + suffix.substring(0, 8), "name", "Journey unit"),
                        map("code", "U" + suffix.substring(0, 8), "name", "Journey unit renamed")),
                new Master("/api/v1/master-data/exchange-rates",
                        map("month", futureMonth(), "vndUsdRate", "25000.000000", "wonUsdRate", "1300.000000"),
                        map("month", futureMonth(), "vndUsdRate", "25100.000000", "wonUsdRate", "1310.000000")),
                new Master("/api/v1/master-data/customers",
                        map("shortName", "C" + suffix.substring(0, 8), "name", "Journey customer", "currencyCode", "USD"),
                        map("name", "Journey customer renamed", "currencyCode", "USD")),
                new Master("/api/v1/master-data/suppliers",
                        map("name", "Journey supplier " + suffix),
                        map("name", "Journey supplier renamed " + suffix)),
                new Master("/api/v1/master-data/processes",
                        map("name", "Journey process " + suffix, "sequenceNo", 70, "qrValue", "QR-" + suffix),
                        map("name", "Journey process renamed " + suffix, "sequenceNo", 71)),
                new Master("/api/v1/master-data/raw-materials",
                        map("code", "RM-" + suffix, "name", "Journey material",
                                "uomId", uomId.toString(), "currencyCode", "USD", "supplierId", supplierId.toString()),
                        map("code", "RM-" + suffix, "name", "Journey material renamed",
                                "uomId", uomId.toString(), "currencyCode", "USD", "supplierId", supplierId.toString())),
                new Master("/api/v1/master-data/finished-goods",
                        map("productKind", "PRINT", "styleNo", "FG-" + suffix, "name", "Journey good",
                                "uomId", uomId.toString()),
                        map("productKind", "PRINT", "styleNo", "FG-" + suffix, "name", "Journey good renamed",
                                "uomId", uomId.toString())));
    }

    private static String futureMonth() {
        return LocalDate.now().plusMonths(7).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return values;
    }

    private record Master(String collection, Map<String, Object> create, Map<String, Object> update) {
    }
}
