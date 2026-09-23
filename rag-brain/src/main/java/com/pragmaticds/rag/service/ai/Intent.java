package com.pragmaticds.rag.service.ai;

/**
 * Heuristic routing intent for an EDUCATIONAL question, computed AFTER the
 * compliance {@link QuestionCategory} gate and BEFORE retrieval. This is a
 * separate concept from {@link QuestionCategory}: the category enum is the
 * compliance guardrail (it decides whether we answer at all); Intent is a
 * downstream hint about HOW to retrieve/answer, consumed by later phases.
 *
 * <p>Phase 5 only computes and logs the intent — nothing reads it yet, so the
 * pipeline behaves identically to today. {@code GUIDELINE_QUESTION} is the
 * neutral default returned for ordinary guideline questions and for any
 * null/blank input, preserving the classifier's proceed-by-default anchor.
 *
 * <p><b>Spec §7.3 reconciliation:</b> the spec lists five intent names:
 * {@code question_answering}, {@code page_guidance}, {@code external_reference},
 * {@code calculator}, and {@code handoff}. The Java enum uses deliberate
 * renames: {@code GUIDELINE_QUESTION} (was {@code question_answering}) and
 * {@code CALCULATION} (was {@code calculator}) are idiomatic UPPER_SNAKE Java
 * names. {@code handoff} is intentionally deferred — there is no consumer for
 * it until a later phase — so no {@code HANDOFF} constant is added here.
 */
public enum Intent {

    /** Ordinary guideline/education question — the neutral default. */
    GUIDELINE_QUESTION,

    /** The caller is on a specific page and wants page-scoped guidance. */
    PAGE_GUIDANCE,

    /** A numeric/calculation question (payment, DTI, LTV, amortization, rate). */
    CALCULATION,

    /** A request for an official/external source, link, or handbook reference. */
    EXTERNAL_REFERENCE
}
