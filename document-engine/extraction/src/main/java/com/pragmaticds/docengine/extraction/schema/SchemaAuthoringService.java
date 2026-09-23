package com.pragmaticds.docengine.extraction.schema;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.domain.DocumentType;
import com.pragmaticds.docengine.classification.match.TextFold;
import com.pragmaticds.docengine.classification.repo.DocumentTypeRepository;
import com.pragmaticds.docengine.extraction.domain.ExtractionSchema;
import com.pragmaticds.docengine.extraction.extract.Normalizers;
import com.pragmaticds.docengine.extraction.repo.ExtractionSchemaRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Creates a new extraction schema version for the CALLING org — the runtime replacement for a
 * hand-written Flyway seed.
 *
 * <p>Nothing about the read path changes. A tenant row is exactly the row a migration would have
 * inserted with a non-null {@code org_id}, so {@code ExtractionSchemaLoader}'s shadowing rule picks
 * it up unaltered (the org's schemas hide every global schema of that type wholesale, highest
 * version wins), and {@code ReuseFingerprintService} therefore composes the parse-once fingerprint
 * over the tenant definition VERBATIM with no change of its own. That is the design: authoring adds
 * a row, and every downstream property — provenance, shadowing, behavior identity, reuse — follows
 * from the row already meaning what it means. See {@link #invalidateAfterCommit} for the single
 * place that is NOT free.
 *
 * <h2>The four admission tests, and why each one is here rather than at extraction time</h2>
 *
 * <ol>
 *   <li><b>The type exists.</b> An unknown {@code document_type_code} produces a schema that is not
 *       wrong, merely inert — it will never be selected, because no document is ever classified as
 *       that type. Inert is the worst failure mode available here: the author sees a 201, waits for
 *       fields that never arrive, and has nothing to look at. A typo'd type code dies at the door.
 *   <li><b>The version is three numeric segments.</b> {@code ExtractionSchemaLoader.compareVersions}
 *       compares segment-wise as integers and falls back to STRING order on a non-numeric segment,
 *       so {@code 1.0.0-rc1} would order against {@code 1.0.0} by text and "highest version wins"
 *       would stop meaning what it says. The loader's fallback is right for rows that already exist;
 *       it is not a licence to author new ones.
 *   <li><b>The definition is STRICT JSON.</b> This is the non-obvious one, and it is a reuse
 *       property, not a parsing one. {@code ReuseFingerprintService} re-reads every winning
 *       definition through a mapper with duplicate-key detection and trailing-token rejection
 *       enabled, and throws when a definition fails it — which is caught, which yields NO
 *       fingerprint, which SILENTLY DISABLES PARSE-ONCE REUSE FOR THE WHOLE ORG. Postgres {@code
 *       jsonb} accepts a duplicate key happily (it keeps the last one), so nothing else in the
 *       system would ever complain. The same strictness is therefore applied HERE, at the only door
 *       a tenant definition can come through.
 *   <li><b>The loader can parse it, and every pattern in it compiles.</b> Parsing is delegated to
 *       {@code ExtractionSchemaLoader.parseAuthored} — literally the serving path's own parser, so
 *       the two cannot drift. Pattern compilation is checked separately because the loader does NOT
 *       do it: a malformed regex survives loading and dies per-document inside the extraction
 *       engine, long after the author is gone.
 * </ol>
 *
 * <h2>What the 400 body says, and does not</h2>
 *
 * <p>Failures carry {@link ErrorCode#INVALID_REQUEST} plus the FIELD at fault, and never the
 * parser's own message. A definition is operator-supplied, but it is not therefore safe to echo: a
 * literal anchor is routinely lifted verbatim off a real document, so a message that quotes the
 * offending token can quote a borrower's employer, or their name. The specific complaint is logged
 * with the type and version — both non-sensitive — and the author reads it there. Returning a
 * curated, closed set of reason codes would be strictly better for the author and is the obvious
 * follow-up; it is not free, because it means giving ~20 loader throw sites a stable identity.
 */
@Service
public class SchemaAuthoringService {

    private static final Logger log = LoggerFactory.getLogger(SchemaAuthoringService.class);

    /**
     * Exactly {@code MAJOR.MINOR.PATCH}, all numeric — the shape every seeded version has, and the
     * only shape {@code compareVersions} orders numerically end to end.
     */
    private static final Pattern VERSION = Pattern.compile("\\d{1,6}\\.\\d{1,6}\\.\\d{1,6}");

    /**
     * The reader's copy of {@code ReuseFingerprintService}'s STRICT mapper settings. A copy, and
     * not the original, because {@code :extraction} cannot depend on {@code :app} — module
     * boundaries are load-bearing here. {@code SchemaAuthoringStrictJsonTest} pins the two lists
     * equal so the copy cannot quietly become laxer than the thing it is protecting.
     */
    private static final ObjectMapper STRICT =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final ExtractionSchemaRepository schemas;
    private final DocumentTypeRepository documentTypes;
    private final ExtractionSchemaLoader loader;

    public SchemaAuthoringService(
            ExtractionSchemaRepository schemas,
            DocumentTypeRepository documentTypes,
            ExtractionSchemaLoader loader) {
        this.schemas = schemas;
        this.documentTypes = documentTypes;
        this.loader = loader;
    }

    /**
     * Parses the authoring REQUEST envelope with the strict mapper and delegates.
     *
     * <p>The envelope goes through the strict mapper too, not merely the definition inside it, and
     * that is the reason this method exists at all rather than the controller binding a record. A
     * lax parse of the outer object would collapse {@code {"definition":{…broken…},"definition":{…
     * fine…}}} to the second one before the strict check ever ran, and the strict check's job is to
     * be the ONLY parse a tenant definition survives. See the controller for the full account.
     */
    @Transactional
    public AuthoredSchema authorFromRequest(String requestBody) {
        JsonNode envelope = requireStrictJsonObject(requestBody, "?", "?");
        JsonNode definition = envelope.get("definition");
        if (definition == null || definition.isNull()) {
            throw invalid("definition");
        }
        return author(
                envelope.path("documentTypeCode").asText(null),
                envelope.path("version").asText(null),
                definition.toString());
    }

    /**
     * Validates and inserts one new schema version for the current org, retiring that org's
     * previously active versions of the same type in the same transaction.
     *
     * <p>The retire-then-insert order is the migrations' order (V10, V12, V43…) and it is not
     * cosmetic: both statements are inside ONE transaction, so no reader ever sees a moment with
     * two active org versions of a type, nor a moment with none.
     */
    @Transactional
    public AuthoredSchema author(String documentTypeCode, String version, String definitionJson) {
        UUID orgId = TenantContext.require();

        requireKnownType(orgId, documentTypeCode);
        requireAuthorableVersion(version);
        JsonNode definition = requireStrictJsonObject(definitionJson, documentTypeCode, version);
        SchemaDefinition parsed = requireLoadable(documentTypeCode, version, definitionJson);
        requireCompilablePatterns(parsed, documentTypeCode, version);
        requireKnownNormalizers(parsed);

        List<ExtractionSchema> owned = schemas.findOwnedByType(orgId, documentTypeCode);
        requireNewHighestVersion(owned, documentTypeCode, version);

        List<String> retired =
                owned.stream()
                        .filter(ExtractionSchema::isActive)
                        .map(ExtractionSchema::getVersion)
                        .sorted()
                        .toList();
        schemas.retireOwnedActive(orgId, documentTypeCode);

        // Re-serialized from the STRICT parse, not stored as the author typed it. The bytes that
        // reach the column are then bytes this service has already proven parse cleanly, and no
        // trailing garbage the strict read rejected can ride along inside the same string.
        ExtractionSchema row =
                schemas.save(
                        new ExtractionSchema(
                                orgId, documentTypeCode, version, definition.toString(), true));

        invalidateAfterCommit(orgId);
        log.info(
                "extraction schema authored orgId={} documentTypeCode={} version={} retired={}",
                orgId,
                documentTypeCode,
                version,
                retired.size());
        return new AuthoredSchema(row.getId(), documentTypeCode, version, true, retired);
    }

    /**
     * Drops this org's parsed-schema cache — but only once the row is actually committed, and that
     * ordering is the whole content of this method.
     *
     * <p>Invalidating inline would open a window in which a concurrent request on another thread
     * misses the cache, re-reads the table, does not see the uncommitted row, and re-populates the
     * cache with the PRE-write view — permanently, since the write's own invalidation has already
     * happened. The new schema would then apply on some nodes and not others with no further event
     * to fix it.
     *
     * <p>What this does NOT do is invalidate other JVMs. A multi-instance deployment keeps serving
     * the previous view on every node but this one until its cache is dropped, and that is a
     * PROPAGATION delay, not a correctness bug: a stale node parses under the old view AND stamps
     * the fingerprint composed from that same held view (the whole point of {@code
     * BehaviorViewScope}), so its results stay honestly labelled and can never be served as a reuse
     * hit to a run that executed under the new schema. Cross-node invalidation is therefore a
     * latency feature, not a safety one, and is deliberately out of scope here.
     */
    private void invalidateAfterCommit(UUID orgId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            loader.invalidate(orgId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        // afterCompletion, not afterCommit: a ROLLED BACK write must also drop the
                        // cache. The transaction may have loaded the snapshot mid-flight, and a
                        // snapshot minted from a view that no longer exists is worse than none.
                        loader.invalidate(orgId);
                    }
                });
    }

    private void requireKnownType(UUID orgId, String documentTypeCode) {
        if (documentTypeCode == null || documentTypeCode.isBlank()) {
            throw invalid("documentTypeCode");
        }
        boolean known =
                documentTypes.findActiveVisibleTo(orgId).stream()
                        .map(DocumentType::getCode)
                        .anyMatch(documentTypeCode::equals);
        if (!known) {
            // The code is taxonomy, not content — safe to name in the response, and naming it is
            // the difference between "fix your typo" and "something was wrong".
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST,
                    400,
                    Map.of("field", "documentTypeCode", "documentTypeCode", documentTypeCode));
        }
    }

    private static void requireAuthorableVersion(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST,
                    400,
                    Map.of("field", "version", "expected", "MAJOR.MINOR.PATCH"));
        }
    }

    private static JsonNode requireStrictJsonObject(
            String definitionJson, String documentTypeCode, String version) {
        if (definitionJson == null || definitionJson.isBlank()) {
            throw invalid("definition");
        }
        JsonNode definition;
        try {
            definition = STRICT.readTree(definitionJson);
        } catch (Exception notStrict) {
            // Class name only. The message quotes the offending token, and the offending token is
            // author text that may have been lifted off a real document.
            log.warn(
                    "rejected schema definition: not strict JSON documentTypeCode={} version={}"
                            + " exception={}",
                    documentTypeCode,
                    version,
                    notStrict.getClass().getSimpleName());
            throw invalid("definition");
        }
        if (definition == null || !definition.isObject()) {
            throw invalid("definition");
        }
        return definition;
    }

    private SchemaDefinition requireLoadable(
            String documentTypeCode, String version, String definitionJson) {
        try {
            return loader.parseAuthored(documentTypeCode, version, definitionJson);
        } catch (RuntimeException unloadable) {
            log.warn(
                    "rejected schema definition: loader refused it documentTypeCode={} version={}"
                            + " reason={}",
                    documentTypeCode,
                    version,
                    unloadable.getMessage());
            throw invalid("definition");
        }
    }

    /**
     * Compiles every authored pattern through the SAME seam the engine matches with — {@code
     * TextFold}, never a bare {@code Pattern.compile} — so a pattern that compiles here is a
     * pattern that compiles there. The fold rewrites the pattern's foldable literals before
     * compiling, and an escape it introduces can itself be the thing that fails to compile; testing
     * the unfolded text would miss exactly those.
     */
    private static void requireCompilablePatterns(
            SchemaDefinition parsed, String documentTypeCode, String version) {
        for (FieldSpec field : parsed.fields()) {
            for (LabelSpec label : labelsOf(field)) {
                compile(() -> TextFold.pattern(label.kind(), label.pattern()),
                        documentTypeCode, version, field.name());
            }
            for (ExtractorSpec extractor : field.extractors()) {
                ValueSpec value = extractor.value();
                if (value != null) {
                    compile(() -> TextFold.regexPattern(value.pattern()),
                            documentTypeCode, version, field.name());
                    if (value.joinNextLine() != null) {
                        compile(() -> TextFold.regexPattern(value.joinNextLine()),
                                documentTypeCode, version, field.name());
                    }
                }
            }
        }
    }

    /** Every label-shaped pattern a field can carry, across group, ladder and detector rungs. */
    private static List<LabelSpec> labelsOf(FieldSpec field) {
        List<LabelSpec> labels = new ArrayList<>();
        GroupSpec group = field.group();
        if (group != null) {
            addIfPresent(labels, group.header());
            if (group.region() != null) {
                addIfPresent(labels, group.region().start());
                addIfPresent(labels, group.region().end());
            }
        }
        for (ExtractorSpec extractor : field.extractors()) {
            addIfPresent(labels, extractor.label());
            addIfPresent(labels, extractor.columnHeader());
            if (extractor.table() != null) {
                addIfPresent(labels, extractor.table().rowLabel());
                addIfPresent(labels, extractor.table().columnHeader());
            }
            if (extractor.region() != null) {
                addIfPresent(labels, extractor.region().label());
            }
            if (extractor.options() != null) {
                extractor.options().forEach(option -> addIfPresent(labels, option.label()));
            }
        }
        return labels;
    }

    private static void addIfPresent(List<LabelSpec> labels, LabelSpec label) {
        if (label != null) {
            labels.add(label);
        }
    }

    private static void compile(
            Runnable compilation, String documentTypeCode, String version, String fieldName) {
        try {
            compilation.run();
        } catch (PatternSyntaxException malformed) {
            // The field NAME is schema vocabulary the author chose, not document content, so it is
            // safe both to log and to return. The pattern itself is neither.
            log.warn(
                    "rejected schema definition: pattern does not compile documentTypeCode={}"
                            + " version={} field={}",
                    documentTypeCode,
                    version,
                    fieldName);
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST,
                    400,
                    Map.of("field", "definition.pattern", "fieldName", fieldName));
        }
    }

    private static void requireKnownNormalizers(SchemaDefinition parsed) {
        for (FieldSpec field : parsed.fields()) {
            if (!Normalizers.isKnownNormalizer(field.normalizer())) {
                // Both names are schema vocabulary; Normalizers itself says a normalizer name is
                // configuration and never document content.
                throw new DomainException(
                        ErrorCode.INVALID_REQUEST,
                        400,
                        Map.of(
                                "field", "normalizer",
                                "fieldName", field.name(),
                                "normalizer", field.normalizer()));
            }
        }
    }

    /**
     * A new version must be strictly HIGHER than every version this org already has for the type —
     * retired ones included.
     *
     * <p>Two distinct failures collapse into this one rule. Re-using a number is refused because
     * {@code extracted_field.schema_id} makes a version an identity, so a second definition under a
     * used number rewrites the meaning of values already extracted (the unique index would refuse
     * an active duplicate anyway; this refuses a RETIRED one too, which the index does not).
     * Authoring a LOWER number is refused because it would be accepted, stored, marked active — and
     * then never selected, since the loader takes the highest version among the active rows. A
     * write that succeeds and does nothing is the failure mode worth spending a 409 on.
     */
    private static void requireNewHighestVersion(
            List<ExtractionSchema> owned, String documentTypeCode, String version) {
        Optional<String> highest =
                owned.stream()
                        .map(ExtractionSchema::getVersion)
                        .max(SchemaVersions.ASCENDING);
        if (highest.isEmpty()) {
            return;
        }
        if (SchemaVersions.ASCENDING.compare(version, highest.get()) > 0) {
            return;
        }
        throw new DomainException(
                ErrorCode.CONFLICT,
                409,
                Map.of(
                        "documentTypeCode", documentTypeCode,
                        "version", version,
                        "highestExistingVersion", highest.get()));
    }

    /**
     * Segment-wise numeric compare over already-validated {@code MAJOR.MINOR.PATCH} strings — the
     * ordering {@code ExtractionSchemaLoader.compareVersions} applies when it selects a winner,
     * restated over Strings rather than rows. Restated and not shared because the loader's version
     * takes two ENTITIES: making it take Strings would widen a serving-path method for an authoring
     * caller, and the format this one accepts is already narrowed to the numeric case where the two
     * cannot disagree.
     */
    private static DomainException invalid(String field) {
        return new DomainException(ErrorCode.INVALID_REQUEST, 400, Map.of("field", field));
    }
}
