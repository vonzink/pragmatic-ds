package com.pragmaticds.rag.lab.engine;

import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Declarative Income-instance compatibility policy over a parsed engine envelope.
 *
 * <p>Pure data + pure function: the decision depends only on envelope/canonicalization
 * versions and logical document types/field states. Fingerprints (envelope SHA, source-set
 * SHA, content digests) and provenance never participate — identity is not compatibility.
 *
 * <p>The predicate itself now lives in {@link ParsedDataCompatibilityService}; this class builds
 * the Income policy and translates the generalized answer back into the vocabulary the prototype
 * already publishes. Income is a contract with {@code reviewRequired = WARN}, {@code missingFields
 * = PRESERVE}, one minimum supported document, and no required document type, so the generalized
 * evaluator reproduces it exactly rather than approximating it.
 */
public final class IncomeEnvelopeCompatibility {

    /**
     * Logical document types the Income prototype can analyze — every INCOME type the engine's
     * contract §5 serves.
     *
     * <p>Kept in lockstep with {@code engine-contract/income-document-types.json} by {@link
     * com.pragmaticds.rag.lab.engine.EngineIncomeTypeCoverageTest}. This list is not a preference: a
     * type missing here is not rejected, it is <em>ignored</em> ({@code
     * UNSUPPORTED_DOCUMENT_IGNORED} is a warning, and warnings never block analysis), so omitting an
     * income type silently drops that income from the borrower's total rather than failing loudly.
     * It stood at four types from 2026-08-17 while the engine grew to twenty-two, which is how
     * Schedule C self-employment income went missing without a single error.
     *
     * <p>The analysis path is generic over the type code — {@code ParsedDocumentPromptRenderer}
     * emits {@code type=<documentTypeCode>} and renders fields uniformly — so accepting a type costs
     * nothing beyond letting its fields reach the prompt.
     */
    public static final Set<String> SUPPORTED_DOCUMENT_TYPES =
            Set.of(
                    "FORM_1099_G",
                    "FORM_1099_MISC",
                    "FORM_1099_NEC",
                    "FORM_1099_R",
                    "FORM_8962",
                    "FORM_SSA_1099",
                    "PAYSTUB",
                    "SCHEDULE_1",
                    "SCHEDULE_2",
                    "SCHEDULE_B",
                    "SCHEDULE_C",
                    "SCHEDULE_D",
                    "SCHEDULE_E",
                    "SCHEDULE_F",
                    "SCHEDULE_K1_1041",
                    "SCHEDULE_K1_1065",
                    "SCHEDULE_K1_1120S",
                    "SSA_AWARD_LETTER",
                    "STATE_TAX_RETURN",
                    "TAX_RETURN",
                    "VOE",
                    "W2");

    /** Envelope versions this policy accepts. */
    public static final Set<String> SUPPORTED_ENVELOPE_VERSIONS = Set.of("1.0.0");

    /** Canonicalization versions this policy accepts. */
    public static final Set<String> SUPPORTED_CANONICALIZATION_VERSIONS =
            Set.of("DOCENGINE-C14N-1");

    /** Field validation statuses preserved as review warnings rather than rejections. */
    public static final Set<String> REVIEW_WARNING_VALIDATION_STATUSES =
            Set.of("MANUAL_REVIEW_REQUIRED");

    /** Why an envelope cannot feed Income analysis. */
    public enum RejectionCode {
        ENVELOPE_VERSION_UNSUPPORTED,
        CANONICALIZATION_VERSION_UNSUPPORTED,
        NO_SUPPORTED_DOCUMENT
    }

    /** Value-free warning vocabulary; warnings never block analysis. */
    public enum WarningCode {
        FIELD_REVIEW_REQUIRED,
        FIELD_MISSING,
        UNSUPPORTED_DOCUMENT_IGNORED
    }

    /**
     * One value-free warning: schema/coordinate identifiers only, never extracted values.
     * {@code fieldName}/{@code groupKey} are null for document-level warnings.
     */
    public record Warning(
            WarningCode code,
            int documentOrdinal,
            String documentTypeCode,
            String fieldName,
            String groupKey) {}

    /** The full decision; {@code rejection} is null exactly when {@code compatible}. */
    public record Decision(boolean compatible, RejectionCode rejection, List<Warning> warnings) {

