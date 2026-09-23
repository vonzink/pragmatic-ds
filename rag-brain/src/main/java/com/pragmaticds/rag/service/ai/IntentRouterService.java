package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.domain.Surface;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic, code-driven intent router (Phase 5). Computes a heuristic
 * {@link Intent} for an EDUCATIONAL question AFTER the compliance
 * {@link QuestionCategory} gate and BEFORE retrieval. It NEVER replaces the
 * category gate and NEVER folds intent into {@link QuestionCategory}.
 *
 * <p><b>Phase 5 scope:</b> the result is computed and logged by the caller only;
 * nothing reads it yet, so the pipeline behaves identically to today. This is a
 * forward seam for Phase 6.
 *
 * <p><b>Not pack-driven on purpose:</b> the keyword sets below are code
 * constants, not an {@code intent.yaml}. They are a small, deliberately
 * conservative heuristic, NOT the final taxonomy — refine in a later phase once
 * intent is actually consumed.
 *
 * <p><b>Word-boundary cues:</b> single-word keywords match on a leading word
 * boundary so {@code rate} no longer fires inside "sepa<b>rate</b>" or
 * "ove<b>rrate</b>"; multi-word cues ("how much") match as phrases. This keeps
 * the analytics intent honest now that {@link AskService} consumes a (separate,
 * stricter) calculation check via {@link #isCalculationRequest(String)}.
 *
 * <p><b>Surface parsing divergence (intentional):</b> {@code route()} parses
 * surface as {@code Surface.valueOf(surface.strip().toUpperCase(Locale.US))}
 * (lenient, public-facing — accepts {@code "public"} or {@code "Public"}),
 * whereas the admin {@code SourceLinkService}/{@code PageGuideService} parsers
 * use {@code Surface.valueOf(value.strip())} (case-sensitive). A shared
 * {@code Surface.parse(String)} helper could unify them later; not required
 * for Phase 5.
 *
 * <p><b>Deterministic rule order:</b>
 * <ol>
 *   <li>Validate {@code surface} first (regardless of which branch wins): if
 *       non-null and non-blank, {@code Surface.valueOf(surface.strip().toUpperCase())};
 *       a bad value throws {@link IllegalArgumentException} (mapped to HTTP 400 by
 *       the global handler). Null/blank surface is accepted.</li>
 *   <li>If {@code pageRoute} is non-blank → {@link Intent#PAGE_GUIDANCE}.</li>
 *   <li>If {@code question} is null/blank → {@link Intent#GUIDELINE_QUESTION}
 *       (preserves the classifier's proceed-by-default anchor).</li>
 *   <li>If the question contains any calculation cue → {@link Intent#CALCULATION}.</li>
 *   <li>If the question contains any external-reference cue →
 *       {@link Intent#EXTERNAL_REFERENCE}.</li>
 *   <li>Otherwise → {@link Intent#GUIDELINE_QUESTION}.</li>
 * </ol>
 * Calculation is intentionally checked before external-reference, so a question
 * carrying both cues resolves to {@code CALCULATION} (acceptable for a heuristic
 * seam that nothing consumes yet).
 */
@Service
public class IntentRouterService {

    /**
     * Numeric / calculation cues for the analytics intent. Single words match on
     * a leading word boundary; "how much" is a phrase; "%" is a literal.
     */
    private static final Pattern CALCULATION_CUES = Pattern.compile(
            "\\b(?:calculate|payment|monthly|dti|ltv|amortiz|rate)|how much|%",
            Pattern.CASE_INSENSITIVE);

    /** Official / external-source cues (leading word boundary + phrases). */
    private static final Pattern EXTERNAL_REFERENCE_CUES = Pattern.compile(
            "\\b(?:official|source|link|handbook)|where can i find|guideline number",
            Pattern.CASE_INSENSITIVE);

    /**
     * Stricter predicate for the answer guard: does the question ask the
     * assistant to PRODUCE a number (a personalized/explicit computation) rather
     * than explain a concept? Deliberately narrow so definitional questions like
     * "what is DTI?" or "what is an interest rate?" still answer normally, while
     * "calculate my payment" / "what's my monthly payment" / "how much can I
     * borrow" escalate instead of risking a hallucinated figure.
     */
    private static final Pattern CALCULATION_REQUEST = Pattern.compile(
            "\\b(?:calculate|compute)\\b"
                    + "|how much"
                    + "|monthly payment"
                    + "|\\bmy\\b[^.?!]{0,40}\\b(?:payment|dti|ltv|debt[- ]to[- ]income"
                    + "|loan[- ]to[- ]value|interest rate|mortgage insurance|credit score)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Computes the heuristic intent. Validates {@code surface} up front so a bad
     * value yields a clean 400 regardless of the winning branch.
     *
     * @param question  the user's question (may be null/blank)
     * @param pageRoute optional page route the caller is on (may be null/blank)
     * @param surface   optional audience string ("PUBLIC"/"INTERNAL"/"BOTH",
     *                  case-insensitive); a bad value throws IllegalArgumentException
     * @return the computed {@link Intent}; never null
     * @throws IllegalArgumentException if {@code surface} is non-blank and not a
     *                                  valid {@link Surface} name
     */
    public Intent route(String question, String pageRoute, String surface) {
        // 1. Validate surface first (independent of which branch wins).
        if (surface != null && !surface.isBlank()) {
            Surface.valueOf(surface.strip().toUpperCase(Locale.US));
        }

        // 2. pageRoute wins.
        if (pageRoute != null && !pageRoute.isBlank()) {
            return Intent.PAGE_GUIDANCE;
        }

        // 3. Proceed-by-default anchor for empty questions.
        if (question == null || question.isBlank()) {
            return Intent.GUIDELINE_QUESTION;
        }

        String normalized = question.toLowerCase(Locale.US).strip();

        // 4. Calculation cues (checked before external-reference by design).
        if (CALCULATION_CUES.matcher(normalized).find()) {
            return Intent.CALCULATION;
        }

        // 5. External-reference cues.
        if (EXTERNAL_REFERENCE_CUES.matcher(normalized).find()) {
            return Intent.EXTERNAL_REFERENCE;
        }

        // 6. Neutral default.
        return Intent.GUIDELINE_QUESTION;
    }

    /**
     * True when the question asks the assistant to compute or report a specific
     * number, which the model must not generate from retrieved prose. Used by the
     * answer pipeline to escalate to a human instead of guessing figures.
     */
    public boolean isCalculationRequest(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return CALCULATION_REQUEST.matcher(question.toLowerCase(Locale.US).strip()).find();
    }
}
