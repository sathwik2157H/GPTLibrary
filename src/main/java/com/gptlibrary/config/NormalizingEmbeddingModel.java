package com.gptlibrary.config;

import com.gptlibrary.common.VectorNormalizationUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decorator around any Spring AI {@link EmbeddingModel} that ensures all returned vectors
 * (whether for document ingestion or similarity search queries) are uniformly L2-normalized
 * to unit length (L2 norm = 1.0).
 *
 * Rejects non-finite, empty, or zero-magnitude vectors safely via {@link VectorNormalizationUtils}.
 * Does not make duplicate API calls to the underlying provider.
 */
public class NormalizingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;

    public NormalizingEmbeddingModel(EmbeddingModel delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate EmbeddingModel must not be null");
    }

    public EmbeddingModel getDelegate() {
        return delegate;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        EmbeddingResponse response = delegate.call(request);
        if (response == null || response.getResults() == null) {
            return response;
        }

        List<Embedding> normalizedList = new ArrayList<>(response.getResults().size());
        for (Embedding emb : response.getResults()) {
            float[] normalized = VectorNormalizationUtils.l2Normalize(emb.getOutput());
            normalizedList.add(new Embedding(normalized, emb.getIndex(), emb.getMetadata()));
        }
        return new EmbeddingResponse(normalizedList, response.getMetadata());
    }

    @Override
    public float[] embed(Document document) {
        float[] raw = delegate.embed(document);
        return VectorNormalizationUtils.l2Normalize(raw);
    }

    @Override
    public float[] embed(String text) {
        float[] raw = delegate.embed(text);
        return VectorNormalizationUtils.l2Normalize(raw);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> rawList = delegate.embed(texts);
        if (rawList == null) {
            return null;
        }
        List<float[]> normalizedList = new ArrayList<>(rawList.size());
        for (float[] raw : rawList) {
            normalizedList.add(VectorNormalizationUtils.l2Normalize(raw));
        }
        return normalizedList;
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy strategy) {
        List<float[]> rawList = delegate.embed(documents, options, strategy);
        if (rawList == null) {
            return null;
        }
        List<float[]> normalizedList = new ArrayList<>(rawList.size());
        for (float[] raw : rawList) {
            normalizedList.add(VectorNormalizationUtils.l2Normalize(raw));
        }
        return normalizedList;
    }

    @Override
    public EmbeddingResponse embedForResponse(List<String> texts) {
        EmbeddingResponse response = delegate.embedForResponse(texts);
        if (response == null || response.getResults() == null) {
            return response;
        }

        List<Embedding> normalizedList = new ArrayList<>(response.getResults().size());
        for (Embedding emb : response.getResults()) {
            float[] normalized = VectorNormalizationUtils.l2Normalize(emb.getOutput());
            normalizedList.add(new Embedding(normalized, emb.getIndex(), emb.getMetadata()));
        }
        return new EmbeddingResponse(normalizedList, response.getMetadata());
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
