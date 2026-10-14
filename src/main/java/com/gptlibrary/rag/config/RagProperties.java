package com.gptlibrary.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gptlibrary.rag")
@Getter
@Setter
public class RagProperties {

    /**
     * Minimum cosine similarity threshold for vector retrieval (between 0.0 and 1.0).
     * Defaults to 0.65.
     */
    private double similarityThreshold = 0.65;

    /**
     * Default maximum number of relevant chunks to retrieve.
     * Defaults to 4.
     */
    private int topK = 4;
}
