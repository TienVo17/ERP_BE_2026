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
    private static final float BODY_FONT_SIZE = 10;

    public byte[] write(String title, List<String> lines) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font font = loadFont(document);
            float maxWidth = PDRectangle.A4.getWidth() - (2 * MARGIN);
            String safeTitle = clean(font, title);
            List<String> safeLines = lines == null ? List.of() : lines.stream()
                    .flatMap(line -> wrap(font, clean(font, line), maxWidth).stream())
                    .toList();
            int linesPerPage = Math.max(1,
                    (int) ((PDRectangle.A4.getHeight() - (2 * MARGIN)) / LEADING) - 1);
            int offset = 0;

            do {
                int end = Math.min(offset + linesPerPage, safeLines.size());
                writePage(document, font, safeTitle, safeLines.subList(offset, end));
                offset = end;
            } while (offset < safeLines.size());

            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to generate PDF", exception);
        }
    }

    private static void writePage(
            PDDocument document,
            PDType0Font font,
            String title,
            List<String> lines) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(font, 14);
            content.setLeading(LEADING);
            content.newLineAtOffset(MARGIN, page.getMediaBox().getHeight() - MARGIN);
            content.showText(title);
            content.newLine();
            content.setFont(font, BODY_FONT_SIZE);
            for (String line : lines) {
                content.showText(line);
                content.newLine();
            }
            content.endText();
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

    private static List<String> wrap(
            PDType0Font font,
            String value,
            float maxWidth) {
        if (value.isEmpty()) {
            return List.of("");
        }
        List<String> result = new java.util.ArrayList<>();
        String remaining = value;
        while (!remaining.isEmpty()) {
            int length = remaining.length();
            while (length > 1 && width(font, remaining.substring(0, length)) > maxWidth) {
                length--;
            }
            if (length < remaining.length()) {
                int whitespace = remaining.lastIndexOf(' ', length - 1);
                if (whitespace > 0) {
                    length = whitespace;
                }
            }
            result.add(remaining.substring(0, length).stripTrailing());
            remaining = remaining.substring(length).stripLeading();
        }
        return result;
    }

    private static float width(PDType0Font font, String value) {
        try {
            return font.getStringWidth(value) / 1000 * BODY_FONT_SIZE;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to measure PDF text", exception);
        }
    }

    /**
     * Business text is free-form, but the embedded font only covers Latin and Vietnamese. PDFBox
     * throws on any codepoint it cannot encode, so unsupported characters — and all control
     * characters — become a placeholder instead of failing the whole report.
     */
    private static String clean(PDType0Font font, String value) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            String character = Character.isISOControl(codePoint)
                    ? " "
                    : new String(Character.toChars(codePoint));
            result.append(encodable(font, character) ? character : "?");
        });
        return result.toString();
    }

    private static boolean encodable(PDType0Font font, String character) {
        try {
            font.getStringWidth(character);
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            return false;
        }
    }
}
