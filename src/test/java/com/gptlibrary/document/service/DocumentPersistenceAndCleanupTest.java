package com.gptlibrary.document.service;

import com.gptlibrary.document.model.DocumentEntity;
import com.gptlibrary.document.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
class DocumentPersistenceAndCleanupTest {

    @Autowired
    private DocumentPersistenceService documentPersistenceService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TikaParserService tikaParserService;

    @Autowired
    private DocumentChunkerService documentChunkerService;

    @Autowired
    private VectorStore realVectorStore;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM vector_store");
        documentRepository.deleteAll();
    }

    @Test
    void successfulIngestionEndsInIndexedStatus() {
        DocumentIngestionService service = new DocumentIngestionService(
                documentPersistenceService,
                tikaParserService,
                documentChunkerService,
                realVectorStore
        );

        String text = "Document ingestion should persist initial record, chunk text, and mark INDEXED.";
        MockMultipartFile file = new MockMultipartFile("file", "success.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));

        DocumentIngestionService.IngestionResult result = service.ingest(file);

        assertThat(result.status()).isEqualTo(DocumentEntity.DocumentStatus.INDEXED);

        DocumentEntity persisted = documentRepository.findById(result.documentId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(DocumentEntity.DocumentStatus.INDEXED);
        assertThat(persisted.getErrorMessage()).isNull();

        // Verify vector store has rows for this document
        Integer vectorCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?",
                Integer.class,
                result.documentId().toString()
        );
        assertThat(vectorCount).isGreaterThan(0);
    }

    @Test
    void simulatedVectorStoreFailurePersistsFailedStatusAndCleansUpVectors() {
        VectorStore failingVectorStore = mock(VectorStore.class);
        doThrow(new RuntimeException("Simulated vector-store connection failure with secret key AIzaSyDUMMYKEY12345678901234567890123"))
                .when(failingVectorStore).add(anyList());

        DocumentIngestionService failingService = new DocumentIngestionService(
                documentPersistenceService,
                tikaParserService,
                documentChunkerService,
                failingVectorStore
        );

        String text = "This document will fail during vector storage.";
        MockMultipartFile file = new MockMultipartFile("file", "fail.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> failingService.ingest(file))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Document indexing failed");

        // Verify document is committed as FAILED in PostgreSQL
        var allDocs = documentRepository.findAll();
        assertThat(allDocs).hasSize(1);
        DocumentEntity failedDoc = allDocs.get(0);
        assertThat(failedDoc.getStatus()).isEqualTo(DocumentEntity.DocumentStatus.FAILED);

        // Verify error message is sanitized and key is redacted
        assertThat(failedDoc.getErrorMessage())
                .doesNotContain("AIzaSyDUMMYKEY")
                .contains("[REDACTED_API_KEY]");

        // Verify 0 orphaned vectors exist for this failed document
        Integer vectorCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?",
                Integer.class,
                failedDoc.getId().toString()
        );
        assertThat(vectorCount).isEqualTo(0);
    }

    @Test
    void vectorCleanupDoesNotDeleteUnrelatedDocumentVectors() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();

        // Insert dummy vectors for docA and docB
        jdbcTemplate.update(
                "INSERT INTO vector_store (id, content, metadata, embedding) VALUES (uuid_generate_v4(), 'Doc A content', ?::jsonb, ?::vector)",
                "{\"document_id\": \"" + docA + "\"}",
                "[1.0, " + "0.0, ".repeat(766) + "0.0]"
        );
        jdbcTemplate.update(
                "INSERT INTO vector_store (id, content, metadata, embedding) VALUES (uuid_generate_v4(), 'Doc B content', ?::jsonb, ?::vector)",
                "{\"document_id\": \"" + docB + "\"}",
                "[1.0, " + "0.0, ".repeat(766) + "0.0]"
        );

        // Delete vectors for docA only
        int deleted = documentPersistenceService.cleanupVectors(docA);
        assertThat(deleted).isEqualTo(1);

        // Doc A vectors should be gone
        Integer countA = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?",
                Integer.class,
                docA.toString()
        );
        assertThat(countA).isEqualTo(0);

        // Doc B vectors must be preserved intact
        Integer countB = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?",
                Integer.class,
                docB.toString()
        );
        assertThat(countB).isEqualTo(1);
    }

    @Test
    void initialDocumentRecordCommittedInProcessingState() {
        DocumentEntity initial = documentPersistenceService.createInitialDocument("sample.pdf", "application/pdf", 1024L, 2);
        assertThat(initial.getId()).isNotNull();

        DocumentEntity reloaded = documentRepository.findById(initial.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentEntity.DocumentStatus.PROCESSING);
        assertThat(reloaded.getFilename()).isEqualTo("sample.pdf");
        assertThat(reloaded.getContentType()).isEqualTo("application/pdf");
    }
}
