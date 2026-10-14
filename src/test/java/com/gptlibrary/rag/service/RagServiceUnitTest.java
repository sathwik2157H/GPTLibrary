package com.gptlibrary.rag.service;

import com.gptlibrary.document.model.DocumentEntity;
import com.gptlibrary.document.repository.DocumentRepository;
import com.gptlibrary.rag.config.RagProperties;
import com.gptlibrary.rag.dto.RagQueryRequest;
import com.gptlibrary.rag.dto.RagQueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
        ragProperties.setSimilarityThreshold(0.65);
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
}
