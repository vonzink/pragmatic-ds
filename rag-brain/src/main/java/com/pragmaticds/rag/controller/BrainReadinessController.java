package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainMode;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.DocumentChunkRepository;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.service.profile.BrainProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Connect/installer readiness for a brain: a single call the dashboard wizard
 * uses to render a "is this brain ready to attach to a site?" checklist. It
 * composes brain status, corpus counts, and the public profile so the UI does
 * not have to stitch three calls together.
 */
@RestController
@RequestMapping("/api/ai/admin/brains/{brainId}/readiness")
public class BrainReadinessController {

    public record ChecklistItem(String key, String label, boolean ok, String hint) {}

    public record ReadinessDto(
            UUID brainId, String slug, String displayName,
            boolean active, boolean isDefault,
            long chunks, long activeDocuments,
            boolean publicEnabled, String mode, boolean hasPublicToken,
            List<String> allowedDomains,
            boolean ready, List<ChecklistItem> checklist) {}

    private final BrainRepository brains;
    private final BrainProfileService profiles;
    private final BrainDocumentRepository documents;
    private final DocumentChunkRepository chunks;

    public BrainReadinessController(BrainRepository brains, BrainProfileService profiles,
                                    BrainDocumentRepository documents, DocumentChunkRepository chunks) {
        this.brains = brains;
        this.profiles = profiles;
        this.documents = documents;
        this.chunks = chunks;
    }

    @GetMapping
    public ReadinessDto readiness(@PathVariable UUID brainId) {
        Brain brain = brains.findById(brainId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + brainId));
        BrainProfile profile = profiles.getOrCreate(brainId);

        long chunkCount = chunks.countByBrainId(brainId);
        long activeDocs = documents.countByBrainIdAndActiveTrue(brainId);
        boolean hasToken = profile.getPublicTokenHash() != null && !profile.getPublicTokenHash().isBlank();
        boolean publicMode = profile.getMode() == BrainMode.PUBLIC_SITE;
        List<String> domains = profile.getAllowedDomains() == null ? List.of() : profile.getAllowedDomains();

        ChecklistItem knowledge = new ChecklistItem("knowledge",
                "Knowledge synced", chunkCount > 0,
                chunkCount > 0 ? null : "Point the brain at a folder or bucket and run Sync.");
        ChecklistItem active = new ChecklistItem("active",
                "Brain is active", brain.isActive(),
                brain.isActive() ? null : "This brain is disabled; re-enable it on the Brains screen.");
        ChecklistItem publicEnabled = new ChecklistItem("public",
                "Public access enabled", profile.isPublicEnabled() && publicMode,
                profile.isPublicEnabled() && publicMode ? null
                        : "Enable public access (mode PUBLIC_SITE) in the Publish step.");
        ChecklistItem token = new ChecklistItem("token",
                "Public token issued", hasToken,
                hasToken ? null : "Generate a public token in the Publish step.");
        ChecklistItem allowed = new ChecklistItem("domains",
                "Site domain allowlisted", !domains.isEmpty(),
                domains.isEmpty() ? "Add the website domain(s) that may embed this assistant." : null);

        List<ChecklistItem> checklist = List.of(knowledge, active, publicEnabled, token, allowed);
        boolean ready = checklist.stream().allMatch(ChecklistItem::ok);

        return new ReadinessDto(
                brain.getId(), brain.getSlug(), brain.getDisplayName(),
                brain.isActive(), brain.isDefault(),
                chunkCount, activeDocs,
                profile.isPublicEnabled(), profile.getMode().name(), hasToken,
                domains, ready, checklist);
    }
}
