package com.gptlibrary.rag.service;

import com.gptlibrary.document.model.DocumentEntity;
import com.gptlibrary.document.repository.DocumentRepository;
import com.gptlibrary.rag.config.RagProperties;
import com.gptlibrary.rag.dto.RagQueryRequest;
import com.gptlibrary.rag.dto.RagQueryResponse;
import com.gptlibrary.rag.dto.SourceExcerpt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final ChatModel chatModel;
    private final RagProperties ragProperties;

    public static final String INSUFFICIENT_EVIDENCE_ANSWER =
            "I could not find sufficient relevant evidence in the indexed library documents to answer your question.";

    public static final String SYSTEM_INSTRUCTIONS = """
            You are GPTLibrary Assistant, an accurate and truthful RAG system.
            Answer the user's question based strictly on the provided document context below.
            
            Guidelines:
            1. Answer directly and factually using only the provided context.
            2. If the context does not contain enough information to answer the question, state clearly that the provided documents do not contain sufficient evidence. Do not guess or fabricate facts.
            3. Rely only on the provided context. Do not invent fake source citations.
            """;

    public RagQueryResponse query(RagQueryRequest request) {
        request.validate();

        int topK = request.topK() != null ? request.topK() : ragProperties.getTopK();
        double similarityThreshold = request.similarityThreshold() != null
                ? request.similarityThreshold()
                : ragProperties.getSimilarityThreshold();

        log.info("Executing RAG query: topK={}, threshold={}", topK, similarityThreshold);

        // Step 1: Perform vector similarity search (query is normalized via NormalizingEmbeddingModel)
        SearchRequest searchRequest = SearchRequest.builder()
                .query(request.question())
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();

        List<Document> rawMatches = vectorStore.similaritySearch(searchRequest);
        if (rawMatches == null || rawMatches.isEmpty()) {
            log.info("No matching vector chunks found above similarity threshold {}.", similarityThreshold);
            return new RagQueryResponse(
                    request.question(),
                    INSUFFICIENT_EVIDENCE_ANSWER,
                    Collections.emptyList(),
                    0,
                    true
            );
        }

        // Step 2: Validate parent document status; strictly exclude failed, processing, or deleted documents
        Map<UUID, DocumentEntity> indexedParents = new HashMap<>();
        List<Document> validMatches = new ArrayList<>();

        for (Document doc : rawMatches) {
            Object docIdObj = doc.getMetadata().get("document_id");
            if (docIdObj == null) {
                continue;
            }
            try {
                UUID docId = UUID.fromString(docIdObj.toString());
                DocumentEntity parent = indexedParents.computeIfAbsent(docId, id ->
                        documentRepository.findById(id)
                                .filter(e -> e.getStatus() == DocumentEntity.DocumentStatus.INDEXED)
                                .orElse(null)
                );
                if (parent != null) {
                    validMatches.add(doc);
                } else {
                    log.warn("Excluding vector chunk because parent document {} is not in INDEXED status.", docId);
                }
            } catch (IllegalArgumentException e) {
                log.warn("Invalid document_id in vector chunk metadata: {}", docIdObj);
            }
        }

        // Step 3: If no valid indexed chunks remain, return insufficient evidence without calling LLM
        if (validMatches.isEmpty()) {
            log.info("All raw matches were excluded due to unindexed/failed parent status.");
            return new RagQueryResponse(
                    request.question(),
                    INSUFFICIENT_EVIDENCE_ANSWER,
                    Collections.emptyList(),
                    0,
                    true
            );
        }

        // Step 4: Construct structured source excerpts and context block
        List<SourceExcerpt> sources = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();
        int sourceIdx = 1;

        for (Document doc : validMatches) {
            UUID docId = UUID.fromString(doc.getMetadata().get("document_id").toString());
            DocumentEntity parent = indexedParents.get(docId);
            String filename = parent != null ? parent.getFilename() : (String) doc.getMetadata().getOrDefault("filename", "unknown");

            Integer chunkIndex = null;
            Object chunkIdxObj = doc.getMetadata().get("chunk_index");
            if (chunkIdxObj instanceof Number n) {
                chunkIndex = n.intValue();
            }

            Double score = doc.getScore();
            String content = doc.getText() != null ? doc.getText() : "";
            String excerpt = content.length() > 300 ? content.substring(0, 297) + "..." : content;

            sources.add(new SourceExcerpt(docId, filename, chunkIndex, score, excerpt));

            contextBuilder.append(String.format("[Source %d] (File: %s, Chunk: %s):\n%s\n\n",
                    sourceIdx++, filename, chunkIndex != null ? chunkIndex : "N/A", content));
        }

        // Step 5: Construct grounded prompt for Gemini
        String userPrompt = String.format("--- Document Context ---\n%s\n--- Question ---\n%s",
                contextBuilder.toString().trim(), request.question());

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_INSTRUCTIONS),
                new UserMessage(userPrompt)
        ));

        // Step 6: Invoke ChatModel (Gemini)
        ChatResponse response = chatModel.call(prompt);
        String answer = (response != null && response.getResult() != null && response.getResult().getOutput() != null)
                ? response.getResult().getOutput().getText()
                : INSUFFICIENT_EVIDENCE_ANSWER;

        return new RagQueryResponse(
                request.question(),
                answer,
                sources,
                validMatches.size(),
                false
        );
    }
}
