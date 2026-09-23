package com.pragmaticds.rag.pack;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.repository.BrainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Boot-time visibility for stranded brains: logs an error for any active brain
 * whose {@code packRef} directory is missing. {@link DomainPackRegistry} loads a
 * brain's pack lazily on first request and 500s if the directory is gone (e.g. a
 * dashboard-generated pack lost to a redeploy before generated packs were made
 * persistent), so without this an operator only finds out from user-facing errors.
 * Runs after {@link com.pragmaticds.rag.config.DefaultBrainSeeder} has reconciled the
 * default brain's packRef. Read-only and never fails boot.
 */
@Component
@Order(1)
public class PackAvailabilityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PackAvailabilityCheck.class);

    private final BrainRepository brains;

    public PackAvailabilityCheck(BrainRepository brains) {
        this.brains = brains;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Brain brain : brains.findAll()) {
            if (!brain.isActive()) {
                continue;
            }
            String packRef = brain.getPackRef();
            if (packRef == null || packRef.isBlank()) {
                log.warn("Brain '{}' ({}) has no packRef configured", brain.getSlug(), brain.getId());
                continue;
            }
            Path dir = Path.of(packRef).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                log.error("Brain '{}' ({}) references a missing pack directory '{}' — requests to this "
                                + "brain will fail until the pack is restored (restore the generated-packs "
                                + "volume or recreate the brain).",
                        brain.getSlug(), brain.getId(), dir);
            }
        }
    }
}
