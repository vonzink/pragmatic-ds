package com.pragmaticds.rag.config;

import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.service.audit.RagTraceService;
import com.pragmaticds.rag.service.learning.SourceWeightLearningService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the two small resolver interfaces {@link SourceWeightLearningService} needs
 * to attribute feedback to documents and to recognize top-authority sources. Kept as
 * functional-interface seams (rather than direct dependencies on
 * {@link RagTraceService}/{@link BrainDocumentRepository}) so the learning job's unit
 * tests stay DB-free; this config supplies the real, DB-backed implementations for
 * the running application.
 */
@Configuration
public class LearningWiringConfig {

    @Bean
    public SourceWeightLearningService.TraceDocumentResolver traceDocumentResolver(RagTraceService ragTraceService) {
        return ragTraceService::documentIdsForTraces;
    }

    @Bean
    public SourceWeightLearningService.DocumentTrustResolver documentTrustResolver(
            BrainDocumentRepository brainDocumentRepository) {
        return documentId -> brainDocumentRepository.existsByIdAndTrustLevel(documentId, SourceTrustLevel.AUTHORITATIVE);
    }
}
