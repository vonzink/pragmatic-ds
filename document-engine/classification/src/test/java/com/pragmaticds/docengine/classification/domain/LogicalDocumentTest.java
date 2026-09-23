package com.pragmaticds.docengine.classification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogicalDocumentTest {
    @Test
    void human_regroup_sets_type_nulls_confidence_and_marks_in_review() {
        LogicalDocument d = new LogicalDocument(UUID.randomUUID(), 0, "UNKNOWN", new BigDecimal("0.55"), LogicalDocument.BOUNDARY_TYPE_CHANGE);
        d.humanRegroup("PAYSTUB");
        assertThat(d.getDocumentTypeCode()).isEqualTo("PAYSTUB");
        // A human decided; a machine confidence would be a fiction.
        assertThat(d.getClassificationConfidence()).isNull();
        assertThat(d.getReviewStatus()).isEqualTo("IN_REVIEW");
    }
}
