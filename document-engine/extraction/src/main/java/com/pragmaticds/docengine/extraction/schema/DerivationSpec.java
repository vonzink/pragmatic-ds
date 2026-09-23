package com.pragmaticds.docengine.extraction.schema;

import java.util.List;

/**
 * A value computed from other fields of the same schema when the field's own rungs leave it
 * MISSING: {@code sum(plus) - sum(minus)}. Spec 2026-09-23 §4. Ungrouped MONEY fields only.
 */
public record DerivationSpec(List<String> plus, List<String> minus) {
    public DerivationSpec {
        plus = List.copyOf(plus);
        minus = List.copyOf(minus);
    }
}
