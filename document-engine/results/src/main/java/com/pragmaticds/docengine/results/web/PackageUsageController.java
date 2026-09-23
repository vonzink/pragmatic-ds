package com.pragmaticds.docengine.results.web;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * What one package cost to parse: pages and their OCR share, elapsed, retries, and a model-spend
 * block that today reports a measured zero.
 *
 * <p>The assembly (and the org/tombstone guard) lives in {@link PackageUsageReader}, following
 * {@code DocumentFieldsController}: a controller that owned the assembly would make a second
 * surface a second query path, and two query paths over one contract eventually disagree.
 *
 * <p>Authorization: this path falls under the broad {@code GET /v1/**} rule in
 * {@code SecurityConfig} — READONLY and above. That is right for it: the payload is counts,
 * durations and version strings, with no field value and therefore no PII.
 */
@RestController
public class PackageUsageController {

    private final PackageUsageReader reader;

    public PackageUsageController(PackageUsageReader reader) {
        this.reader = reader;
    }

    @GetMapping("/v1/packages/{id}/usage")
    public PackageUsageView get(@PathVariable UUID id) {
        return reader.usageOf(id, reader.require(id));
    }
}
