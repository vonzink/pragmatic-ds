package com.pragmaticds.docengine.parsing.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for {@link LayoutElementSpan}: {@code (layout_element_id, text_span_id)}. */
public class LayoutElementSpanId implements Serializable {

    private UUID layoutElementId;
    private Long textSpanId;

    public LayoutElementSpanId() {
        // JPA
    }

    public LayoutElementSpanId(UUID layoutElementId, Long textSpanId) {
        this.layoutElementId = layoutElementId;
        this.textSpanId = textSpanId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LayoutElementSpanId that)) {
            return false;
        }
        return Objects.equals(layoutElementId, that.layoutElementId)
                && Objects.equals(textSpanId, that.textSpanId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(layoutElementId, textSpanId);
    }
}
