package com.gptlibrary.document.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class TestDocumentFixtures {

    private TestDocumentFixtures() {}

    /**
     * Generates a minimal, valid single-page PDF document in memory using Apache PDFBox.
     */
    public static byte[] createSamplePdfBytes(String text) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText(text != null ? text : "Sample PDF content for GPTLibrary testing.");
                contentStream.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate test PDF fixture", e);
        }
    }

    /**
     * Generates a minimal, valid single-paragraph DOCX document in memory using Apache POI.
     */
    public static byte[] createSampleDocxBytes(String text) {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph paragraph = doc.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(text != null ? text : "Sample DOCX content for GPTLibrary testing.");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate test DOCX fixture", e);
        }
    }

    /**
     * Creates corrupted PDF bytes that cannot be parsed.
     */
    public static byte[] createCorruptedPdfBytes() {
        return "%PDF-1.4\ncorrupted_not_valid_pdf_trailer_or_objects_random_junk".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates corrupted DOCX bytes that cannot be decompressed.
     */
    public static byte[] createCorruptedDocxBytes() {
        return "PK\u0003\u0004corrupted_docx_header_without_zip_table".getBytes(StandardCharsets.UTF_8);
    }
}
