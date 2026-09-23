package com.pragmaticds.docengine.config;

import com.pragmaticds.docengine.ingestion.ProcessingStarter;
import com.pragmaticds.docengine.orchestration.JobService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ingestion→orchestration seam, wired at the app layer.
 *
 * <p>Ingestion depends only on {@code platform} and publishes the {@link ProcessingStarter} port;
 * orchestration owns jobs and knows nothing about uploads. This adapter is the single point where
 * the two meet — the same cross-module pattern host-app uses, kept in {@code app} so neither
 * module gains a dependency on the other.
 */
@Configuration
public class ProcessingSeamConfig {

    @Bean
    public ProcessingStarter processingStarter(JobService jobService) {
        return new ProcessingStarter() {
            @Override
            public UUID startJob(UUID packageId, String idempotencyKey) {
                return jobService.createJob(packageId, idempotencyKey).getId();
            }

            @Override
            public Optional<ExistingJob> findExisting(String idempotencyKey) {
                return jobService
                        .findExisting(idempotencyKey)
                        .map(job -> new ExistingJob(job.getId(), job.getPackageId()));
            }
        };
    }
}
