package com.pragmaticds.rag.lab.findings;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.rag.lab.analyze.InstanceToolExecutor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The reference findings rule: one WARNING per field the extractor flagged for manual review.
 *
 * <p>Chosen as the first rule because it needs no domain arithmetic and no policy of its own —
 * {@code IncomeEnvelopeCompatibility.REVIEW_WARNING_VALIDATION_STATUSES} already names
 * MANUAL_REVIEW_REQUIRED a review warning rather than a rejection, so this rule restates an
 * existing decision instead of taking a new one. The rules that carry judgment are slice 2.
 *
 * <p>Deterministic: documents and fields are walked in envelope order, and nothing here reads a
 * clock, a random, or anything outside the envelope. Nothing is logged — a summary names a field
 * on a borrower's document.
 */
@Component
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class ManualReviewRequiredFindingTool implements InstanceToolExecutor {

    public static final String NAME = "field.manual_review_required";
    public static final String VERSION = "1.0.0";

    /** The status this rule fires on, matching IncomeEnvelopeCompatibility's review warnings. */
    static final String MANUAL_REVIEW_REQUIRED = "MANUAL_REVIEW_REQUIRED";

    private static final String INPUT_SCHEMA = "ai/tools/findings-v1.input.schema.json";
    private static final String OUTPUT_SCHEMA = "ai/tools/findings-v1.output.schema.json";

    private final String inputSchemaSha256;
    private final String outputSchemaSha256;

    public ManualReviewRequiredFindingTool() {
        this.inputSchemaSha256 = sha256Hex(read(INPUT_SCHEMA));
        this.outputSchemaSha256 = sha256Hex(read(OUTPUT_SCHEMA));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String inputSchemaSha256() {
        return inputSchemaSha256;
    }

    @Override
    public String outputSchemaSha256() {
        return outputSchemaSha256;
    }

    @Override
    public boolean producesFindings() {
        return true;
    }

    @Override
    public JsonNode execute(EngineResultEnvelope envelope) {
        List<Finding> findings = new ArrayList<>();
        for (LogicalDocument document : envelope.documents()) {
            for (FieldOccurrence occurrence : document.fields()) {
                if (!MANUAL_REVIEW_REQUIRED.equals(occurrence.validationStatus())) {
                    continue;
                }
                FindingAnchor anchor = FindingAnchorResolver.resolve(document, occurrence);
                findings.add(new Finding(
                        NAME,
                        VERSION,
                        Finding.Severity.WARNING,
                        "The extractor flagged " + occurrence.name()
                                + " on this " + document.documentTypeCode()
                                + " for manual review.",
                        null,
                        // The status IS the input here: this rule reads no value, so a changed
                        // number must not look like a changed finding.
                        FindingDigest.of(MANUAL_REVIEW_REQUIRED),
                        List.of(anchor),
                        List.of()));
            }
        }
        return FindingJson.toOutput(findings);
    }

    private static byte[] read(String resource) {
        try (InputStream in = ManualReviewRequiredFindingTool.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
