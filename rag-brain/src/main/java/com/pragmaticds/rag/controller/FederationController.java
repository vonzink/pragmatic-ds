package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.dto.FederationDtos;
import com.pragmaticds.rag.dto.PublicAskResponse;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.connect.ConnectorAuthService;
import com.pragmaticds.rag.service.connect.ConnectorQueryService;
import com.pragmaticds.rag.service.connect.ConnectorScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/connect/v1")
public class FederationController {

    private final BrainRepository brains;
    private final ConnectorAuthService auth;
    private final ConnectorQueryService query;
    private final BrainReadinessController readiness;

    public FederationController(BrainRepository brains, ConnectorAuthService auth,
                                ConnectorQueryService query, BrainReadinessController readiness) {
        this.brains = brains;
        this.auth = auth;
        this.query = query;
        this.readiness = readiness;
    }

    @GetMapping("/brains")
    public List<FederationDtos.BrainSummary> brains(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @RequestHeader(value = "Host", required = false) String host,
                                                    @RequestHeader(value = "Origin", required = false) String origin) {
        auth.require(authorization, ConnectorScope.BRAINS_LIST, null, host, origin, "BRAINS_LIST");
        return brains.findAll().stream()
                .filter(Brain::isActive)
                .map(FederationController::summary)
                .toList();
    }

    @GetMapping("/brains/{slug}")
    public FederationDtos.BrainSummary brain(@PathVariable String slug,
                                             @RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestHeader(value = "Host", required = false) String host,
                                             @RequestHeader(value = "Origin", required = false) String origin) {
        Brain brain = requireBrain(slug);
        auth.require(authorization, ConnectorScope.BRAIN_READ, brain.getId(), host, origin, "BRAIN_READ");
        return summary(brain);
    }

    @GetMapping("/brains/{slug}/readiness")
    public BrainReadinessController.ReadinessDto readiness(@PathVariable String slug,
                                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @RequestHeader(value = "Host", required = false) String host,
                                                           @RequestHeader(value = "Origin", required = false) String origin) {
        Brain brain = requireBrain(slug);
        auth.require(authorization, ConnectorScope.READINESS_READ, brain.getId(), host, origin, "READINESS");
        return readiness.readiness(brain.getId());
    }

    @PostMapping("/brains/{slug}/ask")
    public PublicAskResponse ask(@PathVariable String slug,
                                 @RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestHeader(value = "Host", required = false) String host,
                                 @RequestHeader(value = "Origin", required = false) String origin,
                                 @RequestBody FederationDtos.FederationAskRequest request) {
        Brain brain = requireBrain(slug);
        auth.require(authorization, ConnectorScope.ASK_PUBLIC, brain.getId(), host, origin, "ASK");
        return query.ask(brain, request);
    }

    @PostMapping("/brains/{slug}/retrieve")
    public FederationDtos.FederationRetrieveResponse retrieve(@PathVariable String slug,
                                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                                              @RequestHeader(value = "Host", required = false) String host,
                                                              @RequestHeader(value = "Origin", required = false) String origin,
                                                              @RequestBody FederationDtos.FederationRetrieveRequest request) {
        Brain brain = requireBrain(slug);
        auth.require(authorization, ConnectorScope.RETRIEVE_PUBLIC, brain.getId(), host, origin, "RETRIEVE");
        return query.retrieve(brain, request);
    }

    private Brain requireBrain(String slug) {
        return brains.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + slug));
    }

    private static FederationDtos.BrainSummary summary(Brain brain) {
        return new FederationDtos.BrainSummary(
                brain.getId(), brain.getSlug(), brain.getDisplayName(), brain.isActive());
    }
}
