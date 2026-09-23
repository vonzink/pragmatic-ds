package com.pragmaticds.docengine.extraction.web;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The per-document field projection with the evidence chain behind every value.
 *
 * <p>The assembly (and the org/tombstone guard) lives in {@link DocumentFieldsReader}, because the
 * same read model now serves two representations: this JSON endpoint and the Markdown one. A
 * controller that owned the assembly would have made the Markdown surface a second query path, and
 * two query paths over one contract eventually disagree.
 */
@RestController
public class DocumentFieldsController {

    private final DocumentFieldsReader reader;

    public DocumentFieldsController(DocumentFieldsReader reader) {
        this.reader = reader;
    }

    @GetMapping("/v1/documents/{id}/fields")
    public DocumentFieldsView get(@PathVariable UUID id) {
        return reader.fieldsOf(reader.require(id));
    }
}
