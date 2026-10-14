package com.gptlibrary.document.service;

import com.gptlibrary.document.model.DocumentEntity;
import com.gptlibrary.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentPersistenceService {

    private final DocumentRepository documentRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Persists the initial document record with PROCESSING status in an isolated,
     * immediately committed transaction before lengthy parsing or embedding begins.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DocumentEntity createInitialDocument(String filename, String contentType, long fileSize, Integer pageCount) {
        DocumentEntity entity = DocumentEntity.builder()
                .filename(filename)
                .contentType(contentType)
                .fileSize(fileSize)
                .status(DocumentEntity.DocumentStatus.PROCESSING)
                .pageCount(pageCount)
                .build();
        DocumentEntity saved = documentRepository.save(entity);
        log.info("Committed initial document record in PROCESSING state: id={}", saved.getId());
        return saved;
    }

    /**
     * Updates document status to INDEXED in an isolated, immediately committed transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIndexed(UUID documentId, Integer pageCount) {
        documentRepository.findById(documentId).ifPresent(entity -> {
            entity.setStatus(DocumentEntity.DocumentStatus.INDEXED);
            if (pageCount != null) {
                entity.setPageCount(pageCount);
            }
            entity.setErrorMessage(null);
            documentRepository.save(entity);
            log.info("Committed document state as INDEXED: id={}", documentId);
        });
    }

    /**
     * Updates document status to FAILED in an isolated, immediately committed transaction,
     * ensuring error details are safely persisted even when an outer process fails or rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID documentId, String rawErrorMessage) {
        documentRepository.findById(documentId).ifPresent(entity -> {
            entity.setStatus(DocumentEntity.DocumentStatus.FAILED);
            entity.setErrorMessage(sanitizeErrorMessage(rawErrorMessage));
            documentRepository.save(entity);
            log.info("Committed document state as FAILED: id={}", documentId);
        });
    }

    /**
     * Safely purges any vector store chunks associated with the specified documentId.
     * Prevents orphaned or partially written vectors from contaminating RAG retrieval.
     * Guaranteed to target ONLY the specified documentId via JSON metadata matching.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanupVectors(UUID documentId) {
        if (documentId == null) {
            return 0;
        }
        int deleted = jdbcTemplate.update(
                "DELETE FROM vector_store WHERE metadata->>'document_id' = ?",
                documentId.toString()
        );
        if (deleted > 0) {
            log.info("Cleaned up {} orphaned vector chunk(s) for documentId={}", deleted, documentId);
        }
        return deleted;
    }

    /**
     * Sanitizes error messages to protect sensitive credentials, API keys, and headers
     * from ever being recorded in the database or surfaced in exception logs.
     */
    public static String sanitizeErrorMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "Unknown document ingestion error.";
        }
        // Redact standard Google AI keys (starts with AIza followed by alphanumeric/dashes)
        String sanitized = rawMessage.replaceAll("AIza[0-9A-Za-z\\-_]+", "[REDACTED_API_KEY]");
        // Redact query parameter keys e.g. ?key=... or &key=...
        sanitized = sanitized.replaceAll("(?i)(key|api[-_]?key)=[^&\\s]+", "$1=[REDACTED]");
        // Redact bearer tokens
        sanitized = sanitized.replaceAll("(?i)bearer\\s+[a-zA-Z0-9_\\-\\.]+", "Bearer [REDACTED]");
        // Redact sensitive headers
        sanitized = sanitized.replaceAll("(?i)x-goog-api-key:\\s*[^\\r\\n]+", "x-goog-api-key: [REDACTED]");
        // Truncate message to avoid unbounded database growth
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 497) + "...";
        }
        return sanitized;
    }
}
