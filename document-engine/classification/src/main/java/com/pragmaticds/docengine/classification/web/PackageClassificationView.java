package com.pragmaticds.docengine.classification.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /v1/packages/{id}/classification}: one entry per page holding a CURRENT
 * classification_result row, with the stored evidence jsonb verbatim.
 *
 * <p>Evidence carries matched anchors as ids/weights/span ids/boxes/offsets plus per-pack scores
 * — never matched text, never page text (Phase 4 rule 3). That construction is why no field here
 * is a {@code MaskableValue}: there is no value-bearing field to mask. (The masking ArchUnit rule,
 * {@code ResponseDtoMaskingArchTest}, guards value-bearing View fields in
 * {@code com.pragmaticds.docengine.extraction.web} and {@code com.pragmaticds.docengine.review}; this view has
 * nothing in its jurisdiction and must stay that way — add a text-bearing field here and it
 * belongs behind the serializer boundary instead.)
 */
public record PackageClassificationView(
        UUID packageId, List<PageClassificationEvidenceView> pages) {

    /** One classified page: the current classification_result row, evidence verbatim. */
    public record PageClassificationEvidenceView(
            UUID pageId,
            int packagePageIndex,
            String documentTypeCode,
            BigDecimal confidence,
            String rulePackVersion,
            JsonNode evidence) {}
}
