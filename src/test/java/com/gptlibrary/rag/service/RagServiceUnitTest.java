package com.gptlibrary.rag.service;

import com.gptlibrary.document.model.DocumentEntity;
import com.gptlibrary.document.repository.DocumentRepository;
import com.gptlibrary.rag.config.RagProperties;
import com.gptlibrary.rag.dto.RagQueryRequest;
import com.gptlibrary.rag.dto.RagQueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagServiceUnitTest {

    private VectorStore mockVectorStore;
    private DocumentRepository mockDocumentRepository;
    private ChatModel mockChatModel;
    private RagProperties ragProperties;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        mockVectorStore = mock(VectorStore.class);
        mockDocumentRepository = mock(DocumentRepository.class);
        mockChatModel = mock(ChatModel.class);
        ragProperties = new RagProperties();
        ragProperties.setSimilarityThreshold(0.45);
        ragProperties.setTopK(4);

        ragService = new RagService(
                mockVectorStore,
                mockDocumentRepository,
                mockChatModel,
                ragProperties
        );
    }

    @Test
    void rejectBlankOrInvalidQuestion() {
        assertThatThrownBy(() -> ragService.query(new RagQueryRequest("", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Question must not be blank");

        assertThatThrownBy(() -> ragService.query(new RagQueryRequest("   ", null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ragService.query(new RagQueryRequest("valid", -1, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");

        assertThatThrownBy(() -> ragService.query(new RagQueryRequest("valid", 5, 1.5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("similarityThreshold");
    }

    @Test
    void returnInsufficientEvidenceWhenNoMatchingVectorsFound() {
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());

        RagQueryResponse response = ragService.query(new RagQueryRequest("What is quantum computing?", 3, 0.7));

        assertThat(response.insufficientEvidence()).isTrue();
        assertThat(response.answer()).isEqualTo(RagService.INSUFFICIENT_EVIDENCE_ANSWER);
        assertThat(response.sources()).isEmpty();
        assertThat(response.retrievedChunkCount()).isEqualTo(0);

        // Crucial: ChatModel must NOT be called when no context exists
        verify(mockChatModel, never()).call(any(Prompt.class));
    }

    @Test
    void excludeChunksBelongingToFailedOrProcessingDocuments() {
        UUID failedDocId = UUID.randomUUID();
        DocumentEntity failedDoc = DocumentEntity.builder()
                .id(failedDocId)
                .filename("failed-doc.pdf")
                .status(DocumentEntity.DocumentStatus.FAILED)
                .build();

        Document chunkFromFailedDoc = new Document(
                "Chunk from failed doc",
                Map.of("document_id", failedDocId.toString(), "chunk_index", 0)
        );

        when(mockVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunkFromFailedDoc));
        when(mockDocumentRepository.findById(failedDocId))
                .thenReturn(Optional.of(failedDoc));

        RagQueryResponse response = ragService.query(new RagQueryRequest("What happened?", null, null));

        assertThat(response.insufficientEvidence()).isTrue();
        assertThat(response.sources()).isEmpty();
        verify(mockChatModel, never()).call(any(Prompt.class));
    }

    @Test
    void queryConstructsPromptAndReturnsAnswerWithSourcesForIndexedDocument() {
        UUID docId = UUID.randomUUID();
        DocumentEntity indexedDoc = DocumentEntity.builder()
                .id(docId)
                .filename("spring-ai-guide.pdf")
                .status(DocumentEntity.DocumentStatus.INDEXED)
                .build();

        Document validChunk = Document.builder()
                .text("Spring AI integrates LLMs with vector databases using standard models.")
                .metadata(Map.of(
                        "document_id", docId.toString(),
                        "filename", "spring-ai-guide.pdf",
                        "chunk_index", 2
                ))
                .score(0.88)
                .build();

        when(mockVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(validChunk));
        when(mockDocumentRepository.findById(docId))
                .thenReturn(Optional.of(indexedDoc));

        ChatResponse mockChatResponse = new ChatResponse(List.of(
                new Generation(new org.springframework.ai.chat.messages.AssistantMessage("Spring AI connects LLMs and vector stores."))
        ));
        when(mockChatModel.call(any(Prompt.class))).thenReturn(mockChatResponse);

        RagQueryResponse response = ragService.query(new RagQueryRequest("How does Spring AI work?", 2, 0.6));

        assertThat(response.insufficientEvidence()).isFalse();
        assertThat(response.answer()).isEqualTo("Spring AI connects LLMs and vector stores.");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).documentId()).isEqualTo(docId);
        assertThat(response.sources().get(0).filename()).isEqualTo("spring-ai-guide.pdf");
        assertThat(response.sources().get(0).chunkIndex()).isEqualTo(2);
        assertThat(response.sources().get(0).score()).isEqualTo(0.88);

        // Verify prompt structure
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(mockChatModel).call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();

        UserMessage userMsg = (UserMessage) prompt.getInstructions().get(1);
        assertThat(userMsg.getText())
                .contains("spring-ai-guide.pdf")
                .contains("How does Spring AI work?")
                .contains("Spring AI integrates LLMs with vector databases");
    }

    @Test
    void queryReturnsInsufficientEvidenceWhenModelReportsMissingFactEvenWithRetrievedChunks() {
        UUID docId = UUID.randomUUID();
        DocumentEntity indexedDoc = DocumentEntity.builder()
                .id(docId)
                .filename("ai-testing-tools.pdf")
                .status(DocumentEntity.DocumentStatus.INDEXED)
                .build();

        // Chunks are retrieved because broad topic matches (e.g. AI testing tools)
        Document chunk = Document.builder()
                .text("AI testing tools have evolved significantly in modern software engineering.")
                .metadata(Map.of(
                        "document_id", docId.toString(),
                        "filename", "ai-testing-tools.pdf",
                        "chunk_index", 0
                ))
                .score(0.82)
                .build();

        when(mockVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunk));
        when(mockDocumentRepository.findById(docId))
                .thenReturn(Optional.of(indexedDoc));

        // The specific question is "what accuracy percentage do the best AI testing tools achieve?"
        // The LLM accurately answers that the document does not mention the accuracy percentage.
        String llmRefusal = "The provided document discusses AI testing tools, but does not mention the accuracy percentage achieved by the best tools.";
        ChatResponse mockChatResponse = new ChatResponse(List.of(
                new Generation(new org.springframework.ai.chat.messages.AssistantMessage(llmRefusal))
        ));
        when(mockChatModel.call(any(Prompt.class))).thenReturn(mockChatResponse);

        RagQueryResponse response = ragService.query(new RagQueryRequest(
                "According to the document, what accuracy percentage do the best AI testing tools achieve?",
                3,
                0.6
        ));

        // The flag must be TRUE despite chunks having been retrieved and sources preserved
        assertThat(response.insufficientEvidence()).isTrue();
        assertThat(response.answer()).isEqualTo(llmRefusal);
        assertThat(response.sources()).hasSize(1);
        assertThat(response.retrievedChunkCount()).isEqualTo(1);
        assertThat(response.sources().get(0).filename()).isEqualTo("ai-testing-tools.pdf");
    }

    @Test
    void queryFiltersOutChunksBelowSimilarityThreshold() {
        UUID docId = UUID.randomUUID();
        DocumentEntity indexedDoc = DocumentEntity.builder()
                .id(docId)
                .filename("doc.pdf")
                .status(DocumentEntity.DocumentStatus.INDEXED)
                .build();

        Document chunkBelowThreshold = Document.builder()
                .text("Low relevance text.")
                .metadata(Map.of(
                        "document_id", docId.toString(),
                        "filename", "doc.pdf",
                        "chunk_index", 0
                ))
                .score(0.45) // below request threshold 0.70
                .build();

        when(mockVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunkBelowThreshold));
        when(mockDocumentRepository.findById(docId))
                .thenReturn(Optional.of(indexedDoc));

        RagQueryResponse response = ragService.query(new RagQueryRequest("Query needing high confidence", 3, 0.70));

        assertThat(response.insufficientEvidence()).isTrue();
        assertThat(response.sources()).isEmpty();
        assertThat(response.retrievedChunkCount()).isEqualTo(0);
        assertThat(response.answer()).isEqualTo(RagService.INSUFFICIENT_EVIDENCE_ANSWER);
        verify(mockChatModel, never()).call(any(Prompt.class));
    }

    @Test
    void defaultSimilarityThresholdInRagPropertiesIs045() {
        RagProperties defaultProperties = new RagProperties();
        assertThat(defaultProperties.getSimilarityThreshold()).isEqualTo(0.45);
        assertThat(defaultProperties.getTopK()).isEqualTo(4);
    }

    @Test
    void queryUsesDefaultSimilarityThresholdWhenOmittedInRequest() {
        UUID docId = UUID.randomUUID();
        DocumentEntity indexedDoc = DocumentEntity.builder()
                .id(docId)
                .filename("doc.pdf")
                .status(DocumentEntity.DocumentStatus.INDEXED)
                .build();

        // One chunk at 0.50 (above default 0.45) and one chunk at 0.40 (below default 0.45)
        Document chunkAbove = Document.builder()
                .text("Relevant content.")
                .metadata(Map.of("document_id", docId.toString(), "filename", "doc.pdf", "chunk_index", 0))
                .score(0.50)
                .build();
        Document chunkBelow = Document.builder()
                .text("Marginal content.")
                .metadata(Map.of("document_id", docId.toString(), "filename", "doc.pdf", "chunk_index", 1))
                .score(0.40)
                .build();

        when(mockVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunkAbove, chunkBelow));
        when(mockDocumentRepository.findById(docId))
                .thenReturn(Optional.of(indexedDoc));
        when(mockChatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("Answer based on relevant content."))
        )));

        // Request with similarityThreshold = null (omitted)
        RagQueryRequest request = new RagQueryRequest("What is this document about?", null, null);
        RagQueryResponse response = ragService.query(request);

        // Verify SearchRequest passed to vectorStore uses the configured default 0.45
        ArgumentCaptor<SearchRequest> searchRequestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(mockVectorStore).similaritySearch(searchRequestCaptor.capture());
        SearchRequest capturedSearch = searchRequestCaptor.getValue();
        assertThat(capturedSearch.getSimilarityThreshold()).isEqualTo(0.45);
        assertThat(capturedSearch.getTopK()).isEqualTo(4);

        // Verify that the chunk below default 0.45 was filtered out in memory and only the chunk >= 0.45 was retained
        assertThat(response.insufficientEvidence()).isFalse();
        assertThat(response.retrievedChunkCount()).isEqualTo(1);
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).score()).isEqualTo(0.50);
    }

    @Test
    void queryPreservesExplicitSimilarityThresholdOverride() {
        UUID docId = UUID.randomUUID();
        DocumentEntity indexedDoc = DocumentEntity.builder()
                .id(docId)
                .filename("doc.pdf")
                .status(DocumentEntity.DocumentStatus.INDEXED)
                .build();

        // Chunk at 0.50 is above default 0.45, but below client override of 0.70
        Document chunk = Document.builder()
                .text("Moderate match text.")
                .metadata(Map.of("document_id", docId.toString(), "filename", "doc.pdf", "chunk_index", 0))
                .score(0.50)
                .build();

        when(mockVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunk));
        when(mockDocumentRepository.findById(docId))
                .thenReturn(Optional.of(indexedDoc));

        // Client explicitly specifies similarityThreshold = 0.70
        RagQueryRequest request = new RagQueryRequest("Require strict match", 2, 0.70);
        RagQueryResponse response = ragService.query(request);

        // Verify SearchRequest passed to vectorStore uses client override 0.70
        ArgumentCaptor<SearchRequest> searchRequestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(mockVectorStore).similaritySearch(searchRequestCaptor.capture());
        SearchRequest capturedSearch = searchRequestCaptor.getValue();
        assertThat(capturedSearch.getSimilarityThreshold()).isEqualTo(0.70);
        assertThat(capturedSearch.getTopK()).isEqualTo(2);

        // Score 0.50 < 0.70 so it is filtered out
        assertThat(response.insufficientEvidence()).isTrue();
        assertThat(response.retrievedChunkCount()).isEqualTo(0);
        assertThat(response.sources()).isEmpty();
        verify(mockChatModel, never()).call(any(Prompt.class));
    }

    @Test
    void isInsufficientEvidenceAccuratelyIdentifiesPatterns() {
        // Null / blank / default constant
        assertThat(RagService.isInsufficientEvidence(null)).isTrue();
        assertThat(RagService.isInsufficientEvidence("")).isTrue();
        assertThat(RagService.isInsufficientEvidence("   ")).isTrue();
        assertThat(RagService.isInsufficientEvidence(RagService.INSUFFICIENT_EVIDENCE_ANSWER)).isTrue();

        // Variations of insufficient evidence responses
        assertThat(RagService.isInsufficientEvidence("The provided documents do not contain sufficient evidence to answer your question.")).isTrue();
        assertThat(RagService.isInsufficientEvidence("According to the document, there is insufficient evidence to explain token weights.")).isTrue();
        assertThat(RagService.isInsufficientEvidence("The document does not mention the accuracy percentage achieved by the best AI testing tools.")).isTrue();
        assertThat(RagService.isInsufficientEvidence("No information is provided in the document regarding this topic.")).isTrue();
        assertThat(RagService.isInsufficientEvidence("The provided context does not state the pricing tier.")).isTrue();
        assertThat(RagService.isInsufficientEvidence("This question cannot be answered from the provided documents.")).isTrue();
        assertThat(RagService.isInsufficientEvidence("The text makes no mention of transformer architecture details.")).isTrue();

        // Affirmative valid answers must evaluate to false
        assertThat(RagService.isInsufficientEvidence("PostgreSQL pgvector provides vector similarity search for RAG.")).isFalse();
        assertThat(RagService.isInsufficientEvidence("The accuracy percentage is 94.5% based on benchmark tests.")).isFalse();
        assertThat(RagService.isInsufficientEvidence("The paper presents strong evidence supporting modern transformer attention mechanisms.")).isFalse();

        // Case 1: Partially supported answer that answers one part but explicitly says another requested fact is absent
        assertThat(RagService.isInsufficientEvidence(
                "PostgreSQL pgvector supports vector indexing and HNSW, but the document does not mention the maximum recommended dimensions."
        )).isTrue();
        assertThat(RagService.isInsufficientEvidence(
                "While the document covers AI testing tools, it does not specify the accuracy percentage achieved."
        )).isTrue();
        assertThat(RagService.isInsufficientEvidence(
                "The text describes the vector database; however, it does not state the supported distance metrics."
        )).isTrue();
        assertThat(RagService.isInsufficientEvidence(
                "The document does not mention the memory requirement. The answer is 64 GB."
        )).isTrue();

        // Case 2: Supported answer that mentions missing information in a historical or contrasting statement but then provides the requested fact
        assertThat(RagService.isInsufficientEvidence(
                "While earlier versions of the specification did not mention vector search, PostgreSQL pgvector currently supports HNSW and IVFFlat index types."
        )).isFalse();
        assertThat(RagService.isInsufficientEvidence(
                "Although Chapter 1 does not state the benchmark score, Section 4 confirms that the model achieved 94.5% accuracy."
        )).isFalse();
        assertThat(RagService.isInsufficientEvidence(
                "The introductory chapter does not provide performance metrics. However, Section 5 explicitly states that the throughput is 10,000 queries per second."
        )).isFalse();

        // Case 3: Confident-looking answer where model asserts facts without refusal markers
        // Demonstrates known limitation: answer-text pattern matching alone evaluates to false on confident hallucinations
        assertThat(RagService.isInsufficientEvidence(
                "According to the document, the exact maximum memory requirement for HNSW indexes is 64 gigabytes."
        )).isFalse();
    }

    @Test
    void queryHandlesPartiallySupportedAnswerWithMissingFactAsInsufficientEvidenceTrue() {
        UUID docId = UUID.randomUUID();
        DocumentEntity indexedDoc = DocumentEntity.builder()
                .id(docId)
                .filename("pgvector-spec.pdf")
                .status(DocumentEntity.DocumentStatus.INDEXED)
                .build();

        Document chunk = Document.builder()
                .text("PostgreSQL pgvector supports HNSW indexing for approximate nearest neighbor search.")
                .metadata(Map.of(
                        "document_id", docId.toString(),
                        "filename", "pgvector-spec.pdf",
                        "chunk_index", 0
                ))
                .score(0.85)
                .build();

        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));
        when(mockDocumentRepository.findById(docId)).thenReturn(Optional.of(indexedDoc));

        String answer = "PostgreSQL pgvector supports HNSW indexing, but the document does not mention the maximum recommended dimensions.";
        when(mockChatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new org.springframework.ai.chat.messages.AssistantMessage(answer))
        )));

        RagQueryResponse response = ragService.query(new RagQueryRequest("What index types and max dimensions does pgvector support?", 3, 0.6));

        // Must be marked insufficient evidence because a requested fact was absent
        assertThat(response.insufficientEvidence()).isTrue();
        assertThat(response.answer()).isEqualTo(answer);
        assertThat(response.sources()).hasSize(1);
    }

    @Test
    void queryHandlesSupportedAnswerWithContrastingPreambleAsInsufficientEvidenceFalse() {
        UUID docId = UUID.randomUUID();
        DocumentEntity indexedDoc = DocumentEntity.builder()
                .id(docId)
                .filename("pgvector-spec.pdf")
                .status(DocumentEntity.DocumentStatus.INDEXED)
                .build();

        Document chunk = Document.builder()
                .text("PostgreSQL pgvector supports HNSW and IVFFlat index types for similarity search.")
                .metadata(Map.of(
                        "document_id", docId.toString(),
                        "filename", "pgvector-spec.pdf",
                        "chunk_index", 0
                ))
                .score(0.88)
                .build();

        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));
        when(mockDocumentRepository.findById(docId)).thenReturn(Optional.of(indexedDoc));

        String answer = "While earlier drafts did not mention vector search, PostgreSQL pgvector currently supports HNSW and IVFFlat index types.";
        when(mockChatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new org.springframework.ai.chat.messages.AssistantMessage(answer))
        )));

        RagQueryResponse response = ragService.query(new RagQueryRequest("What index types does pgvector support?", 3, 0.6));

        // Must NOT be marked as insufficient evidence because the main clause answers the question
        assertThat(response.insufficientEvidence()).isFalse();
        assertThat(response.answer()).isEqualTo(answer);
        assertThat(response.sources()).hasSize(1);
    }

    @Test
    void queryDemonstratesLimitationOnConfidentHallucinationWhenContextLacksFact() {
        UUID docId = UUID.randomUUID();
        DocumentEntity indexedDoc = DocumentEntity.builder()
                .id(docId)
                .filename("pgvector-spec.pdf")
                .status(DocumentEntity.DocumentStatus.INDEXED)
                .build();

        // Context only mentions indexing, NOT memory requirements
        Document chunk = Document.builder()
                .text("PostgreSQL pgvector supports HNSW indexing.")
                .metadata(Map.of(
                        "document_id", docId.toString(),
                        "filename", "pgvector-spec.pdf",
                        "chunk_index", 0
                ))
                .score(0.75) // Good similarity score on broad topic
                .build();

        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));
        when(mockDocumentRepository.findById(docId)).thenReturn(Optional.of(indexedDoc));

        // LLM generates a confident hallucination without any refusal or hedging
        String hallucinatedAnswer = "According to the document, the exact maximum memory requirement for HNSW indexes is 64 gigabytes.";
        when(mockChatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new org.springframework.ai.chat.messages.AssistantMessage(hallucinatedAnswer))
        )));

        RagQueryResponse response = ragService.query(new RagQueryRequest("What is the exact maximum memory requirement for HNSW indexes?", 3, 0.6));

        // Documenting limitation: Without refusal markers or semantic NLI verification, answer-text matching flags this as false
        assertThat(response.insufficientEvidence()).isFalse();
        assertThat(response.answer()).isEqualTo(hallucinatedAnswer);
    }

    @Test
    void queryHandlesMixedResponseWhereModelRefusesFromDocumentThenProvidesAnswerAsInsufficientEvidenceTrue() {
        UUID docId = UUID.randomUUID();
        DocumentEntity indexedDoc = DocumentEntity.builder()
                .id(docId)
                .filename("pgvector-spec.pdf")
                .status(DocumentEntity.DocumentStatus.INDEXED)
                .build();

        Document chunk = Document.builder()
                .text("PostgreSQL pgvector provides vector indexing capabilities.")
                .metadata(Map.of(
                        "document_id", docId.toString(),
                        "filename", "pgvector-spec.pdf",
                        "chunk_index", 0
                ))
                .score(0.80)
                .build();

        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));
        when(mockDocumentRepository.findById(docId)).thenReturn(Optional.of(indexedDoc));

        String mixedAnswer = "The document does not mention the memory requirement. The answer is 64 GB.";
        when(mockChatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new org.springframework.ai.chat.messages.AssistantMessage(mixedAnswer))
        )));

        RagQueryResponse response = ragService.query(new RagQueryRequest("What is the memory requirement for pgvector?", 3, 0.6));

        // Mixed response must be flagged as insufficientEvidence=true because the requested fact was missing from the library
        assertThat(response.insufficientEvidence()).isTrue();
        assertThat(response.answer()).isEqualTo(mixedAnswer);
        assertThat(response.sources()).hasSize(1);
    }
}
