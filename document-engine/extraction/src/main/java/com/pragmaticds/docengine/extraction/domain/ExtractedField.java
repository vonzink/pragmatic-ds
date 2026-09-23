package com.pragmaticds.docengine.extraction.domain;

import com.pragmaticds.docengine.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Layer 2 of the four value layers: the deterministic normalized result of one field extraction
 * (docs/DATA_MODEL.md 6, V7). A field that could not be found still gets a row — confidence 0, no
 * evidence, {@link #VALIDATION_MANUAL_REVIEW_REQUIRED}. A missing field is a RESULT, not an
 * absence.
 *
 * <p>Supersession rides on the {@code extracted_field_one_current} partial unique index, same
 * rule as {@code classification_result}: EXTRACTING replaces a package's rows wholesale
 * (delete-then-recreate under retry), never mutates a value in place.
 *
 * <p>Spec 5a widened that index to {@code (org_id, logical_document_id, field_name,
 * coalesce(group_key, ''))}. A field may therefore legitimately repeat within one document —
 * three rental properties, N partnership entities — each occurrence carrying its own value,
 * confidence and evidence. A NULL {@link #getGroupKey() group key} is exactly the pre-Spec-5a
 * row, so every single-valued schema behaves identically.
 */
@Entity
@Table(name = "extracted_field")
public class ExtractedField extends TenantScopedEntity {

    // validation_status CHECK values (V7).
    public static final String VALIDATION_NOT_VALIDATED = "NOT_VALIDATED";
    public static final String VALIDATION_VALID = "VALID";
    public static final String VALIDATION_WARNING = "WARNING";
    public static final String VALIDATION_ERROR = "ERROR";
    public static final String VALIDATION_UNABLE_TO_VALIDATE = "UNABLE_TO_VALIDATE";
    public static final String VALIDATION_MANUAL_REVIEW_REQUIRED = "MANUAL_REVIEW_REQUIRED";

    // review_status CHECK values (V7).
    public static final String REVIEW_NOT_REVIEWED = "NOT_REVIEWED";
    public static final String REVIEW_CONFIRMED = "CONFIRMED";
    public static final String REVIEW_CORRECTED = "CORRECTED";
    public static final String REVIEW_REJECTED = "REJECTED";

    @Column(name = "logical_document_id", nullable = false, updatable = false)
    private UUID logicalDocumentId;

    @Column(name = "schema_id", nullable = false, updatable = false)
    private UUID schemaId;

    @Column(name = "field_name", nullable = false, updatable = false)
    private String fieldName;

    @Column(name = "data_type", nullable = false, updatable = false)
    private String dataType;

    /**
     * The repeating-group occurrence key (Spec 5a D1/D2), or NULL for a single-valued field —
     * which is exactly the pre-V13 row. The value is the one PRINTED ON THE FORM: {@code A} /
     * {@code B} / {@code C} for Schedule E's property columns, the row's own ordinal for entity
     * tables where the form prints no key. Never a synthetic index: a reviewer looking at
     * "property B, rents received" has to be able to find it on the page.
     *
     * <p>It rides in the {@code extracted_field_one_current} partial unique index as
     * {@code coalesce(group_key, '')}, so occurrences with distinct keys coexist while an
     * ungrouped field keeps exactly its old one-row-per-name rule.
     */
    @Column(name = "group_key", updatable = false)
    private String groupKey;

    /** Exactly as it appears on the page: {@code "$3,565.87"}. */
    @Column(name = "displayed_text", updatable = false)
    private String displayedText;

    /** As captured, before normalization (may differ from displayed on OCR pages). */
    @Column(name = "raw_value", updatable = false)
    private String rawValue;

    @Column(name = "normalized_text", updatable = false)
    private String normalizedText;

    @Column(name = "normalized_number", updatable = false)
    private BigDecimal normalizedNumber;

    @Column(name = "normalized_date", updatable = false)
    private LocalDate normalizedDate;

    /** Composite values (addresses) — unused by paystub@1.0.0, mapped for the schema's sake. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "normalized_json", updatable = false)
    private String normalizedJson;

    @Column(name = "extraction_method", nullable = false, updatable = false)
    private String extractionMethod;

    @Column(name = "extractor_version", nullable = false, updatable = false)
    private String extractorVersion;

    @Column(name = "confidence", nullable = false, updatable = false)
    private BigDecimal confidence;

    /**
     * The confidence formula's inputs, auditable and tunable rather than a bare number:
     * {@code {"spanConfidence","anchorStrength","normalizerCertainty"}}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confidence_components", updatable = false)
    private String confidenceComponents;

    @Column(name = "validation_status", nullable = false)
    private String validationStatus = VALIDATION_NOT_VALIDATED;

    @Column(name = "review_status", nullable = false)
    private String reviewStatus = REVIEW_NOT_REVIEWED;

    /** Drives masking at read boundaries (Phase 7); from the schema field spec. */
    @Column(name = "is_sensitive", nullable = false)
    private boolean sensitive;

    /** The one mutable column — flipped off by supersession, never back on. */
    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    protected ExtractedField() {
        // JPA
    }

    /**
     * The pre-Spec-5a shape — a single-valued field, i.e. no group key. Kept so every existing
     * call site (and every existing test) stays untouched by the group dimension, the same way
     * {@code ExtractorSpec} kept its shorter constructors through Specs 3 and 4.
     */
    public ExtractedField(
            UUID logicalDocumentId,
            UUID schemaId,
            String fieldName,
            String dataType,
            String displayedText,
            String rawValue,
            String normalizedText,
            BigDecimal normalizedNumber,
            LocalDate normalizedDate,
            String normalizedJson,
            String extractionMethod,
            String extractorVersion,
            BigDecimal confidence,
            String confidenceComponents,
            String validationStatus,
            String reviewStatus,
            boolean sensitive) {
        this(
                logicalDocumentId,
                schemaId,
                fieldName,
                dataType,
                displayedText,
                rawValue,
                normalizedText,
                normalizedNumber,
                normalizedDate,
                normalizedJson,
                extractionMethod,
                extractorVersion,
                confidence,
                confidenceComponents,
                validationStatus,
                reviewStatus,
                sensitive,
                null);
    }

    public ExtractedField(
            UUID logicalDocumentId,
            UUID schemaId,
            String fieldName,
            String dataType,
            String displayedText,
            String rawValue,
            String normalizedText,
            BigDecimal normalizedNumber,
            LocalDate normalizedDate,
            String normalizedJson,
            String extractionMethod,
            String extractorVersion,
            BigDecimal confidence,
            String confidenceComponents,
            String validationStatus,
            String reviewStatus,
            boolean sensitive,
            String groupKey) {
        this.logicalDocumentId = logicalDocumentId;
        this.schemaId = schemaId;
        this.fieldName = fieldName;
        this.dataType = dataType;
        this.displayedText = displayedText;
        this.rawValue = rawValue;
        this.normalizedText = normalizedText;
        this.normalizedNumber = normalizedNumber;
        this.normalizedDate = normalizedDate;
        this.normalizedJson = normalizedJson;
        this.extractionMethod = extractionMethod;
        this.extractorVersion = extractorVersion;
        this.confidence = confidence;
        this.confidenceComponents = confidenceComponents;
        this.validationStatus = validationStatus;
        this.reviewStatus = reviewStatus;
        this.sensitive = sensitive;
        this.groupKey = groupKey;
    }

    public UUID getLogicalDocumentId() {
        return logicalDocumentId;
    }

    public UUID getSchemaId() {
        return schemaId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getDataType() {
        return dataType;
    }

    /** The occurrence key printed on the form, or null for a single-valued field. */
    public String getGroupKey() {
        return groupKey;
    }

    public String getDisplayedText() {
        return displayedText;
    }

    public String getRawValue() {
        return rawValue;
    }

    public String getNormalizedText() {
        return normalizedText;
    }

    public BigDecimal getNormalizedNumber() {
        return normalizedNumber;
    }

    public LocalDate getNormalizedDate() {
        return normalizedDate;
    }

    public String getNormalizedJson() {
        return normalizedJson;
    }

    public String getExtractionMethod() {
        return extractionMethod;
    }

    public String getExtractorVersion() {
        return extractorVersion;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getConfidenceComponents() {
        return confidenceComponents;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public boolean isCurrent() {
        return current;
    }

    /**
     * The single sanctioned IN-PLACE mutation of this row: a human review decision flips
     * {@code review_status} (CONFIRMED / CORRECTED / REJECTED). Phase 7b. The Layer-2 value
     * columns are {@code updatable=false} and stay untouched — a correction lives in a
     * {@code review_decision} row, so the machine's value remains permanently readable.
     */
    public void applyReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    /**
     * Phase 3 conflict signal: AI disagreed with a deterministic current value. The value and its
     * provenance remain immutable; only validation is escalated for review.
     */
    public void markAiConflict() {
        this.validationStatus = VALIDATION_WARNING;
    }

    /**
     * A human's decision was carried onto this replacement row, but the machine's own reading
     * changed underneath it (a different value, or a different schema version). The human's value
     * keeps being served — dropping it would restore a reading a human already refused — but the
     * occurrence goes back in front of a reviewer, because the call was made against text that has
     * since been read differently.
     *
     * <p>Escalates to {@link #VALIDATION_MANUAL_REVIEW_REQUIRED} rather than {@link
     * #VALIDATION_WARNING} deliberately: that status IS the reviewer queue's definition of shaky
     * (it is what the AI stage's second-pass selector reads), and a stale human value nobody is
     * told to look at is the failure mode this whole carry-forward exists to avoid.
     */
    public void markCarriedReviewNeedsRecheck() {
        this.validationStatus = VALIDATION_MANUAL_REVIEW_REQUIRED;
    }
}
