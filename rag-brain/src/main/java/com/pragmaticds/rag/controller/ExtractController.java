package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.ExtractResponse;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.analyze.DocInput;
import com.pragmaticds.rag.service.extract.ExtractionResult;
import com.pragmaticds.rag.service.extract.ExtractionService;
import com.pragmaticds.rag.service.extract.ExtractorNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Internal per-document extraction endpoint. Gated by AnalyzeApiKeyFilter (503 when
 * the analyze key is unset, 401 on a bad key) — same gate as /analyze and /chat.
 * Single multipart `doc` part; bytes held IN MEMORY ONLY for the duration of the
 * call (same discipline as {@link DocInput}) — never persisted to corpus, disk, or
 * the audit body. The suite is the only caller.
 */
@RestController
public class ExtractController {

    private final ExtractionService extractionService;
    private final BrainResolver brainResolver;

    public ExtractController(ExtractionService extractionService, BrainResolver brainResolver) {
        this.extractionService = extractionService;
        this.brainResolver = brainResolver;
    }

    @PostMapping(value = "/api/ai/{brain}/extract/{extractorSlug}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExtractResponse> extract(
            @PathVariable("brain") String brain,
            @PathVariable("extractorSlug") String extractorSlug,
            @RequestPart(value = "doc", required = false) MultipartFile doc) {

        // required = false + an explicit check (rather than a required @RequestPart) so a
        // missing part maps through GlobalExceptionHandler's ResponseStatusException handler
        // to a clean 400 JSON body, instead of MissingServletRequestPartException falling
        // through to the generic 500 handler.
        if (doc == null || doc.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "doc part is required");
        }

        UUID brainId = brainResolver.resolve(brain).getId();

        byte[] bytes;
        try {
            bytes = doc.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "unreadable upload: " + doc.getOriginalFilename());
        }
        DocInput docInput = new DocInput("doc-0", doc.getOriginalFilename(), doc.getContentType(), bytes, 0L);

        ExtractionResult r = extractionService.extract(brainId, extractorSlug, docInput);
        return ResponseEntity.ok(toResponse(r));
    }

    private ExtractResponse toResponse(ExtractionResult r) {
        return new ExtractResponse(
                r.status().name(), r.values(), r.warnings(),
                r.provider(), r.model(), r.inputTokens(), r.outputTokens(), r.reason());
    }

    @ExceptionHandler(ExtractorNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUnknownExtractor(ExtractorNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