        public Decision {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * The policy's four vocabularies as data, so a stored release can supply its own.
     *
     * <p>{@link #DEFAULT} holds the static defaults this build ships. A Lab run does <em>not</em>
     * use them: it builds a {@code Policy} from the manifest it resolved, so editing the constants
     * above after a release exists cannot change what that release accepts. Keeping the predicate
     * itself in one place — rather than reimplementing it beside the manifest — is why the policy
     * is a parameter instead of a fork.
     */
    public record Policy(
            Set<String> envelopeVersions,
            Set<String> canonicalizationVersions,
            Set<String> documentTypes,
            Set<String> reviewWarningValidationStatuses) {

        public Policy {
            envelopeVersions = Set.copyOf(envelopeVersions);
            canonicalizationVersions = Set.copyOf(canonicalizationVersions);
            documentTypes = Set.copyOf(documentTypes);
            reviewWarningValidationStatuses = Set.copyOf(reviewWarningValidationStatuses);
        }

        /** Builds a policy from a release manifest's declared lists. */
        public static Policy of(List<String> envelopeVersions,
                                List<String> canonicalizationVersions,
                                List<String> documentTypes,
                                List<String> reviewWarningValidationStatuses) {
            return new Policy(Set.copyOf(envelopeVersions), Set.copyOf(canonicalizationVersions),
                    Set.copyOf(documentTypes), Set.copyOf(reviewWarningValidationStatuses));
        }
    }

    /** The static defaults this build ships; a release snapshots them, runs read the snapshot. */
    public static final Policy DEFAULT = new Policy(
            SUPPORTED_ENVELOPE_VERSIONS,
            SUPPORTED_CANONICALIZATION_VERSIONS,
            SUPPORTED_DOCUMENT_TYPES,
            REVIEW_WARNING_VALIDATION_STATUSES);

    private static final ParsedDataCompatibilityService GENERAL =
            new ParsedDataCompatibilityService();

    /** Evaluates the shipped defaults; never throws for a well-formed parsed envelope. */
    public Decision evaluate(EngineResultEnvelope envelope) {
        return evaluate(envelope, DEFAULT);
    }

    /** Evaluates one explicit policy — the form a Lab run uses, with the manifest's vocabularies. */
    public Decision evaluate(EngineResultEnvelope envelope, Policy policy) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(policy, "policy");
        ParsedDataCompatibilityService.CompatibilityDecision general =
                GENERAL.evaluate(envelope, generalize(policy));
        return new Decision(general.compatible(), rejection(general.rejection()),
                general.warnings().stream().map(IncomeEnvelopeCompatibility::warning).toList());
    }

    /** Income's four vocabularies expressed as a generalized contract. */
    private static ParsedDataCompatibilityService.Policy generalize(Policy policy) {
        return new ParsedDataCompatibilityService.Policy(
                policy.envelopeVersions(),
                policy.canonicalizationVersions(),
                policy.documentTypes(),
                Set.of(),
                1,
                policy.reviewWarningValidationStatuses(),
                InstanceReleaseManifest.ReviewPolicy.WARN,
                InstanceReleaseManifest.MissingFieldPolicy.PRESERVE);
    }

    /**
     * Narrows the generalized rejection vocabulary to the three codes Income publishes.
     *
     * <p>The last three cases cannot arise under {@link #generalize}: Income never requires a
     * document type, never demands more than one supported document, and warns rather than
     * rejects on review and missing fields. They map to the nearest published code anyway so a
     * future policy change degrades to a safe refusal instead of throwing at runtime.
     */
    private static RejectionCode rejection(ParsedDataCompatibilityService.RejectionCode code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case ENVELOPE_VERSION_UNSUPPORTED -> RejectionCode.ENVELOPE_VERSION_UNSUPPORTED;
            case CANONICALIZATION_VERSION_UNSUPPORTED ->
                    RejectionCode.CANONICALIZATION_VERSION_UNSUPPORTED;
            case NO_SUPPORTED_DOCUMENT, MINIMUM_DOCUMENTS_NOT_MET, REQUIRED_DOCUMENT_TYPE_MISSING,
                 REVIEW_REQUIRED, MISSING_FIELD -> RejectionCode.NO_SUPPORTED_DOCUMENT;
        };
    }

    private static Warning warning(ParsedDataCompatibilityService.Warning warning) {
        WarningCode code = switch (warning.code()) {
            case FIELD_REVIEW_REQUIRED -> WarningCode.FIELD_REVIEW_REQUIRED;
            case FIELD_MISSING -> WarningCode.FIELD_MISSING;
            case UNSUPPORTED_DOCUMENT_IGNORED -> WarningCode.UNSUPPORTED_DOCUMENT_IGNORED;
        };
        return new Warning(code, warning.documentOrdinal(), warning.documentTypeCode(),
                warning.fieldName(), warning.groupKey());
    }
}
