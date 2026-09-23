package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.RuleRevision;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.ai.PromptBuilderService;
import com.pragmaticds.rag.service.ai.RulesService;
import com.pragmaticds.rag.service.ai.RulesService.RuleState;
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
 * Admin endpoints for viewing and editing the two owner-editable rule blocks
 * (hard rules and guidance). Protected by AdminApiKeyFilter. Path variables use
 * the full dotted key form, e.g. {@code /api/ai/admin/rules/rules.hard}.
 *
 * <p>Route order: Spring resolves the literal {@code /preview} segment before
 * the {@code /{key}} wildcard pattern, so all five mappings are safe to coexist.
 */
@RestController
@RequestMapping("/api/ai/admin/rules")
public class AdminRulesController {

    /** Request body for PUT (content-only). */
    public record ContentBody(String content) {}

    private static final String UPDATED_BY = "admin-api";

    private final RulesService rulesService;
    private final PromptBuilderService promptBuilder;
    private final BrainResolver brainResolver;

    public AdminRulesController(RulesService rulesService, PromptBuilderService promptBuilder,
                                BrainResolver brainResolver) {
        this.rulesService  = rulesService;
        this.promptBuilder = promptBuilder;
        this.brainResolver = brainResolver;
    }

    // ── GET /api/ai/admin/rules ──────────────────────────────────────────────

    /**
     * Returns {@code {"hard": RuleState, "guidance": RuleState}} — keys are the
     * short form (strip the {@code "rules."} prefix) for dashboard convenience.
     */
    @GetMapping
    public Map<String, RuleState> getState(@RequestParam(value = "brain", required = false) String brain) {
        UUID brainId = brainResolver.resolve(brain).getId();
        return shortKeyMap(rulesService.state(brainId));
    }

    // ── PUT /api/ai/admin/rules/{key} ────────────────────────────────────────

    /**
     * Saves new content for the given key (full dotted form, e.g. {@code rules.hard}).
     * Pre-validates key membership and content non-blankness; length cap is enforced
     * downstream in {@link RulesService#save}.
     *
     * @param key  full rule key: {@code rules.hard} or {@code rules.guidance}
     * @param body {@code {"content": "..."}}
     * @return fresh state map (same shape as GET)
     */
    @PutMapping("/{key}")
    public Map<String, RuleState> putRule(@PathVariable String key,
                                          @RequestParam(value = "brain", required = false) String brain,
                                          @RequestBody ContentBody body) {
        requireKnownKey(key);
        if (body == null || body.content() == null || body.content().isBlank()) {
            throw new IllegalArgumentException("content must be non-blank for key: " + key);
        }
        UUID brainId = brainResolver.resolve(brain).getId();
        rulesService.save(brainId, key, body.content(), UPDATED_BY);
        return shortKeyMap(rulesService.state(brainId));
    }

    // ── POST /api/ai/admin/rules/{key}/revert ────────────────────────────────

    /**
     * Reverts the given key to its pack default by appending a null-content marker.
     *
     * @param key full rule key: {@code rules.hard} or {@code rules.guidance}
     * @return fresh state map (same shape as GET)
     */
    @PostMapping("/{key}/revert")
    public Map<String, RuleState> revertRule(@PathVariable String key,
                                             @RequestParam(value = "brain", required = false) String brain) {
        UUID brainId = brainResolver.resolve(brain).getId();
        rulesService.revert(brainId, key, UPDATED_BY);
        return shortKeyMap(rulesService.state(brainId));
    }

    // ── GET /api/ai/admin/rules/{key}/history ────────────────────────────────

    /**
     * Returns newest-first history for the key. Revision numbers run from
     * {@code list.size()} (newest) down to {@code 1} (oldest in the 20-row window).
     * The {@code reverted} flag is {@code true} when {@code content} is null (pack
     * revert marker).
     *
     * <p>Note: Spring matches the literal {@code /preview} segment before this
     * {@code /{key}/history} pattern, so there is no ambiguity.
     */
    @GetMapping("/{key}/history")
    public List<Map<String, Object>> history(@PathVariable String key,
                                             @RequestParam(value = "brain", required = false) String brain) {
        UUID brainId = brainResolver.resolve(brain).getId();
        List<RuleRevision> revisions = rulesService.history(brainId, key);
        int total = revisions.size();
        List<Map<String, Object>> result = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            RuleRevision rev = revisions.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("revision",  total - i);          // newest = total, oldest = 1
            entry.put("createdAt", rev.getCreatedAt());
            entry.put("createdBy", rev.getCreatedBy());
            entry.put("reverted",  rev.getContent() == null);
            entry.put("content",   rev.getContent());
            result.add(entry);
        }
        return result;
    }

    // ── GET /api/ai/admin/rules/preview ─────────────────────────────────────

    /**
     * Returns the fully-assembled prompt that would be sent to the LLM for a
     * representative question, with an empty chunk list. Useful for verifying
     * live rule edits before committing them.
     *
     * <p>Spring resolves this literal mapping before the {@code /{key}} wildcard.
     */
    @GetMapping("/preview")
    public Map<String, String> preview(@RequestParam(value = "brain", required = false) String brain) {
        UUID brainId = brainResolver.resolve(brain).getId();
        String prompt = promptBuilder.build(
                "<your question here>", List.of(), brainId);
        return Map.of("prompt", prompt);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Maps {@code "rules.hard"} → {@code "hard"} and {@code "rules.guidance"} → {@code "guidance"}.
     */
    private Map<String, RuleState> shortKeyMap(Map<String, RuleState> full) {
        Map<String, RuleState> out = new LinkedHashMap<>();
        out.put("hard",     full.get("rules.hard"));
        out.put("guidance", full.get("rules.guidance"));
        return out;
    }

    private void requireKnownKey(String key) {
        if (!RulesService.KEYS.contains(key)) {
            throw new IllegalArgumentException(
                    "Unknown rule key: " + key + ". Must be one of " + RulesService.KEYS);
        }
    }
}
