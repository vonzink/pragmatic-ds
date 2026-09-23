package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.repository.DocumentChunkRepository;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.service.BrainResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Brain identity + corpus counts for the dashboard shell and corpus screen. */
@RestController
@RequestMapping("/api/ai/admin/stats")
public class AdminStatsController {

    public record BrainDto(UUID id, String companyName, String slug) {}
    public record CorpusDto(long activeDocuments, long totalDocuments, long chunks) {}
    public record StatsDto(BrainDto brain, CorpusDto corpus) {}

    private final DomainPackRegistry packRegistry;
    private final BrainResolver brainResolver;
    private final BrainDocumentRepository documents;
    private final DocumentChunkRepository chunks;

    public AdminStatsController(DomainPackRegistry packRegistry,
                                BrainResolver brainResolver,
                                BrainDocumentRepository documents,
                                DocumentChunkRepository chunks) {
        this.packRegistry = packRegistry;
        this.brainResolver = brainResolver;
        this.documents = documents;
        this.chunks = chunks;
    }

    @GetMapping
    public StatsDto stats(@RequestParam(value = "brain", required = false) String brain) {
        var resolved = brainResolver.resolve(brain);
        var pack = packRegistry.bundle(resolved.getId()).pack();
        UUID brainId = resolved.getId();
        return new StatsDto(
                new BrainDto(brainId, pack.companyName(), pack.slug()),
                new CorpusDto(documents.countByBrainIdAndActiveTrue(brainId),
                              documents.countByBrainId(brainId),
                              chunks.countByBrainId(brainId)));
    }
}
