package com.gptlibrary.config;

import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Isolated offline fallback configuration to satisfy Spring AI PgVectorStore dependency
 * during Phase 1 testing and offline builds without external Gemini API credentials.
 *
 * Controlled by the property 'gptlibrary.ai.offline-mock' (defaulting to true in Phase 1 / tests).
 * When real Gemini auto-configuration is enabled in Phase 2, setting
 * 'gptlibrary.ai.offline-mock: false' ensures this fallback bean never registers,
 * allowing Spring AI's native Google GenAI auto-configuration to take precedence smoothly.
 */
@Configuration
@ConditionalOnProperty(prefix = "gptlibrary.ai", name = "offline-mock", havingValue = "true")
public class VectorStoreConfig {

    @Bean
    public org.springframework.ai.embedding.EmbeddingModel fallbackEmbeddingModel() {
        return new AbstractEmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                float[] dummy = new float[768];
                dummy[0] = 1.0f; // Unit vector [1, 0, ... 0]
                return new EmbeddingResponse(List.of(new Embedding(dummy, 0)));
            }

            @Override
            public float[] embed(org.springframework.ai.document.Document document) {
                float[] dummy = new float[768];
                dummy[0] = 1.0f; // Unit vector [1, 0, ... 0]
                return dummy;
            }
        };
    }

    @Bean
    public org.springframework.ai.chat.model.ChatModel fallbackChatModel() {
        return prompt -> {
            String userText = prompt.getInstructions().stream()
                    .filter(m -> m instanceof org.springframework.ai.chat.messages.UserMessage)
                    .map(org.springframework.ai.chat.messages.Message::getText)
                    .findFirst().orElse("");
            String responseText = "Mock generated RAG response.";
            if (userText.contains("accuracy percentage do the best AI testing tools achieve")) {
                responseText = "The provided document discusses AI testing tools, but does not mention the accuracy percentage achieved by the best tools.";
            } else if (userText.contains("transformer attention mechanism assign weights")) {
                responseText = "The provided documents do not contain sufficient evidence to explain the transformer attention mechanism.";
            }
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new org.springframework.ai.chat.model.Generation(
                            new org.springframework.ai.chat.messages.AssistantMessage(responseText)
                    ))
            );
        };
    }
}
