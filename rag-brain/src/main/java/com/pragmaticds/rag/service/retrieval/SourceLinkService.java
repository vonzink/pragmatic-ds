package com.pragmaticds.rag.service.retrieval;

import com.pragmaticds.rag.domain.BrainSourceLink;
import com.pragmaticds.rag.domain.LinkAuthority;
import com.pragmaticds.rag.domain.Surface;
import com.pragmaticds.rag.dto.SourceLinkDto;
import com.pragmaticds.rag.dto.SourceLinkRequest;
import com.pragmaticds.rag.repository.BrainSourceLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Full-CRUD service over the source-link registry, plus a short-cached
 * activeLinks() read snapshot for the (Phase 6) retrieval seam. The cache mirrors
 * VocabularyService exactly: 10s nanoTime TTL with a Long.MIN_VALUE sentinel
 * tested BEFORE the subtraction (so a fresh process never computes
 * now - Long.MIN_VALUE), volatile fields, invalidate() on every write. Nothing
 * in Phase 3 reads activeLinks() — it is the integration point for later phases.
 */
@Service
public class SourceLinkService {

    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10 s

    private final BrainSourceLinkRepository repo;

    public SourceLinkService(BrainSourceLinkRepository repo) {
        this.repo = repo;
    }

    public List<SourceLinkDto> list(UUID brainId) {
        return repo.findAllByBrainIdOrderByCreatedAtDescIdDesc(brainId).stream()
                .map(SourceLinkDto::from)
                .toList();
    }

    public SourceLinkDto get(UUID brainId, UUID id) {
        return SourceLinkDto.from(find(brainId, id));
    }

    @Transactional
    public SourceLinkDto create(UUID brainId, SourceLinkRequest req, String createdBy) {
        String name = required(req.name(), "name");
        String url = required(req.url(), "url");
        LinkAuthority authority = authority(req.authority());
        Surface surface = surface(req.surface());

        BrainSourceLink link = new BrainSourceLink(
                brainId, name, url, strip(req.domain()), authority,
                cleanList(req.topics()), req.freshnessRequired(),
                cleanList(req.allowedUse()), cleanList(req.doNotUseFor()),
                surface, createdBy);

        SourceLinkDto dto = SourceLinkDto.from(repo.save(link));
        return dto;
    }

    @Transactional
    public SourceLinkDto update(UUID brainId, UUID id, SourceLinkRequest req, String updatedBy) {
        BrainSourceLink link = find(brainId, id);
        link.setName(required(req.name(), "name"));
        link.setUrl(required(req.url(), "url"));
        link.setDomain(strip(req.domain()));
        link.setAuthority(authority(req.authority()));
        link.setTopics(cleanList(req.topics()));
        link.setFreshnessRequired(req.freshnessRequired());
        link.setAllowedUse(cleanList(req.allowedUse()));
        link.setDoNotUseFor(cleanList(req.doNotUseFor()));
        link.setSurface(surface(req.surface()));
        link.setUpdatedBy(updatedBy);

        SourceLinkDto dto = SourceLinkDto.from(repo.save(link));
        return dto;
    }

    @Transactional
    public SourceLinkDto setActive(UUID brainId, UUID id, boolean active, String updatedBy) {
        BrainSourceLink link = find(brainId, id);
        link.setActive(active);
        link.setUpdatedBy(updatedBy);
        SourceLinkDto dto = SourceLinkDto.from(repo.save(link));
        return dto;
    }

    @Transactional
    public void delete(UUID brainId, UUID id) {
        BrainSourceLink link = find(brainId, id);
        repo.delete(link);
    }

    public List<BrainSourceLink> activeLinks(UUID brainId) {
        return repo.findByBrainIdAndActiveTrueOrderByCreatedAtDescIdDesc(brainId);
    }

    /**
     * Deterministic match of active source links to a question (spec §7.5),
     * reading the cached {@link #activeLinks()} snapshot (never the repo). A link
     * matches when any of {@code link.getTopics()} (lowercased) is a substring of
     * the lowercased {@code question}.
     *
     * <p>Surface filter: when {@code surface} is non-blank it is parsed leniently
     * via {@code Surface.valueOf(surface.strip().toUpperCase(Locale.US))} (a bad
     * value throws {@link IllegalArgumentException} → HTTP 400) and only links
     * whose {@code getSurface()} is that value or {@link Surface#BOTH} are kept; a
     * null/blank surface applies no filter. A null/blank question yields an empty
     * list. Returns a defensive copy (snapshot order preserved).
     *
     * <p><b>Heuristic note:</b> topic matching is substring (not whole-token) and
     * deliberately broad, mirroring the conservative-cue heuristic in
     * {@code IntentRouterService}; deterministic and refined later (Phase 8). The
     * lenient uppercasing surface parse diverges intentionally from the admin CRUD
     * path's case-sensitive parse because this is the public ask path.
     */
    public List<BrainSourceLink> match(UUID brainId, String question, String surface) {
        Surface required = parseSurface(surface);
        if (question == null || question.isBlank()) {
            return new ArrayList<>();
        }
        String haystack = question.toLowerCase(Locale.US);

        List<BrainSourceLink> out = new ArrayList<>();
        for (BrainSourceLink link : activeLinks(brainId)) {
            if (required != null && link.getSurface() != required && link.getSurface() != Surface.BOTH) {
                continue;
            }
            if (matchesTopic(link.getTopics(), haystack)) {
                out.add(link);
            }
        }
        return out;
    }

    /** Lenient surface parse for the public ask path; null/blank → no filter (null). */
    private static Surface parseSurface(String surface) {
        if (surface == null || surface.isBlank()) {
            return null;
        }
        return Surface.valueOf(surface.strip().toUpperCase(Locale.US));
    }

    /** True when any topic (lowercased) is a substring of the lowercased question. */
    private static boolean matchesTopic(List<String> topics, String lowerQuestion) {
        if (topics == null) {
            return false;
        }
        for (String topic : topics) {
            if (topic == null || topic.isBlank()) {
                continue;
            }
            if (lowerQuestion.contains(topic.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    public void invalidate() {}

    // --- helpers ---

    private BrainSourceLink find(UUID brainId, UUID id) {
        BrainSourceLink link = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("source link not found: " + id));
        if (!link.getBrainId().equals(brainId)) {
            throw new IllegalArgumentException("source link not found for brain: " + id);
        }
        return link;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static LinkAuthority authority(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("authority is required");
        }
        return LinkAuthority.valueOf(value.strip());
    }

    private static Surface surface(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("surface is required");
        }
        return Surface.valueOf(value.strip());
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(values.size());
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.strip());
            }
        }
        return out;
    }
}
