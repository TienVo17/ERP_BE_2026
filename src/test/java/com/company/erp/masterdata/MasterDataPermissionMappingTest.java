package com.company.erp.masterdata;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import com.company.erp.masterdata.api.FinishedGoodController;
import com.company.erp.masterdata.api.PartyMasterController;
import com.company.erp.masterdata.api.RawMaterialController;
import com.company.erp.masterdata.api.ProcessMasterController;
import com.company.erp.masterdata.api.ReferenceMasterController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

class MasterDataPermissionMappingTest {

    @Test
    void everyMasterDataEndpointUsesItsExactBusinessPermission() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ReferenceMasterController.currencies", "ETC:VIEW");
        expected.put("ReferenceMasterController.uoms", "ETC:VIEW");
        expected.put("ReferenceMasterController.createUom", "ETC:CREATE");
        expected.put("ReferenceMasterController.uom", "ETC:VIEW");
        expected.put("ReferenceMasterController.updateUom", "ETC:UPDATE");
        expected.put("ReferenceMasterController.archiveUom", "ETC:ARCHIVE");
        expected.put("ReferenceMasterController.rates", "ETC:VIEW");
        expected.put("ReferenceMasterController.createRate", "ETC:CREATE");
        expected.put("ReferenceMasterController.rate", "ETC:VIEW");
        expected.put("ReferenceMasterController.updateRate", "ETC:UPDATE");
        expected.put("ReferenceMasterController.archiveRate", "ETC:ARCHIVE");
        expected.put("PartyMasterController.customers", "CUSTOMER:VIEW");
        expected.put("PartyMasterController.createCustomer", "CUSTOMER:CREATE");
        expected.put("PartyMasterController.customer", "CUSTOMER:VIEW");
        expected.put("PartyMasterController.updateCustomer", "CUSTOMER:UPDATE");
        expected.put("PartyMasterController.archiveCustomer", "CUSTOMER:ARCHIVE");
        expected.put("PartyMasterController.customerContacts", "CUSTOMER:VIEW");
        expected.put("PartyMasterController.createCustomerContact", "CUSTOMER:UPDATE");
        expected.put("PartyMasterController.customerContact", "CUSTOMER:VIEW");
        expected.put("PartyMasterController.updateCustomerContact", "CUSTOMER:UPDATE");
        expected.put("PartyMasterController.archiveCustomerContact", "CUSTOMER:UPDATE");
        expected.put("PartyMasterController.suppliers", "SUPPLIER:VIEW");
        expected.put("PartyMasterController.createSupplier", "SUPPLIER:CREATE");
        expected.put("PartyMasterController.supplier", "SUPPLIER:VIEW");
        expected.put("PartyMasterController.updateSupplier", "SUPPLIER:UPDATE");
        expected.put("PartyMasterController.archiveSupplier", "SUPPLIER:ARCHIVE");
        expected.put("PartyMasterController.supplierContacts", "SUPPLIER:VIEW");
        expected.put("PartyMasterController.createSupplierContact", "SUPPLIER:UPDATE");
        expected.put("PartyMasterController.supplierContact", "SUPPLIER:VIEW");
        expected.put("PartyMasterController.updateSupplierContact", "SUPPLIER:UPDATE");
        expected.put("PartyMasterController.archiveSupplierContact", "SUPPLIER:UPDATE");
        expected.put("ProcessMasterController.list", "PROCESS:VIEW");
        expected.put("ProcessMasterController.create", "PROCESS:CREATE");
        expected.put("ProcessMasterController.get", "PROCESS:VIEW");
        expected.put("ProcessMasterController.update", "PROCESS:UPDATE");
        expected.put("ProcessMasterController.archive", "PROCESS:ARCHIVE");
        expected.put("RawMaterialController.list", "RAW_MATERIAL:VIEW");
        expected.put("RawMaterialController.create", "RAW_MATERIAL:CREATE");
        expected.put("RawMaterialController.get", "RAW_MATERIAL:VIEW");
        expected.put("RawMaterialController.update", "RAW_MATERIAL:UPDATE");
        expected.put("RawMaterialController.archive", "RAW_MATERIAL:ARCHIVE");
        expected.put("FinishedGoodController.list", "FINISHED_GOODS:VIEW");
        expected.put("FinishedGoodController.create", "FINISHED_GOODS:CREATE");
        expected.put("FinishedGoodController.get", "FINISHED_GOODS:VIEW");
        expected.put("FinishedGoodController.update", "FINISHED_GOODS:UPDATE");
        expected.put("FinishedGoodController.archive", "FINISHED_GOODS:ARCHIVE");

        assertMappings(expected, ReferenceMasterController.class, PartyMasterController.class,
                ProcessMasterController.class, RawMaterialController.class, FinishedGoodController.class);
    }

    private static void assertMappings(Map<String, String> expected, Class<?>... controllers) {
        int protectedEndpointCount = 0;
        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);
                if (authorization == null) {
                    continue;
                }
                protectedEndpointCount++;
                String key = controller.getSimpleName() + "." + method.getName();
                assertThat(expected).as(key).containsKey(key);
                assertThat(authorization.value()).as(key)
                        .isEqualTo("hasAuthority('" + expected.get(key) + "')");
            }
        }
        assertThat(protectedEndpointCount).isEqualTo(expected.size());
    }
}
