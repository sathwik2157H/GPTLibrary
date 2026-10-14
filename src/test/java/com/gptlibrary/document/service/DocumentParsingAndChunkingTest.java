package com.gptlibrary.document.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParsingAndChunkingTest {

    private final TikaParserService tikaParserService = new TikaParserService();
    private final DocumentChunkerService chunkerService = new DocumentChunkerService();

    @Test
    void parseValidTxtFile() throws Exception {
        String content = "Hello world! This is a test document for GPTLibrary RAG ingestion pipeline.";
        MockMultipartFile file = new MockMultipartFile("file", "sample.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8));

        TikaParserService.ParsedDocument parsed = tikaParserService.parse(file);
        assertThat(parsed.content()).contains("Hello world!");
        assertThat(parsed.detectedContentType()).isEqualTo("text/plain");
    }

    @Test
    void parseValidPdfFile() throws Exception {
        byte[] pdfBytes = TestDocumentFixtures.createSamplePdfBytes("GPTLibrary automated PDF parsing test.");
        MockMultipartFile file = new MockMultipartFile("file", "test-doc.pdf", "application/pdf", pdfBytes);

        TikaParserService.ParsedDocument parsed = tikaParserService.parse(file);
        assertThat(parsed.content()).contains("GPTLibrary automated PDF parsing test.");
        assertThat(parsed.detectedContentType()).isEqualTo("application/pdf");
        assertThat(parsed.pageCount()).isEqualTo(1);
    }

    @Test
    void parseValidDocxFile() throws Exception {
        byte[] docxBytes = TestDocumentFixtures.createSampleDocxBytes("GPTLibrary automated DOCX parsing test.");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-doc.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes
        );

        TikaParserService.ParsedDocument parsed = tikaParserService.parse(file);
        assertThat(parsed.content()).contains("GPTLibrary automated DOCX parsing test.");
        assertThat(parsed.detectedContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void rejectEmptyFileContent() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        assertThatThrownBy(() -> tikaParserService.parse(file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectUnsupportedFileType() {
        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", new byte[]{1, 2, 3, 4});
        assertThatThrownBy(() -> tikaParserService.parse(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void handleMalformedPdfGracefully() {
        byte[] corruptedBytes = TestDocumentFixtures.createCorruptedPdfBytes();
        MockMultipartFile file = new MockMultipartFile("file", "corrupted.pdf", "application/pdf", corruptedBytes);

        // Corrupted PDF has no readable text or fails parser
        assertThatThrownBy(() -> tikaParserService.parse(file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handleMalformedDocxGracefully() {
        byte[] corruptedBytes = TestDocumentFixtures.createCorruptedDocxBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "corrupted.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                corruptedBytes
        );

        assertThatThrownBy(() -> tikaParserService.parse(file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunkingPreservesContentAndProducesCorrectOverlapAcrossMultipleChunks() {
        // Generate 1200 words -> with targetWords=384 and step=346 -> 4 chunks
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 1200; i++) {
            sb.append("token").append(i).append(" ");
        }
        String text = sb.toString().trim();

        // 512 target tokens (~384 words), 50 overlap tokens (~38 words)
        List<DocumentChunkerService.DocumentChunk> chunks = chunkerService.chunk(text, 512, 50);

        assertThat(chunks).hasSize(4);

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunkerService.DocumentChunk chunk = chunks.get(i);
            assertThat(chunk.chunkIndex()).isEqualTo(i);
            assertThat(chunk.tokenCount()).isGreaterThan(0);

            if (i > 0) {
                // Verify overlap between chunk i-1 and chunk i
                String[] prevWords = chunks.get(i - 1).content().split("\\s+");
                String[] currentWords = chunk.content().split("\\s+");

                // Expected overlap words: (int)(50 * 0.75) = 37 words
                int expectedOverlapWords = (int) (50 * 0.75);
                for (int j = 0; j < expectedOverlapWords; j++) {
                    assertThat(currentWords[j]).isEqualTo(prevWords[prevWords.length - expectedOverlapWords + j]);
                }
            }
        }
    }
}
