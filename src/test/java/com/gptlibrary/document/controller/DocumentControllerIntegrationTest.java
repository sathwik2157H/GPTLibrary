package com.gptlibrary.document.controller;

import com.gptlibrary.document.repository.DocumentRepository;
import com.gptlibrary.document.service.TestDocumentFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    void uploadValidTextDocumentSuccessfully() throws Exception {
        String content = "Spring AI provides vector database abstractions. This test verifies document ingestion and chunk indexing into PGVector.";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "rag-guide.txt",
                "text/plain",
                content.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/v1/documents/upload").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("rag-guide.txt"))
                .andExpect(jsonPath("$.status").value("INDEXED"))
                .andExpect(jsonPath("$.chunkCount").value(1));

        assertThat(documentRepository.findAll()).isNotEmpty();
    }

    @Test
    void uploadValidPdfDocumentSuccessfully() throws Exception {
        byte[] pdfBytes = TestDocumentFixtures.createSamplePdfBytes("PDF document ingestion test content for GPTLibrary.");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "manual.pdf",
                "application/pdf",
                pdfBytes
        );

        mockMvc.perform(multipart("/api/v1/documents/upload").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("manual.pdf"))
                .andExpect(jsonPath("$.status").value("INDEXED"))
                .andExpect(jsonPath("$.chunkCount").value(1));
    }

    @Test
    void uploadValidDocxDocumentSuccessfully() throws Exception {
        byte[] docxBytes = TestDocumentFixtures.createSampleDocxBytes("DOCX document ingestion test content for GPTLibrary.");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "spec.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes
        );

        mockMvc.perform(multipart("/api/v1/documents/upload").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("spec.docx"))
                .andExpect(jsonPath("$.status").value("INDEXED"))
                .andExpect(jsonPath("$.chunkCount").value(1));
    }

    @Test
    void uploadEmptyFileFails() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        mockMvc.perform(multipart("/api/v1/documents/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void uploadUnsupportedFileTypeFails() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", new byte[]{0x00, 0x01, 0x02});
        mockMvc.perform(multipart("/api/v1/documents/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void listDocumentsReturnsUploadedRecords() throws Exception {
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isOk());
    }
}
