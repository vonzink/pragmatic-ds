package com.pragmaticds.rag.seed;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainSourceLink;
import com.pragmaticds.rag.domain.LinkAuthority;
import com.pragmaticds.rag.domain.Surface;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.BrainSourceLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(100)
public class SourceLinkSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SourceLinkSeeder.class);
    private static final String SEEDED_BY = "pack-seed";

    private final BrainRepository brains;
    private final BrainSourceLinkRepository repository;
    private final DomainPackRegistry packRegistry;

    public SourceLinkSeeder(BrainRepository brains, BrainSourceLinkRepository repository,
                            DomainPackRegistry packRegistry) {
        this.brains = brains;
        this.repository = repository;
        this.packRegistry = packRegistry;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (Brain brain : brains.findAll()) {
            if (!brain.isActive() || repository.countByBrainId(brain.getId()) > 0) {
                continue;
            }
            DomainPack pack = packRegistry.bundle(brain.getId()).pack();
            if (pack.sourceLinks().isEmpty()) {
                continue;
            }
            for (DomainPack.SourceLink seed : pack.sourceLinks()) {
                repository.save(new BrainSourceLink(
                        brain.getId(),
                        seed.name(),
                        seed.url(),
                        seed.domain(),
                        LinkAuthority.valueOf(seed.authority()),
                        seed.topics(),
                        seed.freshnessRequired(),
                        seed.allowedUse(),
                        seed.doNotUseFor(),
                        Surface.valueOf(seed.surface()),
                        SEEDED_BY));
            }
            log.info("Seeded {} source link(s) for brain '{}'", pack.sourceLinks().size(), brain.getSlug());
        }
    }
}
