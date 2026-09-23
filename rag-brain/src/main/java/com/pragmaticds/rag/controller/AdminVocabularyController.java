package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.VocabularyRevision;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.retrieval.VocabularyService;
import com.pragmaticds.rag.service.retrieval.VocabularyService.VocabState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for the editable retrieval vocabulary (borrower/broker
 * synonyms). Protected by AdminApiKeyFilter (the /api/ai/admin prefix is gated).
 * Sibling to AdminRulesController; this layer affects retrieval, not the prompt.
 */
@RestController
@RequestMapping("/api/ai/admin/vocabulary")
public class AdminVocabularyController {

    public record ContentBody(String content) {}

    private static final String UPDATED_BY = "admin-api";

    private final VocabularyService vocabulary;
    private final BrainResolver brainResolver;

    public AdminVocabularyController(VocabularyService vocabulary, BrainResolver brainResolver) {
        this.vocabulary = vocabulary;
        this.brainResolver = brainResolver;
    }

    @GetMapping
    public VocabState getState(@RequestParam(value = "brain", required = false) String brain) {
        return vocabulary.state(brainResolver.resolve(brain).getId());
    }

    @PutMapping
    public VocabState put(@RequestBody ContentBody body,
                          @RequestParam(value = "brain", required = false) String brain) {
        if (body == null || body.content() == null || body.content().isBlank()) {
            throw new IllegalArgumentException("content must be non-blank");
        }
        var brainId = brainResolver.resolve(brain).getId();
        vocabulary.save(brainId, body.content(), UPDATED_BY);
        return vocabulary.state(brainId);
    }

    @PostMapping("/revert")
    public VocabState revert(@RequestParam(value = "brain", required = false) String brain) {
        var brainId = brainResolver.resolve(brain).getId();
        vocabulary.revert(brainId, UPDATED_BY);
        return vocabulary.state(brainId);
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(value = "brain", required = false) String brain) {
        List<VocabularyRevision> revisions = vocabulary.history(brainResolver.resolve(brain).getId());
        int total = revisions.size();
        List<Map<String, Object>> result = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            VocabularyRevision rev = revisions.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("revision", total - i);
            entry.put("createdAt", rev.getCreatedAt());
            entry.put("createdBy", rev.getCreatedBy());
            entry.put("reverted", rev.getContent() == null);
            entry.put("content", rev.getContent());
            result.add(entry);
        }
        return result;
    }

    @GetMapping("/preview")
    public Map<String, String> preview(@RequestParam("q") String q,
                                       @RequestParam(value = "brain", required = false) String brain) {
        return Map.of("original", q, "expanded",
                vocabulary.previewExpansion(brainResolver.resolve(brain).getId(), q));
    }
}
