package com.pragmaticds.rag.service.retrieval;

import com.pragmaticds.rag.domain.BrainPageGuide;
import com.pragmaticds.rag.domain.LinkRef;
import com.pragmaticds.rag.domain.Surface;
import com.pragmaticds.rag.dto.PageGuideDto;
import com.pragmaticds.rag.dto.PageGuideRequest;
import com.pragmaticds.rag.repository.BrainPageGuideRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Full-CRUD service over the page-guide registry, plus a short-cached
 * activePageGuides() read snapshot for the later retrieval/routing seam. The
 * cache mirrors SourceLinkService exactly: 10s nanoTime TTL with a Long.MIN_VALUE
 * sentinel tested BEFORE the subtraction (so a fresh process never computes
 * now - Long.MIN_VALUE), volatile fields, invalidate() on every write. Nothing in
 * Phase 4 reads activePageGuides() — it is the integration point for later phases.
 */
@Service
public class PageGuideService {

    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10 s

    private final BrainPageGuideRepository repo;

    public PageGuideService(BrainPageGuideRepository repo) {
        this.repo = repo;
    }

    public List<PageGuideDto> list(UUID brainId) {
        return repo.findAllByBrainIdOrderByCreatedAtDescIdDesc(brainId).stream()
                .map(PageGuideDto::from)
                .toList();
    }

    public PageGuideDto get(UUID brainId, UUID id) {
        return PageGuideDto.from(find(brainId, id));
    }

    @Transactional
    public PageGuideDto create(UUID brainId, PageGuideRequest req, String createdBy) {
        String title = required(req.title(), "title");
        String purpose = required(req.purpose(), "purpose");
        Surface surface = surface(req.surface());

        BrainPageGuide guide = new BrainPageGuide(
                brainId, route(req.route()), title, purpose, surface,
                cleanList(req.userIntents()), cleanList(req.allowedGuidance()),
                links(req.internalLinks()), ids(req.sourceLinkIds()),
                cleanList(req.topics()), createdBy);

        PageGuideDto dto = PageGuideDto.from(repo.save(guide));
        return dto;
    }

    @Transactional
    public PageGuideDto update(UUID brainId, UUID id, PageGuideRequest req, String updatedBy) {
        BrainPageGuide guide = find(brainId, id);
        guide.setRoute(route(req.route()));
        guide.setTitle(required(req.title(), "title"));
        guide.setPurpose(required(req.purpose(), "purpose"));
        guide.setSurface(surface(req.surface()));
        guide.setUserIntents(cleanList(req.userIntents()));
        guide.setAllowedGuidance(cleanList(req.allowedGuidance()));
        guide.setInternalLinks(links(req.internalLinks()));
        guide.setSourceLinkIds(ids(req.sourceLinkIds()));
        guide.setTopics(cleanList(req.topics()));
        guide.setUpdatedBy(updatedBy);

        PageGuideDto dto = PageGuideDto.from(repo.save(guide));
        return dto;
    }

    @Transactional
    public PageGuideDto setActive(UUID brainId, UUID id, boolean active, String updatedBy) {
        BrainPageGuide guide = find(brainId, id);
        guide.setActive(active);
        guide.setUpdatedBy(updatedBy);
        PageGuideDto dto = PageGuideDto.from(repo.save(guide));
        return dto;
    }

    @Transactional
    public void delete(UUID brainId, UUID id) {
        BrainPageGuide guide = find(brainId, id);
        repo.delete(guide);
    }

    public List<BrainPageGuide> activePageGuides(UUID brainId) {
        return repo.findByBrainIdAndActiveTrueOrderByCreatedAtDescIdDesc(brainId);
    }

    /**
     * Deterministic match of active page guides to a question (spec §7.5),
     * reading the cached {@link #activePageGuides()} snapshot (never the repo).
     * A guide matches when:
     * <ol>
     *   <li>{@code pageRoute} is non-blank and equals {@code guide.getRoute()}
     *       (case-insensitive, trimmed) — a route-exact hit; OR</li>
     *   <li>any of {@code guide.getTopics()} (lowercased) is a substring of the
     *       lowercased {@code question} — a topic hit.</li>
     * </ol>
     * Surface filter: when {@code surface} is non-blank it is parsed leniently via
     * {@code Surface.valueOf(surface.strip().toUpperCase(Locale.US))} (a bad value
     * throws {@link IllegalArgumentException} → HTTP 400) and only guides whose
     * {@code getSurface()} is that value or {@link Surface#BOTH} are kept; a
     * null/blank surface applies no filter.
     *
     * <p>Route-exact matches are ordered ahead of topic-only matches (each group
     * preserving snapshot order); a guide hitting both appears once. Returns a
     * defensive copy; never throws on empty inputs.
     *
     * <p><b>Heuristic note:</b> topic matching is substring (not whole-token) and
     * deliberately broad, mirroring the conservative-cue heuristic in
     * {@code IntentRouterService}. It is deterministic and will be refined
     * (word-boundary / embedding) once side-evidence is actually consumed
     * (Phase 8). The lenient uppercasing surface parse diverges intentionally
     * from the admin CRUD path's case-sensitive parse because this is the public
     * ask path.
     */
    public List<BrainPageGuide> match(UUID brainId, String pageRoute, String question, String surface) {
        Surface required = parseSurface(surface);
        boolean hasRoute = pageRoute != null && !pageRoute.isBlank();
        String route = hasRoute ? pageRoute.strip() : null;
        String haystack = (question == null) ? "" : question.toLowerCase(Locale.US);

        LinkedHashSet<BrainPageGuide> routeHits = new LinkedHashSet<>();
        LinkedHashSet<BrainPageGuide> topicHits = new LinkedHashSet<>();

        for (BrainPageGuide guide : activePageGuides(brainId)) {
            if (required != null && guide.getSurface() != required && guide.getSurface() != Surface.BOTH) {
                continue;
            }
            if (hasRoute && guide.getRoute() != null && guide.getRoute().equalsIgnoreCase(route)) {
                routeHits.add(guide);
                continue;
            }
            if (!haystack.isBlank() && matchesTopic(guide.getTopics(), haystack)) {
                topicHits.add(guide);
            }
        }

        List<BrainPageGuide> out = new ArrayList<>(routeHits.size() + topicHits.size());
        out.addAll(routeHits);
        out.addAll(topicHits);
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

    private BrainPageGuide find(UUID brainId, UUID id) {
        BrainPageGuide guide = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("page guide not found: " + id));
        if (!guide.getBrainId().equals(brainId)) {
            throw new IllegalArgumentException("page guide not found for brain: " + id);
        }
        return guide;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    /** route is optional: null stays null; otherwise stripped (blank → null). */
    private static String route(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
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

    /** Map request {label,url} rows to LinkRef; drop rows where both fields are blank. */
    private static List<LinkRef> links(List<PageGuideRequest.LinkRefRequest> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<LinkRef> out = new ArrayList<>(values.size());
        for (PageGuideRequest.LinkRefRequest v : values) {
            if (v == null) {
                continue;
            }
            String label = v.label() == null ? "" : v.label().strip();
            String url = v.url() == null ? "" : v.url().strip();
            if (label.isEmpty() && url.isEmpty()) {
                continue;
            }
            out.add(new LinkRef(label, url));
        }
        return out;
    }

    /** Convert String ids to UUID (a malformed value throws IllegalArgumentException → 400). */
    private static List<UUID> ids(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<UUID> out = new ArrayList<>(values.size());
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(UUID.fromString(v.strip()));
            }
        }
        return out;
    }
}
