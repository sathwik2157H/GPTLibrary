package com.gptlibrary.rag.dto;

import java.util.List;

/**
 * Structured response for RAG query endpoints.
 *
 * @param question the user's query
 * @param answer the generated answer or refusal message
 * @param sources source excerpts contributing to the context
 * @param retrievedChunkCount count of valid retrieved chunks above similarity threshold
 * @param insufficientEvidence heuristic indicator of whether the indexed documents lacked sufficient evidence;
 *                             not a guarantee that every unflagged claim is fully grounded against confident hallucinations.
 */
public record RagQueryResponse(
        String question,
        String answer,
        List<SourceExcerpt> sources,
        int retrievedChunkCount,
        boolean insufficientEvidence
) {}
