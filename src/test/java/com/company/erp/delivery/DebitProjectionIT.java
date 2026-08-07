package com.company.erp.delivery;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DebitProjectionIT extends DeliveryTestSupport {

    @BeforeEach
    void prepare() {
        prepareProductionReferences();
        ensureDeliveryMonthRate();
    }

    /** Debit is a live view of POSTED lines: reversing a delivery removes its rows immediately. */
    @Test
    void listsPostedLinesAndDropsThemWhenTheDeliveryIsReversed() throws Exception {
        UUID positionId = finishedStockPosition("5.0000");
        UUID deliveryId = postedDelivery(positionId, "2.0000");
        String deliveryNo = jdbc.sql("SELECT delivery_no FROM delivery.delivery_note WHERE id = :id")
                .param("id", deliveryId).query(String.class).single();

        mockMvc.perform(get("/api/v1/debit-notes").with(saleJwt())
                        .param("customerId", customerId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items[0].deliveryNo").value(deliveryNo))
                .andExpect(jsonPath("$.items[0].debitReference").value(deliveryNo + "/01"))
                .andExpect(jsonPath("$.items[0].totalQty").value("2.0000"))
                .andExpect(jsonPath("$.items[0].amount").value("5.00"))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        mockMvc.perform(post("/api/v1/delivery-notes/{id}/reverse", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "reverse-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "reissue"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/debit-notes").with(saleJwt())
                        .param("customerId", customerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    /** The export must be the same query, not a second implementation that can drift. */
    @Test
    void exportsTheSameRowsAsTheListAndNeverEmitsExecutableFormulas() throws Exception {
        // A posted Delivery is immutable, so the formula-like name has to come from the Customer
        // record itself and be snapshotted through the normal draft path.
        jdbc.sql("UPDATE master_data.customer SET name = :name WHERE id = :id")
                .param("name", "=SUM(A1:A2)").param("id", customerId).update();
        UUID positionId = finishedStockPosition("5.0000");
        UUID deliveryId = postedDelivery(positionId, "2.0000");
        String deliveryNo = jdbc.sql("SELECT delivery_no FROM delivery.delivery_note WHERE id = :id")
                .param("id", deliveryId).query(String.class).single();

        var result = mockMvc.perform(get("/api/v1/debit-notes/export.xlsx").with(saleJwt())
                        .param("customerId", customerId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString(".xlsx")))
                .andReturn();

        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo(deliveryNo + "/01");
            var customerCell = sheet.getRow(1).getCell(2);
            assertThat(customerCell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(customerCell.getStringCellValue()).isEqualTo("=SUM(A1:A2)");
            assertThat(sheet.getLastRowNum()).isOne();
        }
    }

    @Test
    void exportRequiresItsOwnPermission() throws Exception {
        jdbc.sql("""
                        INSERT INTO identity.user_permission_override (user_id, permission_id, effect, reason, updated_by)
                        SELECT :userId, id, 'DENY', 'debit export test', :actor
                        FROM identity.permission
                        WHERE module_code = 'DELIVERY' AND action_code = 'EXPORT'
                        """).param("userId", saleId).param("actor", SYSTEM_USER_ID).update();

        mockMvc.perform(get("/api/v1/debit-notes/export.xlsx").with(saleJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // Reading the projection is a different grant and must still work.
        mockMvc.perform(get("/api/v1/debit-notes").with(saleJwt()))
                .andExpect(status().isOk());
    }

    private UUID postedDelivery(UUID positionId, String quantity) throws Exception {
        UUID deliveryId = createDraft(draftRequest(positionId, quantity));
        mockMvc.perform(post("/api/v1/delivery-notes/{id}/post", deliveryId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "post-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "shipping"))))
                .andExpect(status().isOk());
        return deliveryId;
    }
}
