package com.pragmaticds.rag.seed;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainPageGuide;
import com.pragmaticds.rag.domain.LinkRef;
import com.pragmaticds.rag.domain.Surface;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.repository.BrainPageGuideRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Order(101)
public class PageGuideSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PageGuideSeeder.class);
    private static final String SEEDED_BY = "pack-seed";

    private final BrainRepository brains;
    private final BrainPageGuideRepository repository;
    private final DomainPackRegistry packRegistry;

    public PageGuideSeeder(BrainRepository brains, BrainPageGuideRepository repository,
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
            if (pack.pageGuides().isEmpty()) {
                continue;
            }
            for (DomainPack.PageGuide seed : pack.pageGuides()) {
                repository.save(new BrainPageGuide(
                        brain.getId(),
                        seed.route(),
                        seed.title(),
                        seed.purpose(),
                        Surface.valueOf(seed.surface()),
                        seed.userIntents(),
                        seed.allowedGuidance(),
                        seed.internalLinks().stream()
                                .map(l -> new LinkRef(l.label(), l.url()))
                                .toList(),
                        List.of(),
                        seed.topics(),
                        SEEDED_BY));
            }
            log.info("Seeded {} page guide(s) for brain '{}'", pack.pageGuides().size(), brain.getSlug());
        }
    }
}
