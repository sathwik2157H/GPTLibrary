package com.gptlibrary.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration that automatically wraps any {@link EmbeddingModel} bean
 * with {@link NormalizingEmbeddingModel} via a Spring {@link BeanPostProcessor}.
 *
 * This guarantees that both document indexing and query vector generation
 * use the same unit-normalized embeddings without duplicate API calls or manual wrapping.
 */
@Configuration
public class NormalizingEmbeddingModelConfig {

    @Bean
    public static BeanPostProcessor normalizingEmbeddingModelBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof EmbeddingModel embeddingModel && !(bean instanceof NormalizingEmbeddingModel)) {
                    return new NormalizingEmbeddingModel(embeddingModel);
                }
                return bean;
            }
        };
    }
}
