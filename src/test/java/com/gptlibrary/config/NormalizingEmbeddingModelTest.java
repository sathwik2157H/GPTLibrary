package com.gptlibrary.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NormalizingEmbeddingModelTest {

    private EmbeddingModel mockDelegate;
    private NormalizingEmbeddingModel normalizingModel;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(EmbeddingModel.class);
        normalizingModel = new NormalizingEmbeddingModel(mockDelegate);
    }

    @Test
    void callNormalizesEmbeddingOutputToUnitLength() {
        // Raw vector of magnitude 5 (3^2 + 4^2 = 25)
        float[] raw = new float[]{3.0f, 4.0f};
        EmbeddingResponse rawResponse = new EmbeddingResponse(List.of(new Embedding(raw, 0)));
        when(mockDelegate.call(any(EmbeddingRequest.class))).thenReturn(rawResponse);

        EmbeddingResponse response = normalizingModel.call(new EmbeddingRequest(List.of("test content"), null));

        assertThat(response).isNotNull();
        assertThat(response.getResults()).hasSize(1);
        float[] output = response.getResults().get(0).getOutput();
        assertThat(output[0]).isEqualTo(0.6f, within(1e-5f));
        assertThat(output[1]).isEqualTo(0.8f, within(1e-5f));

        double norm = Math.sqrt((double) output[0] * output[0] + (double) output[1] * output[1]);
        assertThat(norm).isEqualTo(1.0, within(1e-5));
        verify(mockDelegate).call(any(EmbeddingRequest.class));
    }

    @Test
    void embedQueryStringNormalizesToUnitLength() {
        // Simulating Google's raw unnormalized 768-dim output (norm ~0.588)
        float[] raw = new float[768];
        for (int i = 0; i < 768; i++) {
            raw[i] = 0.02f;
        }
        when(mockDelegate.embed("what is RAG?")).thenReturn(raw);

        float[] queryEmbedding = normalizingModel.embed("what is RAG?");

        assertThat(queryEmbedding).hasSize(768);
        double sumSq = 0.0;
        for (float val : queryEmbedding) {
            sumSq += (double) val * val;
        }
        assertThat(Math.sqrt(sumSq)).isEqualTo(1.0, within(1e-5));
        verify(mockDelegate).embed("what is RAG?");
    }

    @Test
    void embedDocumentNormalizesToUnitLength() {
        Document doc = new Document("Spring AI document content");
        float[] raw = new float[]{6.0f, 8.0f}; // magnitude 10
        when(mockDelegate.embed(doc)).thenReturn(raw);

        float[] docEmbedding = normalizingModel.embed(doc);

        assertThat(docEmbedding).hasSize(2);
        assertThat(docEmbedding[0]).isEqualTo(0.6f, within(1e-5f));
        assertThat(docEmbedding[1]).isEqualTo(0.8f, within(1e-5f));
        verify(mockDelegate).embed(doc);
    }

    @Test
    void dimensionsDelegatesDirectly() {
        when(mockDelegate.dimensions()).thenReturn(768);
        assertThat(normalizingModel.dimensions()).isEqualTo(768);
        verify(mockDelegate).dimensions();
    }
}
