package com.gptlibrary.rag.dto;

import java.util.List;

public record RagQueryResponse(
        String question,
        String answer,
        List<SourceExcerpt> sources,
        int retrievedChunkCount,
        boolean insufficientEvidence
) {}
