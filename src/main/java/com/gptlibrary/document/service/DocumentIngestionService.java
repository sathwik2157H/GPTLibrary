package com.gptlibrary.document.service;

import com.gptlibrary.document.model.DocumentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentIngestionService {

    private final DocumentPersistenceService documentPersistenceService;
    private final TikaParserService tikaParserService;
    private final DocumentChunkerService documentChunkerService;
    private final VectorStore vectorStore;

    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50 MB
    public static final int TARGET_TOKEN_SIZE = 512;
    public static final int OVERLAP_TOKENS = 50;

    public record IngestionResult(UUID documentId, String filename, String contentType, long fileSize, int chunkCount, DocumentEntity.DocumentStatus status) {}

    public IngestionResult ingest(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Cannot upload empty or missing file.");
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File size exceeds 50 MB limit. Provided: " + file.getSize() + " bytes");
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";

        // Step 1: Detect and validate content type before creating any database record
        String detectedType;
        try {
            detectedType = tikaParserService.detectContentType(file);
        } catch (Exception e) {
            log.error("Failed to detect content type for {}: {}", originalFilename, e.getMessage());
            throw new IllegalArgumentException("Could not inspect document file format: " + e.getMessage(), e);
        }

        if (!tikaParserService.isSupportedContentType(detectedType)) {
            throw new IllegalArgumentException("Unsupported file type: " + detectedType + ". Only PDF, DOCX, and TXT are supported.");
        }

        // Step 2: Persist initial document record in PROCESSING state in an isolated transaction
        DocumentEntity entity = documentPersistenceService.createInitialDocument(
                originalFilename,
                detectedType,
                file.getSize(),
                null
        );

        try {
            // Step 3: Parse document content
            TikaParserService.ParsedDocument parsed = tikaParserService.parse(file);

            // Step 4: Chunk text into ~512 token chunks with 50-token overlap
            List<DocumentChunkerService.DocumentChunk> chunks = documentChunkerService.chunk(
                    parsed.content(),
                    TARGET_TOKEN_SIZE,
                    OVERLAP_TOKENS
            );

            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("Document produced 0 text chunks.");
            }

            // Step 5: Clean up any pre-existing vectors for this document (ensures clean retry/re-ingest)
            documentPersistenceService.cleanupVectors(entity.getId());

            // Step 6: Build Spring AI Document objects with documentId and chunk metadata
            List<Document> aiDocuments = new ArrayList<>();
            for (DocumentChunkerService.DocumentChunk chunk : chunks) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("document_id", entity.getId().toString());
                metadata.put("filename", originalFilename);
                metadata.put("chunk_index", chunk.chunkIndex());
                metadata.put("token_count", chunk.tokenCount());
                if (parsed.pageCount() != null) {
                    metadata.put("page_count", parsed.pageCount());
                }

                Document doc = new Document(chunk.content(), metadata);
                aiDocuments.add(doc);
            }

            // Step 7: Embed & Save into VectorStore (normalized automatically via NormalizingEmbeddingModel)
            vectorStore.add(aiDocuments);

            // Step 8: Mark document as INDEXED in an isolated committed transaction
            documentPersistenceService.markIndexed(entity.getId(), parsed.pageCount());

            log.info("Successfully ingested document: id={}, chunks={}", entity.getId(), chunks.size());
            return new IngestionResult(
                    entity.getId(),
                    entity.getFilename(),
                    entity.getContentType(),
                    entity.getFileSize(),
                    chunks.size(),
                    DocumentEntity.DocumentStatus.INDEXED
            );

        } catch (Exception e) {
            log.error("Failed to index document: id={}", entity.getId(), e);
            // Purge any partially written or orphaned vectors immediately
            documentPersistenceService.cleanupVectors(entity.getId());
            // Persist FAILED status with sanitized error message in an isolated transaction
            documentPersistenceService.markFailed(entity.getId(), e.getMessage());

            String safeMessage = DocumentPersistenceService.sanitizeErrorMessage(e.getMessage());
            throw new RuntimeException("Document indexing failed: " + safeMessage, e);
        }
    }
}
