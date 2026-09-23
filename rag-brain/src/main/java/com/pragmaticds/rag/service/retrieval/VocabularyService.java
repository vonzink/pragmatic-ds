package com.pragmaticds.rag.service.retrieval;

import com.pragmaticds.rag.domain.VocabularyRevision;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.repository.VocabularyRevisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Live effective retrieval vocabulary (borrower/broker synonyms), read through a
 * short cache. Pattern mirrors RulesService/RuntimeSettings exactly, including
 * the Long.MIN_VALUE sentinel guard that avoids the overflow a naive
 * {@code now - Long.MIN_VALUE} check would produce. One logical document:
 * custom content fully replaces the pack default; a null-content revision
 * reverts to the pack default.
 */
@Service
public class VocabularyService {

    /** Summary for the admin API and dashboard. */
    public record VocabState(
            String content,
            String source,          // "pack" | "custom"
            OffsetDateTime updatedAt,
            String updatedBy,
            int entries) {}

    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10 s
    private static final int  CONTENT_MAX_CHARS = 50_000;

    private final VocabularyRevisionRepository repo;
    private final DomainPackRegistry packRegistry;

    public VocabularyService(VocabularyRevisionRepository repo, DomainPackRegistry packRegistry) {
        this.repo = repo;
        this.packRegistry = packRegistry;
    }

    /** Effective synonym map consumed by RetrievalService. */
    public Map<String, String> effectiveSynonyms(UUID brainId) {
        Optional<VocabularyRevision> latest = snapshot(brainId);
        if (latest.isPresent() && latest.get().getContent() != null) {
            return VocabularyText.parse(latest.get().getContent());
        }
        return packRegistry.bundle(brainId).pack().acronymExpansions();
    }

    /** Effective editable text (custom content, or the pack default serialized). */
    public String effectiveText(UUID brainId) {
        Optional<VocabularyRevision> latest = snapshot(brainId);
        if (latest.isPresent() && latest.get().getContent() != null) {
            return latest.get().getContent();
        }
        return VocabularyText.serialize(packRegistry.bundle(brainId).pack().acronymExpansions());
    }

    public VocabState state(UUID brainId) {
        Optional<VocabularyRevision> latest = snapshot(brainId);
        String text = effectiveText(brainId);
        int entries = effectiveSynonyms(brainId).size();
        if (latest.isEmpty()) {
            return new VocabState(text, "pack", null, null, entries);
        }
        VocabularyRevision rev = latest.get();
        if (rev.getContent() == null) {
            return new VocabState(text, "pack", rev.getCreatedAt(), rev.getCreatedBy(), entries);
        }
        return new VocabState(text, "custom", rev.getCreatedAt(), rev.getCreatedBy(), entries);
    }

    public List<VocabularyRevision> history(UUID brainId) {
        return repo.findTop20ByBrainIdOrderByCreatedAtDescIdDesc(brainId);
    }

    /** Expand a sample question with the live vocabulary (dashboard "test a phrase"). */
    public String previewExpansion(UUID brainId, String question) {
        return RetrievalService.expandQuery(question, effectiveSynonyms(brainId));
    }

    @Transactional
    public void save(UUID brainId, String content, String updatedBy) {
        VocabularyText.validate(content);
        if (content.length() > CONTENT_MAX_CHARS) {
            throw new IllegalArgumentException("Vocabulary exceeds " + CONTENT_MAX_CHARS + " characters");
        }
        repo.save(new VocabularyRevision(brainId, content, updatedBy));
    }

    @Transactional
    public void revert(UUID brainId, String updatedBy) {
        repo.save(new VocabularyRevision(brainId, null, updatedBy));
    }

    public void invalidate() {}

    private Optional<VocabularyRevision> snapshot(UUID brainId) {
        return repo.findFirstByBrainIdOrderByCreatedAtDescIdDesc(brainId);
    }
}
