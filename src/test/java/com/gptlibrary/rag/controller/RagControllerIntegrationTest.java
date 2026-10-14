package com.gptlibrary.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptlibrary.document.model.DocumentEntity;
import com.gptlibrary.document.repository.DocumentRepository;
import com.gptlibrary.document.service.DocumentIngestionService;
import com.gptlibrary.rag.dto.RagQueryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RagControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM vector_store");
        documentRepository.deleteAll();
    }

    @Test
    void queryWithAnswerableDocumentReturnsAnswerAndSources() throws Exception {
        String content = "PostgreSQL pgvector provides vector similarity search capabilities for RAG applications. It supports HNSW and IVFFlat indexes.";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pgvector-overview.txt",
                "text/plain",
                content.getBytes(StandardCharsets.UTF_8)
        );
        DocumentIngestionService.IngestionResult ingestionResult = ingestionService.ingest(file);
        assertThat(ingestionResult.status()).isEqualTo(DocumentEntity.DocumentStatus.INDEXED);

        RagQueryRequest request = new RagQueryRequest(
                "What does PostgreSQL pgvector provide for RAG?",
                3,
                0.1 // Low threshold in test mode to match controlled test embedding
        );

        mockMvc.perform(post("/api/v1/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientEvidence").value(false))
                .andExpect(jsonPath("$.question").value("What does PostgreSQL pgvector provide for RAG?"))
                .andExpect(jsonPath("$.answer").isNotEmpty())
                .andExpect(jsonPath("$.sources").isNotEmpty())
                .andExpect(jsonPath("$.sources[0].filename").value("pgvector-overview.txt"))
                .andExpect(jsonPath("$.sources[0].documentId").value(ingestionResult.documentId().toString()))
                .andExpect(jsonPath("$.sources[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.retrievedChunkCount").value(1));
    }

    @Test
    void queryWithAbsentAnswerOrHighThresholdReturnsInsufficientEvidence() throws Exception {
        // High threshold ensures no vectors match
        RagQueryRequest request = new RagQueryRequest(
                "What is the average rainfall in the Amazon basin?",
                3,
                0.999
        );

        mockMvc.perform(post("/api/v1/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientEvidence").value(true))
                .andExpect(jsonPath("$.sources", hasSize(0)))
                .andExpect(jsonPath("$.retrievedChunkCount").value(0))
                .andExpect(jsonPath("$.answer").value("I could not find sufficient relevant evidence in the indexed library documents to answer your question."));
    }

    @Autowired
    private com.gptlibrary.document.service.DocumentPersistenceService documentPersistenceService;

    @Test
    void queryExcludesFailedDocumentsEvenIfVectorsExistInStore() throws Exception {
        // Create document explicitly in FAILED status
        DocumentEntity initial = documentPersistenceService.createInitialDocument(
                "corrupted-data.pdf",
                "application/pdf",
                100L,
                null
        );
        documentPersistenceService.markFailed(initial.getId(), "Document extraction failed");
        UUID failedDocId = initial.getId();

        // Manually inject a vector pointing to the failed document
        jdbcTemplate.update(
                "INSERT INTO vector_store (id, content, metadata, embedding) VALUES (uuid_generate_v4(), 'Sensitive text from failed doc', ?::jsonb, ?::vector)",
                "{\"document_id\": \"" + failedDocId + "\", \"filename\": \"corrupted-data.pdf\", \"chunk_index\": 0}",
                "[1.0, " + "0.0, ".repeat(766) + "0.0]"
        );

        RagQueryRequest request = new RagQueryRequest(
                "Read from corrupted data",
                3,
                0.1
        );

        mockMvc.perform(post("/api/v1/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientEvidence").value(true))
                .andExpect(jsonPath("$.sources", hasSize(0)))
                .andExpect(jsonPath("$.retrievedChunkCount").value(0));
    }

    @Test
    void blankQuestionReturnsBadRequest() throws Exception {
        RagQueryRequest request = new RagQueryRequest("   ", 3, 0.5);

        mockMvc.perform(post("/api/v1/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Question must not be blank."));
    }
}
