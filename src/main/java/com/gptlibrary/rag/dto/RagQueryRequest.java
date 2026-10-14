package com.gptlibrary.rag.dto;

public record RagQueryRequest(
        String question,
        Integer topK,
        Double similarityThreshold
) {
    public void validate() {
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("Question must not be blank.");
        }
        if (question.length() > 2000) {
            throw new IllegalArgumentException("Question length exceeds maximum allowed 2000 characters.");
        }
        if (topK != null && (topK < 1 || topK > 20)) {
            throw new IllegalArgumentException("topK must be between 1 and 20.");
        }
        if (similarityThreshold != null && (similarityThreshold < 0.0 || similarityThreshold > 1.0)) {
            throw new IllegalArgumentException("similarityThreshold must be between 0.0 and 1.0.");
        }
    }
}
