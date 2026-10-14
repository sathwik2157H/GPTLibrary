package com.gptlibrary.rag.dto;

import java.util.UUID;

public record SourceExcerpt(
        UUID documentId,
        String filename,
        Integer chunkIndex,
        Double score,
        String excerpt
) {}
