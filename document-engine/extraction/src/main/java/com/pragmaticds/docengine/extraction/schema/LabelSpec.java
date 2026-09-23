package com.pragmaticds.docengine.extraction.schema;

import com.pragmaticds.docengine.classification.rules.AnchorKind;

/** A label anchor: a literal (case-insensitive containment) or an authored regex. */
public record LabelSpec(AnchorKind kind, String pattern) {}
