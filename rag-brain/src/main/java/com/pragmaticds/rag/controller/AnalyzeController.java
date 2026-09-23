package com.pragmaticds.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.dto.AnalyzeResponse;
import com.pragmaticds.rag.dto.CitationDto;
import com.pragmaticds.rag.dto.RefineRequest;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.analyze.AnalysisContext;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.analyze.AnalysisService;
import com.pragmaticds.rag.service.analyze.AnalyzerNotFoundException;
import com.pragmaticds.rag.service.analyze.DocInput;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal folder-brains analyze endpoint. Gated by AnalyzeApiKeyFilter (503 when
 * the analyze key is unset, 401 on a bad key). Multipart: `context` JSON part +
 * repeated `docs` file parts. The suite is the only caller.
 */
@RestController
public class AnalyzeController {

    private final AnalysisService analysisService;
    private final BrainResolver brainResolver;
    private final ObjectMapper objectMapper;

    public AnalyzeController(AnalysisService analysisService, BrainResolver brainResolver,
                             ObjectMapper objectMapper) {
        this.analysisService = analysisService;
        this.brainResolver = brainResolver;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/api/ai/{brain}/analyze/{analyzerSlug}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalyzeResponse> analyze(
            @PathVariable("brain") String brain,
            @PathVariable("analyzerSlug") String analyzerSlug,
            @RequestPart("context") String contextJson,
            @RequestPart(value = "docs", required = false) List<MultipartFile> files) {

        UUID brainId = brainResolver.resolve(brain).getId();

        AnalysisContext context;
        try {
            context = objectMapper.readValue(contextJson, AnalysisContext.class);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid context JSON: " + e.getMessage());
        }

        // Pair each uploaded file with its context DocMeta (by ordinal, then by fileName)
        // to recover the suite doc id and createdAt ordering. Context docs are the
        // authoritative id source; createdAt uses list order (index) as the recency key.
        List<DocInput> docs = pairDocs(files, context);

        AnalysisResult r = analysisService.analyze(brainId, analyzerSlug, docs, context);
        return ResponseEntity.ok(toResponse(r));
    }

    @PostMapping(value = "/api/ai/{brain}/analyze/{analyzerSlug}/refine",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalyzeResponse> refine(
            @PathVariable("brain") String brain,
            @PathVariable("analyzerSlug") String analyzerSlug,
            @RequestBody RefineRequest request) {

        UUID brainId = brainResolver.resolve(brain).getId();
        AnalysisResult r = analysisService.refine(brainId, analyzerSlug, request);
        return ResponseEntity.ok(toResponse(r));
    }

    private List<DocInput> pairDocs(List<MultipartFile> files, AnalysisContext context) {
        List<DocInput> docs = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return docs;
        }
        Map<String, AnalysisContext.DocMeta> byName = new HashMap<>();
        List<AnalysisContext.DocMeta> metas =
                context.docs() == null ? List.of() : context.docs();
        for (AnalysisContext.DocMeta m : metas) {
            if (m.fileName() != null) {
                byName.put(m.fileName(), m);
            }
        }
        for (int i = 0; i < files.size(); i++) {
            MultipartFile f = files.get(i);
            AnalysisContext.DocMeta meta = f.getOriginalFilename() == null
                    ? null : byName.get(f.getOriginalFilename());
            if (meta == null && i < metas.size()) {
                meta = metas.get(i);   // ordinal fallback
            }
            String id = meta != null ? meta.id() : "doc-" + i;
            String contentType = meta != null && meta.contentType() != null
                    ? meta.contentType() : f.getContentType();
            byte[] bytes;
            try {
                bytes = f.getBytes();
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unreadable upload: " + id);
            }
            // Recency key = context list order (earlier = older). Ascending index.
            long createdAt = meta != null ? metas.indexOf(meta) : i;
            docs.add(new DocInput(id, f.getOriginalFilename(), contentType, bytes, createdAt));
        }
        return docs;
    }

    private AnalyzeResponse toResponse(AnalysisResult r) {
        List<AnalyzeResponse.AnalyzeCitation> citations = new ArrayList<>();
        for (CitationDto c : r.citations()) {
            citations.add(new AnalyzeResponse.AnalyzeCitation(
                    c.documentName(), c.documentName(), c.section(), null));
        }
        List<AnalyzeResponse.Skipped> skipped = r.skippedDocs().stream()
                .map(s -> new AnalyzeResponse.Skipped(
                        s.id(), s.fileName(), s.category() == null ? null : s.category().name(), s.reason()))
                .toList();
        List<AnalyzeResponse.Filtered> filtered = r.filtered().stream()
                .map(f -> new AnalyzeResponse.Filtered(
                        f.id(), f.fileName(), f.pagesTotal(), f.pagesKept(), f.matchedForms()))
                .toList();
        com.fasterxml.jackson.databind.JsonNode findings;
        try {
            findings = objectMapper.readTree(r.findingsJson() == null ? "{}" : r.findingsJson());
        } catch (IOException e) {
            findings = objectMapper.createObjectNode();
        }
        return new AnalyzeResponse(
                r.status().name(), r.reportMarkdown(), findings, citations,
                r.provider(), r.model(), r.inputTokens(), r.outputTokens(),
                r.costUsd(), r.pageCount(), skipped, r.reason(), filtered, r.runId());
    }

    @ExceptionHandler(AnalyzerNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUnknownAnalyzer(AnalyzerNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
