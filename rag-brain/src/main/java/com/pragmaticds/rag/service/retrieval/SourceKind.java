package com.pragmaticds.rag.service.retrieval;

/**
 * The retrieval indexes a {@link RetrievalPlan} can select. {@link SourceKind#CORPUS}
 * is always present; side indexes are collected when the plan asks for them and
 * corpus evidence is strong enough to answer.
 *
 * <p>{@code PAGE_GUIDE} and {@code LINK_REGISTRY} drive collection from the
 * already-merged cached snapshots ({@code PageGuideService.activePageGuides()} /
 * {@code SourceLinkService.activeLinks()}).
 */
public enum SourceKind {

    /** The hybrid vector + keyword corpus index — always retrieved (unchanged). */
    CORPUS,

    /** The curated page-guide registry (where to send the user). */
    PAGE_GUIDE,

    /** The curated source-link / trust registry (external references). */
    LINK_REGISTRY
}
