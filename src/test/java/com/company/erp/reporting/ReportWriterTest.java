package com.company.erp.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ReportWriterTest {

    @Test
    void producesAParseablePdfWithCanonicalUnicodeText() throws Exception {
        PdfDocumentWriter writer = new PdfDocumentWriter();

        byte[] bytes = writer.write("Production PR-2032-000001", List.of(
                "Status: OPEN",
                "Customer: Công ty Dệt May",
                "Quantity: 12.5000"));

        try (var document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isOne();
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Production PR-2032-000001", "Status: OPEN", "Công ty Dệt May", "12.5000");
        }
    }

    @Test
    void producesAParseableXlsxAndNeverCreatesFormulaCellsFromUntrustedText() throws Exception {
        XlsxWorkbookWriter writer = new XlsxWorkbookWriter();

        byte[] bytes = writer.write(
                List.of("Reference", "Customer", "Date", "Amount"),
                List.of(
                        List.of("DN-2032-000001/01", "  =SUM(A1:A2)", LocalDate.of(2032, 1, 2), "12.30"),
                        List.of("DN-2032-000001/02", "\t+cmd", LocalDate.of(2032, 1, 3), "0.00")));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("  =SUM(A1:A2)");
            assertThat(sheet.getRow(2).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("\t+cmd");
            assertThat(sheet.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.STRING);
        }
    }
}
