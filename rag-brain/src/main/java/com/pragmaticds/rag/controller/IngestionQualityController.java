package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.IngestionQualityDto;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.ingestion.IngestionQualityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/admin/ingestion-quality")
public class IngestionQualityController {

    private final BrainResolver brainResolver;
    private final IngestionQualityService qualityService;

    public IngestionQualityController(BrainResolver brainResolver,
                                      IngestionQualityService qualityService) {
        this.brainResolver = brainResolver;
        this.qualityService = qualityService;
    }

    @GetMapping
    public IngestionQualityDto quality(@RequestParam(value = "brain", required = false) String brain) {
        return qualityService.evaluate(brainResolver.resolve(brain).getId());
    }
}
