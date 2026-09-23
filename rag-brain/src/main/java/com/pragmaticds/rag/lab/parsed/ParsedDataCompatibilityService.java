package com.pragmaticds.rag.lab.parsed;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Declarative compatibility for any instance, evaluated entirely from a release's contract.
 *
 * <p>Pure data in, pure decision out. The answer depends only on envelope and canonicalization
 * versions and on logical document types and field states. Fingerprints — envelope digest,
 * source-set digest, content hashes — and provenance never participate: identity is verified
 * elsewhere, and identity is not compatibility.
 *
 * <p>This generalizes the Income prototype's policy rather than forking it. {@code
 * IncomeEnvelopeCompatibility} now builds a {@link Policy} and delegates here, so there is one
 * predicate to reason about and one place where a contract can silently become permissive.
 *
 * <p>Warnings are value-free: schema and coordinate identifiers only, never an extracted value.
 */
@Component
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class ParsedDataCompatibilityService {

    /** Why a parsed envelope cannot feed this instance. */
    public enum RejectionCode {
        ENVELOPE_VERSION_UNSUPPORTED,
        CANONICALIZATION_VERSION_UNSUPPORTED,
        NO_SUPPORTED_DOCUMENT,
        MINIMUM_DOCUMENTS_NOT_MET,
        REQUIRED_DOCUMENT_TYPE_MISSING,
        REVIEW_REQUIRED,
        MISSING_FIELD
    }

    /**
     * The validation statuses a v2 parsed-data contract treats as needing review.
     *
     * <p>Public and immutable so every caller that builds a {@link Policy} from a stored contract
     * uses the same vocabulary. A second copy could drift, and a copy that drifted low would make
     * a release's review policy quietly permissive — the release would say it rejects packages
     * needing review and would accept them.
     */
    public static final Set<String> REVIEW_WARNING_VALIDATION_STATUSES =
            Set.of("MANUAL_REVIEW_REQUIRED");

    /** Value-free warning vocabulary; a warning never blocks analysis. */
    public enum WarningCode {
        FIELD_REVIEW_REQUIRED,
        FIELD_MISSING,
        UNSUPPORTED_DOCUMENT_IGNORED
    }

    /**
     * One value-free warning. {@code fieldName} and {@code groupKey} are null for a
     * document-level warning.
     */
    public record Warning(
            WarningCode code,
            int documentOrdinal,
            String documentTypeCode,
            String fieldName,
            String groupKey) {}

    /** The decision; {@code rejection} is null exactly when {@code compatible}. */
    public record CompatibilityDecision(
            boolean compatible,
            RejectionCode rejection,
            List<Warning> warnings,
            int supportedDocumentCount) {

        public CompatibilityDecision {
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }
    }

    /**
     * A release's compatibility vocabularies as data.
     *
     * <p>Version sets are sets rather than the contract's single string because the Income
     * prototype already shipped multi-version policies. {@link #of} widens a single contract
     * version into a one-element set, so a stored release still means exactly what it said.
     */
    public record Policy(
            Set<String> envelopeVersions,
            Set<String> canonicalizationVersions,
            Set<String> allowedDocumentTypes,
            Set<String> requireAnyDocumentTypes,
            int minimumSupportedDocuments,
            Set<String> reviewWarningValidationStatuses,
            InstanceReleaseManifest.ReviewPolicy reviewRequired,
            InstanceReleaseManifest.MissingFieldPolicy missingFields) {

        public Policy {
            envelopeVersions = Set.copyOf(Objects.requireNonNull(envelopeVersions, "envelopeVersions"));
            canonicalizationVersions = Set.copyOf(
                    Objects.requireNonNull(canonicalizationVersions, "canonicalizationVersions"));
            allowedDocumentTypes = Set.copyOf(
                    Objects.requireNonNull(allowedDocumentTypes, "allowedDocumentTypes"));
            requireAnyDocumentTypes = Set.copyOf(
                    Objects.requireNonNull(requireAnyDocumentTypes, "requireAnyDocumentTypes"));
            reviewWarningValidationStatuses = Set.copyOf(Objects.requireNonNull(
                    reviewWarningValidationStatuses, "reviewWarningValidationStatuses"));
            Objects.requireNonNull(reviewRequired, "reviewRequired");
            Objects.requireNonNull(missingFields, "missingFields");
            if (minimumSupportedDocuments < 0) {
                throw new IllegalArgumentException("minimumSupportedDocuments must not be negative");
            }
        }

        /** Builds the policy a stored release declared. */
        public static Policy of(InstanceReleaseManifest.ParsedDataContract contract,
                                Set<String> reviewWarningValidationStatuses) {
            Objects.requireNonNull(contract, "contract");
            return new Policy(
                    Set.of(contract.envelopeVersion()),
                    Set.of(contract.canonicalizationVersion()),
                    contract.allowedDocumentTypes(),
                    contract.requireAnyDocumentTypes(),
                    contract.minimumSupportedDocuments(),
                    reviewWarningValidationStatuses,
                    contract.reviewRequired(),
                    contract.missingFields());
        }
    }

    /**
     * Evaluates one envelope against one policy. Never throws for a well-formed parsed envelope.
     *
     * <p>Version checks come first and short-circuit with no warnings: a version this build cannot
     * read makes every downstream observation meaningless rather than merely noteworthy. After
     * that, every document and field is inspected so the caller sees the complete picture even
     * when a REJECT policy has already decided the outcome. The first REJECT-policy violation in
     * document-then-field order is the reported rejection, which keeps the answer deterministic.
     */
    public CompatibilityDecision evaluate(EngineResultEnvelope envelope, Policy policy) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(policy, "policy");

        if (!policy.envelopeVersions().contains(envelope.envelopeVersion())) {
            return new CompatibilityDecision(
                    false, RejectionCode.ENVELOPE_VERSION_UNSUPPORTED, List.of(), 0);
        }
        if (!policy.canonicalizationVersions().contains(envelope.canonicalizationVersion())) {
            return new CompatibilityDecision(
                    false, RejectionCode.CANONICALIZATION_VERSION_UNSUPPORTED, List.of(), 0);
        }

        List<Warning> warnings = new ArrayList<>();
        Set<String> supportedTypes = new LinkedHashSet<>();
        RejectionCode rejection = null;
        int supported = 0;

        for (EngineResultEnvelope.LogicalDocument document : envelope.documents()) {
            if (!policy.allowedDocumentTypes().contains(document.documentTypeCode())) {
                warnings.add(new Warning(WarningCode.UNSUPPORTED_DOCUMENT_IGNORED,
                        document.ordinal(), document.documentTypeCode(), null, null));
                continue;
            }
            supported++;
            supportedTypes.add(document.documentTypeCode());

            for (EngineResultEnvelope.FieldOccurrence field : document.fields()) {
                if (field.status() == EngineResultEnvelope.FieldStatus.MISSING) {
                    warnings.add(new Warning(WarningCode.FIELD_MISSING, document.ordinal(),
                            document.documentTypeCode(), field.name(), field.groupKey()));
                    if (policy.missingFields() == InstanceReleaseManifest.MissingFieldPolicy.REJECT
                            && rejection == null) {
                        rejection = RejectionCode.MISSING_FIELD;
                    }
                } else if (policy.reviewWarningValidationStatuses()
                        .contains(field.validationStatus())) {
                    warnings.add(new Warning(WarningCode.FIELD_REVIEW_REQUIRED, document.ordinal(),
                            document.documentTypeCode(), field.name(), field.groupKey()));
                    if (policy.reviewRequired() == InstanceReleaseManifest.ReviewPolicy.REJECT
                            && rejection == null) {
                        rejection = RejectionCode.REVIEW_REQUIRED;
                    }
                }
            }
        }

        // Nothing analyzable at all is a different failure from too little to be worth analyzing,
        // and an operator reading the code should be able to tell those apart.
        if (supported == 0) {
            return new CompatibilityDecision(
                    false, RejectionCode.NO_SUPPORTED_DOCUMENT, warnings, 0);
        }
        if (supported < policy.minimumSupportedDocuments()) {
            return new CompatibilityDecision(
                    false, RejectionCode.MINIMUM_DOCUMENTS_NOT_MET, warnings, supported);
        }
        if (!policy.requireAnyDocumentTypes().isEmpty()
                && policy.requireAnyDocumentTypes().stream().noneMatch(supportedTypes::contains)) {
            return new CompatibilityDecision(
                    false, RejectionCode.REQUIRED_DOCUMENT_TYPE_MISSING, warnings, supported);
        }
        if (rejection != null) {
            return new CompatibilityDecision(false, rejection, warnings, supported);
        }
        return new CompatibilityDecision(true, null, warnings, supported);
    }
}
