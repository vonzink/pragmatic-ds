package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.connect.ConnectorScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ConnectManifestController {

    private final BrainRepository brains;

    public ConnectManifestController(BrainRepository brains) {
        this.brains = brains;
    }

    @GetMapping("/.well-known/rag-brain.json")
    public ManifestDto manifest() {
        List<ManifestBrainDto> activeBrains = brains.findAll().stream()
                .filter(Brain::isActive)
                .map(brain -> new ManifestBrainDto(
                        brain.getSlug(), brain.getDisplayName(), brain.isActive()))
                .toList();
        return new ManifestDto(
                "rag-brain-connect",
                "1.0",
                "rag-brain",
                activeBrains,
                Map.of("federation", "/api/connect/v1", "mcp", "/mcp/tools"),
                ConnectorScope.MVP_SCOPES);
    }

    public record ManifestDto(
            String protocol,
            String version,
            String name,
            List<ManifestBrainDto> brains,
            Map<String, String> endpoints,
            List<String> capabilities
    ) {}

    public record ManifestBrainDto(String slug, String displayName, boolean active) {}
}
