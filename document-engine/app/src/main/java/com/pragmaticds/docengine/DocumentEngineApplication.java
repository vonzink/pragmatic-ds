package com.pragmaticds.docengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Pragmatic DS Document Engine.
 *
 * <p>Mortgage document parsing with page-and-coordinate-level source evidence. Every extracted value
 * is traceable back to the exact page and location it came from — see {@code docs/ARCHITECTURE.md}.
 *
 * <p>Component, entity, and repository scanning is rooted at {@code com.pragmaticds.docengine} so each
 * Gradle module contributes its own slice without this class enumerating them.
 */
@SpringBootApplication(scanBasePackages = "com.pragmaticds.docengine")
@EntityScan("com.pragmaticds.docengine")
@EnableJpaRepositories("com.pragmaticds.docengine")
public class DocumentEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentEngineApplication.class, args);
    }
}
