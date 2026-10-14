package com.gptlibrary.document.service;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Set;

@Service
public class TikaParserService {

    private final Tika tika = new Tika();

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain"
    );

    public record ParsedDocument(String content, String detectedContentType, Integer pageCount) {}

    public String detectContentType(MultipartFile file) throws Exception {
        try (InputStream is = file.getInputStream()) {
            return tika.detect(is, file.getOriginalFilename());
        }
    }

    public boolean isSupportedContentType(String contentType) {
        if (contentType == null) return false;
        return SUPPORTED_TYPES.contains(contentType.toLowerCase()) || contentType.startsWith("text/plain");
    }

    public ParsedDocument parse(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty() || file.getSize() == 0) {
            throw new IllegalArgumentException("Cannot parse empty or missing file.");
        }

        String detectedType = detectContentType(file);
        if (!isSupportedContentType(detectedType)) {
            throw new IllegalArgumentException("Unsupported file type: " + detectedType + ". Only PDF, DOCX, and TXT are supported.");
        }

        Metadata metadata = new Metadata();
        if (file.getOriginalFilename() != null) {
            metadata.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, file.getOriginalFilename());
        }

        AutoDetectParser parser = new AutoDetectParser();
        // -1 disables the write limit so large files can be parsed completely
        BodyContentHandler handler = new BodyContentHandler(-1);
        ParseContext context = new ParseContext();

        try (InputStream is = file.getInputStream()) {
            parser.parse(is, handler, metadata, context);
        } catch (Exception e) {
            throw new IllegalArgumentException("Corrupted or unreadable document: " + e.getMessage(), e);
        }

        String content = handler.toString();
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Document contains no readable text content.");
        }

        Integer pageCount = null;
        String pages = metadata.get(PagedText.N_PAGES);
        if (pages == null) {
            pages = metadata.get("xmpTPg:NPages");
        }
        if (pages != null) {
            try {
                pageCount = Integer.parseInt(pages.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        return new ParsedDocument(content.trim(), detectedType, pageCount);
    }
}
