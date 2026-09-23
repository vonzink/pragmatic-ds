package com.pragmaticds.rag.domain;

/**
 * Category of a source document. Used for metadata filtering during retrieval
 * and to label citations in answers.
 */
public enum SourceType {
    AGENCY_GUIDELINE,   // authoritative external standards, manuals, or policies
    INTERNAL_POLICY,    // internal policy docs
    INVESTOR_OVERLAY,   // investor-specific overlays
    EDUCATIONAL         // educational/reference content
}
