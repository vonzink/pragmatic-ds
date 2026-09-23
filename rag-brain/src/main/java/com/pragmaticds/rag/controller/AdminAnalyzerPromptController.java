package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.AnalyzerPromptRevision;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.analyze.AnalysisPromptAssembler;
import com.pragmaticds.rag.service.analyze.AnalysisService;
import com.pragmaticds.rag.service.analyze.AnalyzerNotFoundException;
import com.pragmaticds.rag.service.analyze.AnalyzerPromptService;
import com.pragmaticds.rag.service.analyze.AnalyzerPromptService.PromptState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import java.util.UUID;

/**
 * Admin endpoints for viewing and editing each folder analyzer's base prompt. Protected by
 * AdminApiKeyFilter like every {@code /api/ai/admin/**} route. Same lifecycle as
 * {@link AdminRulesController} plus a draft: nothing reaches a run until it is published.
 */
@RestController
@RequestMapping("/api/ai/admin/analyzers")
public class AdminAnalyzerPromptController {

    public record ContentBody(String content) {}

    public record SectionView(String id, String title, String text, String kind) {}

    public record AssemblyView(String source, List<SectionView> sections) {}

    private static final String UPDATED_BY = "admin-api";

    private final AnalyzerPromptService prompts;
    private final AnalysisService analysis;
    private final BrainResolver brainResolver;

    public AdminAnalyzerPromptController(AnalyzerPromptService prompts, AnalysisService analysis,
                                         BrainResolver brainResolver) {
        this.prompts = prompts;
        this.analysis = analysis;
        this.brainResolver = brainResolver;
    }

    @GetMapping
    public List<PromptState> list(@RequestParam(value = "brain", required = false) String brain) {
        return prompts.states(brainId(brain));
    }

    @GetMapping("/{slug}")
    public PromptState one(@PathVariable String slug,
                           @RequestParam(value = "brain", required = false) String brain) {
        return prompts.state(brainId(brain), slug);
    }

    @PutMapping("/{slug}/prompt/draft")
    public PromptState saveDraft(@PathVariable String slug,
                                 @RequestParam(value = "brain", required = false) String brain,
                                 @RequestBody ContentBody body) {
        if (body == null || body.content() == null || body.content().isBlank()) {
            throw new IllegalArgumentException("content must be non-blank");
        }
        return prompts.saveDraft(brainId(brain), slug, body.content(), UPDATED_BY);
    }

    @DeleteMapping("/{slug}/prompt/draft")
    public PromptState discardDraft(@PathVariable String slug,
                                    @RequestParam(value = "brain", required = false) String brain) {
        return prompts.discardDraft(brainId(brain), slug);
    }

    @PostMapping("/{slug}/prompt/publish")
    public PromptState publish(@PathVariable String slug,
                               @RequestParam(value = "brain", required = false) String brain) {
        return prompts.publish(brainId(brain), slug, UPDATED_BY);
    }

    @PostMapping("/{slug}/prompt/revert")
    public PromptState revert(@PathVariable String slug,
                              @RequestParam(value = "brain", required = false) String brain) {
        return prompts.revert(brainId(brain), slug, UPDATED_BY);
    }

    /** Newest first; revision numbers count down from list size, as the Rules history does. */
    @GetMapping("/{slug}/prompt/history")
    public List<Map<String, Object>> history(@PathVariable String slug,
                                             @RequestParam(value = "brain", required = false) String brain) {
        List<AnalyzerPromptRevision> revisions = prompts.history(brainId(brain), slug);
        int total = revisions.size();
        List<Map<String, Object>> result = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            AnalyzerPromptRevision rev = revisions.get(i);
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

    /**
     * The assembled prompt skeleton. {@code source=draft} previews the draft when one exists and
     * otherwise falls back to the published text, so the page never shows an empty first section.
     */
    @GetMapping("/{slug}/assembly")
    public AssemblyView assembly(@PathVariable String slug,
                                 @RequestParam(value = "brain", required = false) String brain,
                                 @RequestParam(value = "source", defaultValue = "published") String source) {
        UUID id = brainId(brain);
        PromptState state = prompts.state(id, slug);
        boolean useDraft = "draft".equals(source) && state.draft() != null;
        String basePrompt = useDraft ? state.draft().content() : state.published().content();
        List<SectionView> views = analysis.assembly(id, slug, basePrompt).stream()
                .map(s -> new SectionView(s.id(), s.title(), s.text(), s.kind().name()))
                .toList();
        return new AssemblyView(useDraft ? "draft" : "published", views);
    }

    @ExceptionHandler(AnalyzerPromptService.NoDraftException.class)
    public ResponseEntity<Map<String, String>> handleNoDraft(AnalyzerPromptService.NoDraftException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "NO_DRAFT"));
    }

    @ExceptionHandler(AnalyzerNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUnknownAnalyzer(AnalyzerNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    private UUID brainId(String brain) {
        return brainResolver.resolve(brain).getId();
    }
}
