package com.pragmaticds.docengine.gold.web;

import com.pragmaticds.docengine.gold.GoldDocumentView;
import com.pragmaticds.docengine.gold.GoldExportService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/documents/{id}/gold} — ADMIN only (SecurityConfig, RAW-content list). A reviewed
 * document's human decisions, unmasked, in the extraction harness's truth shape: the gold-set
 * exporter ({@code tools/gold.py export}) is its only intended caller. 404 for an unknown or
 * another org's document; 409 {@code DOCUMENT_NOT_REVIEWED} until MARK_REVIEWED.
 */
@RestController
public class GoldExportController {

    private final GoldExportService gold;

    public GoldExportController(GoldExportService gold) {
        this.gold = gold;
    }

    @GetMapping("/v1/documents/{id}/gold")
    public GoldDocumentView export(@PathVariable UUID id) {
        return gold.export(id);
    }
}
