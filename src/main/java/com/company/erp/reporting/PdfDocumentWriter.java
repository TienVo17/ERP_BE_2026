package com.company.erp.reporting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

@Component
public class PdfDocumentWriter {

    private static final float MARGIN = 48;
    private static final float LEADING = 18;

    public byte[] write(String title, List<String> lines) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType0Font font = loadFont(document);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 14);
                content.setLeading(LEADING);
                content.newLineAtOffset(MARGIN, page.getMediaBox().getHeight() - MARGIN);
                content.showText(clean(title));
                content.newLine();
                content.setFont(font, 10);
                for (String line : lines) {
                    content.showText(clean(line));
                    content.newLine();
                }
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to generate PDF", exception);
        }
    }

    private static PDType0Font loadFont(PDDocument document) throws IOException {
        try (InputStream font = PdfDocumentWriter.class.getResourceAsStream("/reporting/NotoSans-Regular.ttf")) {
            if (font == null) {
                throw new IllegalStateException("Embedded report font is missing");
            }
            return PDType0Font.load(document, font);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", "");
    }
}
