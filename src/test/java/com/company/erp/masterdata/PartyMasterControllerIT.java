package com.company.erp.masterdata;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartyMasterControllerIT extends MasterDataControllerITSupport {

    @Test
    void customerHasCanonicalImmutableShortNameAndRejectsStaleOrUsedChanges() throws Exception {
        grantSalePermissions("CUSTOMER:VIEW", "CUSTOMER:CREATE", "CUSTOMER:UPDATE");

        MvcResult created = mockMvc.perform(post("/api/v1/master-data/customers")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "shortName", "  acme ", "name", "Acme Incorporated", "currencyCode", "USD"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortName").value("ACME"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        UUID customerId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/master-data/customers")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "shortName", "ACME", "name", "Duplicate", "currencyCode", "USD"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_KEY"));

        mockMvc.perform(put("/api/v1/master-data/customers/{id}", customerId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"12\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme Updated", "currencyCode", "USD"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mockMvc.perform(put("/api/v1/master-data/customers/{id}", customerId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Updated\",\"currencyCode\":\"USD\",\"shortName\":\"FORGED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        referenceCustomer(customerId);
        mockMvc.perform(put("/api/v1/master-data/customers/{id}", customerId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme Used", "currencyCode", "USD"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MASTER_IN_USE"));
        mockMvc.perform(post("/api/v1/master-data/customers/{id}/archive", customerId)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MASTER_IN_USE"));
    }

    @Test
    void contactsAreOwnedByTheirCustomerAndARequestedDefaultReplacesThePreviousDefault() throws Exception {
        grantSalePermissions("CUSTOMER:VIEW", "CUSTOMER:UPDATE");
        UUID customerId = insertCustomer("CONTACT-" + UUID.randomUUID());
        UUID otherCustomerId = insertCustomer("OTHER-" + UUID.randomUUID());
        UUID existingDefaultId = insertCustomerContact(customerId, "First PIC", true);

        MvcResult created = mockMvc.perform(post("/api/v1/master-data/customers/{id}/contacts", customerId)
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Second PIC", "isDefault", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Second PIC"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andReturn();
        UUID secondContactId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/master-data/customers/{id}", customerId).with(saleJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contacts[?(@.isDefault == true)]", hasSize(1)))
                .andExpect(jsonPath("$.contacts[?(@.isDefault == true)].id").value(hasItem(secondContactId.toString())));

        mockMvc.perform(get("/api/v1/master-data/customers/{id}/contacts/{contactId}", otherCustomerId, existingDefaultId)
                        .with(saleJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(put("/api/v1/master-data/customers/{id}/contacts/{contactId}", customerId, secondContactId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"8\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Second PIC", "isDefault", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void contactUpdateFalseOrOmittedPreservesDefaultAndTruePromotesSiblingWithCompleteAudit() throws Exception {
        grantSalePermissions("CUSTOMER:VIEW", "CUSTOMER:UPDATE");
        UUID customerId = insertCustomer("DEFAULT-" + UUID.randomUUID());
        UUID defaultId = insertCustomerContact(customerId, "Default PIC", true);
        UUID siblingId = insertCustomerContact(customerId, "Sibling PIC", false);

        mockMvc.perform(put("/api/v1/master-data/customers/{id}/contacts/{contactId}", customerId, defaultId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Default PIC edited", "isDefault", false))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isDefault").value(true));

        mockMvc.perform(put("/api/v1/master-data/customers/{id}/contacts/{contactId}", customerId, defaultId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Default PIC edited again"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isDefault").value(true));

        mockMvc.perform(put("/api/v1/master-data/customers/{id}/contacts/{contactId}", customerId, siblingId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("division", "Sales", "name", "Promoted PIC", "telephone", "123",
                                "email", "pic@example.com", "remark", "primary", "isDefault", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isDefault").value(true));

        assertThat(jdbc.sql("SELECT is_default FROM master_data.customer_contact WHERE id=:id")
                .param("id", defaultId).query(Boolean.class).single()).isFalse();
        var audits = jdbc.sql("SELECT entity_id, before_data::text, after_data::text FROM audit.audit_event WHERE entity_type='CUSTOMER_CONTACT' AND entity_id IN (:ids) ORDER BY occurred_at")
                .param("ids", Set.of(defaultId, siblingId))
                .query((rs, row) -> Map.of("id", rs.getObject("entity_id", UUID.class),
                        "before", rs.getString("before_data"), "after", rs.getString("after_data"))).list();
        assertThat(audits).anySatisfy(audit -> {
            assertThat(audit.get("id")).isEqualTo(defaultId);
            assertThat(audit.get("before").toString()).contains("\"isDefault\": true");
            assertThat(audit.get("after").toString()).contains("\"isDefault\": false");
        });
        assertThat(audits).anySatisfy(audit -> assertThat(audit.get("after").toString())
                .contains("division", "telephone", "email", "remark", "pic@example.com"));
    }

    @Test
    void endpointPermissionsFollowSystematicViewCreateUpdateArchiveMapping() throws Exception {
        UUID customerId = insertCustomer("PERMISSION-" + UUID.randomUUID());
        UUID contactId = insertCustomerContact(customerId, "Permission PIC", false);

        mockMvc.perform(get("/api/v1/master-data/customers"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        grantSalePermissions("CUSTOMER:VIEW");
        mockMvc.perform(get("/api/v1/master-data/customers").with(saleJwt())).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/master-data/customers").with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("shortName", "ALLOWED-" + UUID.randomUUID(), "name", "Allowed", "currencyCode", "USD"))))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/master-data/customers/{id}/contacts/{contactId}", customerId, contactId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Allowed"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/master-data/customers/{id}/archive", customerId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/master-data/customers/{id}/archive", customerId)
                        .with(adminJwt()).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
    }

    @Test
    void customerArchiveRequiresAdminAndMissingCustomersAreNotFound() throws Exception {
        UUID customerId = insertCustomer("ARCHIVE-" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/master-data/customers/{id}/archive", customerId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/master-data/customers/{id}/archive", customerId)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(get("/api/v1/master-data/customers/{id}", UUID.randomUUID()).with(adminJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void supplierContactsSupportLifecycleOwnershipAndDefaultRules() throws Exception {
        grantSalePermissions("SUPPLIER:VIEW", "SUPPLIER:UPDATE");
        UUID supplierId = insertSupplier("Supplier " + UUID.randomUUID());
        UUID otherSupplierId = insertSupplier("Other " + UUID.randomUUID());

        MvcResult first = mockMvc.perform(post("/api/v1/master-data/suppliers/{id}/contacts", supplierId)
                        .with(saleJwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "First Supplier PIC", "isDefault", true))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.isDefault").value(true)).andReturn();
        UUID firstId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText());
        MvcResult second = mockMvc.perform(post("/api/v1/master-data/suppliers/{id}/contacts", supplierId)
                        .with(saleJwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Second Supplier PIC"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.isDefault").value(false)).andReturn();
        UUID secondId = UUID.fromString(objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/master-data/suppliers/{id}/contacts", supplierId).with(saleJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)));
        mockMvc.perform(get("/api/v1/master-data/suppliers/{id}/contacts/{contactId}", otherSupplierId, firstId).with(saleJwt()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(put("/api/v1/master-data/suppliers/{id}/contacts/{contactId}", supplierId, secondId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Second promoted", "isDefault", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isDefault").value(true));
        mockMvc.perform(post("/api/v1/master-data/suppliers/{id}/contacts/{contactId}/archive", supplierId, secondId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.isDefault").value(false));
        assertThat(jdbc.sql("SELECT count(*) FROM master_data.supplier_contact WHERE supplier_id=:id AND status='ACTIVE' AND is_default")
                .param("id", supplierId).query(Long.class).single()).isZero();
    }

    @Test
    void supplierUsesCanonicalNameHasContactRulesAndRejectsArchiveWhenReferenced() throws Exception {
        grantSalePermissions("SUPPLIER:VIEW", "SUPPLIER:CREATE", "SUPPLIER:UPDATE");

        MvcResult created = mockMvc.perform(post("/api/v1/master-data/suppliers")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  Textile Source  "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Textile Source"))
                .andReturn();
        UUID supplierId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/master-data/suppliers")
                        .with(saleJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "TEXTILE SOURCE"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_KEY"));

        mockMvc.perform(put("/api/v1/master-data/suppliers/{id}", supplierId)
                        .with(saleJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\",\"usedInPurchaseOrder\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        referenceSupplier(supplierId);
        mockMvc.perform(post("/api/v1/master-data/suppliers/{id}/archive", supplierId)
                        .with(adminJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MASTER_IN_USE"));
    }
}
