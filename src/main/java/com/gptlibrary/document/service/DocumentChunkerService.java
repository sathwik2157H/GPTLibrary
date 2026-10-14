package com.gptlibrary.document.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service responsible for splitting extracted document text into overlapping chunks.
 *
 * <p><b>Token Count Approximation Disclosure:</b>
 * This chunker currently uses a word-based heuristic rather than a byte-pair encoding (BPE)
 * or SentencePiece native model tokenizer (e.g., JTokkit or Gemini's countTokens API).
 * Based on standard English prose characteristics:
 * <ul>
 *   <li>1 word is approximated as ~1.33 tokens (or conversely, 1 token ≈ 0.75 words / ~4 characters).</li>
 *   <li>With {@code targetTokenSize = 512}, the target word window is {@code (int)(512 * 0.75) = 384} words.</li>
 *   <li>With {@code overlapTokens = 50}, the sliding overlap is {@code (int)(50 * 0.75) = 38} words.</li>
 *   <li>Reported chunk token count is approximated as {@code (int) Math.ceil(wordCount / 0.75)}.</li>
 * </ul>
 * For multilingual text, code snippets, or highly technical symbols, actual token density may
 * deviate from this ratio. A native BPE tokenizer integration can replace this in later phases.
 */
@Service
public class DocumentChunkerService {

    public record DocumentChunk(String content, int chunkIndex, int tokenCount) {}

    /**
     * Splits text into approximately targetTokenSize tokens with overlapTokens sliding overlap.
     *
     * @param text the full plain text to chunk
     * @param targetTokenSize the desired target size in tokens (e.g. 512)
     * @param overlapTokens the sliding window overlap in tokens (e.g. 50)
     * @return an ordered list of chunks with chunkIndex and approximate tokenCount
     */
    public List<DocumentChunk> chunk(String text, int targetTokenSize, int overlapTokens) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // Split into whitespace-separated tokens/words
        String[] words = text.trim().split("\\s+");
        if (words.length == 0) {
            return Collections.emptyList();
        }

        // Heuristic conversion: 1 token ~ 0.75 words
        int targetWords = Math.max(10, (int) (targetTokenSize * 0.75));
        int overlapWords = Math.max(1, (int) (overlapTokens * 0.75));
        int step = Math.max(1, targetWords - overlapWords);

        List<DocumentChunk> chunks = new ArrayList<>();
        int chunkIdx = 0;

        for (int i = 0; i < words.length; i += step) {
            int end = Math.min(words.length, i + targetWords);
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < end; j++) {
                if (j > i) sb.append(" ");
                sb.append(words[j]);
            }
            String chunkText = sb.toString().trim();
            if (!chunkText.isEmpty()) {
                int approxTokens = (int) Math.ceil((end - i) / 0.75);
                chunks.add(new DocumentChunk(chunkText, chunkIdx++, approxTokens));
            }
            if (end == words.length) {
                break;
            }
        }

        return chunks;
    }
}
