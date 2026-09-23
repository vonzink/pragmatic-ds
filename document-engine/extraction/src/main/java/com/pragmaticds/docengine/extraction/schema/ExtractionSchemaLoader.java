package com.pragmaticds.docengine.extraction.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.domain.ExtractionSchema;
import com.pragmaticds.docengine.extraction.repo.ExtractionSchemaRepository;
import com.pragmaticds.docengine.platform.behavior.BehaviorViewScope;
import com.pragmaticds.docengine.platform.behavior.HeldBehaviorView;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads and parses the extraction schemas that APPLY to the current org, from the rows the org
 * may SEE (own + global built-ins).
 *
 * <p><b>The shadowing rule</b> (same as {@code RulePackLoader}): for each {@code
 * document_type_code}, if the org has any active schema of that type, the org's schemas hide
 * EVERY global schema of that type — a tenant's private paystub schema replaces the shipped
 * generic one wholesale, it never merges with it. Within the surviving scope, the highest version
 * wins (numeric segment-wise semver compare, so 1.10.0 beats 1.2.0).
 *
 * <p>Unlike classification, a type with NO schema at all is NOT an error: {@link
 * #activeSchemaFor} returns empty and extraction SKIPS the document (BANK_STATEMENT, W2, UNKNOWN
 * ship without schemas).
 *
 * <p>Parsed schemas cache per org; {@link #invalidate} is the hook for schema administration and
 * for tests that mutate schema rows underneath a warm cache. An unparseable definition →
 * {@link ErrorCode#INTERNAL} carrying the schema ID ONLY — the definition body never reaches a
 * log line or an error param.
 */
@Service
public class ExtractionSchemaLoader {

    private static final Logger log = LoggerFactory.getLogger(ExtractionSchemaLoader.class);

    /**
     * LABEL_BELOW's cell bounds when a rung omits them (plan CONTRACTS). 24 pt is the number
     * that separates a real W-2 caption's OWN value row (about 6 pt below its bottom edge) from
     * the next row's value (about 30 pt below it) — the decoy the rung must never take.
     */
    private static final double DEFAULT_MAX_DROP_PT = 24.0;

    private static final double DEFAULT_CELL_OVERLAP = 0.5;

    /**
     * LABEL_ABOVE's vertical reach when a rung omits it — {@code DEFAULT_MAX_DROP_PT}'s mirror,
     * and the same number on purpose: the two rungs should not drift apart about how far a cell
     * extends. Measured on the real online print-out the rung was written for, a balance's bottom
     * edge sits 3.7 pt above its caption's top edge; the neighbouring tile is excluded by the
     * horizontal cell window, not by this reach.
     */
    private static final double DEFAULT_MAX_RISE_PT = 24.0;

    /**
     * The largest ROW-group {@code maxRows} the two-digit row key can order correctly. The key is
     * the row ordinal zero-padded to two digits, and {@code group_key} is a text column, so a
     * hundredth row would key as {@code "100"} and sort between {@code "01"} and {@code "02"}.
     */
    private static final int MAX_ROWS_CEILING = 99;

    private static final int MAX_LINE_OFFSET = 10;

    private final ExtractionSchemaRepository schemas;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();

    /**
     * ONE cached load: the parsed schemas extraction EXECUTES against and the schema view that
     * DESCRIBES them, minted together from a single read, under one identity token. Same reason as
     * {@code RulePackLoader.Snapshot} — a description read from the database while the behavior
     * comes from a cache is a description of something that did not happen.
     */
    private record Snapshot(
            String token,
            Map<String, SchemaDefinition> schemas,
            Map<String, SchemaIdentity> identities,
            List<SchemaFingerprint> view) {}

    public ExtractionSchemaLoader(ExtractionSchemaRepository schemas) {
        this.schemas = schemas;
    }

    /**
     * The schema that applies to this document type for the current org, or empty when the type
     * has no schema at all — a skip, not an error.
     */
    public Optional<SchemaDefinition> activeSchemaFor(String documentTypeCode) {
        return Optional.ofNullable(snapshotForCurrentOrg().schemas().get(documentTypeCode));
    }

    /** The exact active row identity that produced fields for this type under the held snapshot. */
    public Optional<SchemaIdentity> activeSchemaIdentityFor(String documentTypeCode) {
        return Optional.ofNullable(snapshotForCurrentOrg().identities().get(documentTypeCode));
    }

    /**
     * Declares the schema view a whole EXTRACTING stage runs under, before the first document is
     * looked up. Without it a package that yields NO documents would consult the loader zero times,
     * and the run would finish with nothing recorded about extraction — which correctly refuses a
     * stamp, but refuses it for a package whose extraction behavior is perfectly well defined.
     */
    public void pinViewForCurrentRun() {
        snapshotForCurrentOrg();
    }

    /** Invalidation hook: schema rows changed for this org. */
    public void invalidate(UUID orgId) {
        cache.remove(orgId);
    }

    /** Global built-ins changed: every org's view is stale. */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * The winning (post-shadowing) schema row per document type — the org's effective extraction
     * view, verbatim, for the parse-once behavior fingerprint. The loader's map has no order, so
     * the sort by type code is imposed HERE; the definition travels as the raw column String and
     * is canonicalized by the fingerprint composer (C14N owns key order — {@code definition::text}
     * is never trusted).
     *
     * <p>Read from the SAME cached snapshot extraction runs against, never freshly from the
     * database, for the reason spelled out on {@link Snapshot}.
     */
    public List<SchemaFingerprint> fingerprintViewForCurrentOrg() {
        return snapshotForCurrentOrg().view();
    }

    /**
     * The snapshot CURRENTLY HELD for this org — its identity token AND the schema view that token
     * names, read together as one thing — or empty when none is held. A PURE read that never mints,
     * refreshes, or records. The finalizer compares the run's recorded token against this token AND
     * composes the stamp from this same view; reading through the serving path instead would make
     * the check compare the recorded slot against a value the read itself had just written there, so
     * an unpinned parse would be stamped anyway. Returning token and view as ONE object read ONCE
     * also means the finalizer never re-reads the view, closing the guard→compose window. See {@code
     * RulePackLoader.heldFingerprintView} for the full account.
     */
    public Optional<HeldBehaviorView<SchemaFingerprint>> heldFingerprintView() {
        Snapshot held = cache.get(TenantContext.require());
        return held == null
                ? Optional.empty()
                : Optional.of(new HeldBehaviorView<>(held.token(), held.view()));
    }

    private Snapshot snapshotForCurrentOrg() {
        Snapshot snapshot = cache.computeIfAbsent(TenantContext.require(), this::load);
        BehaviorViewScope.record(BehaviorViewScope.ViewKind.EXTRACTION_SCHEMAS, snapshot.token());
        return snapshot;
    }

    /** One winning schema row's behavior-relevant identity, for the reuse fingerprint. */
    public record SchemaFingerprint(String documentTypeCode, String version, String definition) {}

    /** Stable database identity and version of the active schema row for read-model provenance. */
    public record SchemaIdentity(UUID id, String documentTypeCode, String version) {}

    private List<ExtractionSchema> winningRows(UUID orgId) {
        List<ExtractionSchema> visible = schemas.findActiveVisibleTo(orgId);

        // Shadowing: org scope wins the whole type when it has any schema for it.
        Map<String, List<ExtractionSchema>> byType = new HashMap<>();
        for (ExtractionSchema schema : visible) {
            byType.computeIfAbsent(schema.getDocumentTypeCode(), key -> new ArrayList<>())
                    .add(schema);
        }

        List<ExtractionSchema> winners = new ArrayList<>();
        for (Map.Entry<String, List<ExtractionSchema>> candidates : byType.entrySet()) {
            boolean orgHasSchema =
                    candidates.getValue().stream().anyMatch(schema -> schema.getOrgId() != null);
            winners.add(
                    candidates.getValue().stream()
                            .filter(schema -> (schema.getOrgId() != null) == orgHasSchema)
                            .max(ExtractionSchemaLoader::compareVersions)
                            .orElseThrow());
        }
        return winners;
    }

    private Snapshot load(UUID orgId) {
        List<ExtractionSchema> winners = winningRows(orgId);

        Map<String, SchemaDefinition> loaded = new HashMap<>();
        Map<String, SchemaIdentity> identities = new HashMap<>();
        for (ExtractionSchema winner : winners) {
            loaded.put(winner.getDocumentTypeCode(), parse(winner));
            identities.put(
                    winner.getDocumentTypeCode(),
                    new SchemaIdentity(
                            winner.getId(), winner.getDocumentTypeCode(), winner.getVersion()));
        }

        List<SchemaFingerprint> view =
                winners.stream()
                        .map(
                                row ->
                                        new SchemaFingerprint(
                                                row.getDocumentTypeCode(),
                                                row.getVersion(),
                                                row.getDefinition()))
                        .sorted(java.util.Comparator.comparing(SchemaFingerprint::documentTypeCode))
                        .toList();

        return new Snapshot(
                UUID.randomUUID().toString(),
                Map.copyOf(loaded),
                Map.copyOf(identities),
                view);
    }

    private SchemaDefinition parse(ExtractionSchema row) {
        try {
            return parseAuthored(row.getDocumentTypeCode(), row.getVersion(), row.getDefinition());
        } catch (Exception e) {
            // Schema id only: the definition body may be arbitrarily broken junk and is
            // operator-supplied data — it never reaches a log line or an error param.
            log.error("unparseable extraction schema id={}", row.getId());
            throw new DomainException(
                    ErrorCode.INTERNAL, 500, Map.of("schemaId", String.valueOf(row.getId())));
        }
    }

    /**
     * Parses a definition that is NOT yet a row — the authoring path's admission test.
     *
     * <p>It is deliberately the SAME method the serving path runs, not a second implementation of
     * the same rules. "Validation accepts exactly what the loader can parse" is then true by
     * construction: a rule added to {@link #parseField}, {@link #parseGroup} or {@link
     * #validateRowGroups} tightens authoring on the same commit that tightens loading, and the two
     * can never drift into a definition that passes the door and dies at extraction time.
     *
     * <p>Unlike {@link #parse(ExtractionSchema)} this does NOT convert failure into {@link
     * ErrorCode#INTERNAL}. A stored row that will not parse is an engine invariant violation (500);
     * an inbound definition that will not parse is the author's mistake (400), and only the caller
     * knows which situation it is in. The raw exception therefore escapes — and callers must treat
     * its message as UNSAFE to echo: the fixed literals below are harmless, but an unknown enum
     * token comes back through {@code valueOf} carrying the author's own text.
     *
     * @throws RuntimeException any parse or validation failure, message not fit for a response
     */
    public SchemaDefinition parseAuthored(
            String documentTypeCode, String version, String definitionJson) {
        try {
            JsonNode definition = mapper.readTree(definitionJson);
            List<FieldSpec> fields = new ArrayList<>();
            for (JsonNode field : definition.path("fields")) {
                fields.add(parseField(field));
            }
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("schema has no fields");
            }
            validateRowGroups(fields);
            // A field's derivation (this task) may reference only OTHER, non-derived, ungrouped
            // MONEY fields of this same schema — the loader is the one door schema JSON comes
            // through, so a dangling or type-mismatched reference dies here, not at extraction
            // time on some future document.
            Map<String, FieldSpec> byName = new HashMap<>();
            for (FieldSpec f : fields) {
                byName.put(f.name(), f);
            }
            for (FieldSpec f : fields) {
                if (f.derivation() == null) {
                    continue;
                }
                if (f.dataType() != DataType.MONEY || f.group() != null) {
                    throw new IllegalArgumentException(
                            "derivation requires a MONEY field without a group: " + f.name());
                }
                List<String> inputs = new ArrayList<>(f.derivation().plus());
                inputs.addAll(f.derivation().minus());
                for (String input : inputs) {
                    if (input.equals(f.name())) {
                        throw new IllegalArgumentException("derivation references itself: " + f.name());
                    }
                    FieldSpec ref = byName.get(input);
                    if (ref == null) {
                        throw new IllegalArgumentException("derivation references unknown field " + input);
                    }
                    if (ref.dataType() != DataType.MONEY || ref.group() != null) {
                        throw new IllegalArgumentException("derivation input " + input + " is not MONEY");
                    }
                    if (ref.derivation() != null) {
                        throw new IllegalArgumentException(
                                "derivation input " + input + " is itself derived");
                    }
                }
            }
            // Phase C: the field(s) whose value identifies ONE instance of this type, so the
            // splitter can separate two of them inside one run of same-type pages. Absent on
            // every schema authored before V25, which parses to an empty list and leaves the
            // mechanism inert. A name that no field declares is a schema bug, not a silent
            // no-op: it would leave the type looking instance-aware while separating nothing.
            List<String> instanceKey = new ArrayList<>();
            for (JsonNode name : definition.path("instanceKey")) {
                String fieldName = name.asText();
                if (fields.stream().noneMatch(field -> field.name().equals(fieldName))) {
                    throw new IllegalArgumentException("instanceKey names no such field");
                }
                instanceKey.add(fieldName);
            }
            return new SchemaDefinition(
                    documentTypeCode, version, List.copyOf(fields), List.copyOf(instanceKey));
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            // Unchecked so the two callers can classify by SITUATION (stored row = 500, inbound
            // definition = 400) rather than by exception type. The message is not echoed anywhere.
            throw new IllegalArgumentException("definition is not JSON");
        }
    }

    /**
     * The ROW-group rules NO single field's block can express, checked across the whole schema.
     *
     * <p>A ROW group is identified by its REGION — the printed anchors that open and close the
     * table — because the region IS the table and a table has one row numbering. Every field over
     * one region must therefore declare the same {@code maxRows}: the cap decides how far the
     * walk goes, so two caps over one table would hand consumers a key set of three occurrences
     * for the name column and twenty for the income column, over the identical rows. That is an
     * authoring slip a schema can state, so the loader — the one door schema JSON comes through —
     * is where it dies.
     *
     * <p>What CANNOT be checked here is the sharper property: whether the fields' {@code
     * columnHeader}s actually resolve to caption lines that can share a row origin. That depends
     * on the PAGE — a real Schedule E prints two caption bands, a scan may lose one, and the same
     * schema is correct on one document and unresolvable on the next. The engine answers it at
     * runtime instead, by resolving ONE origin per (group, page) from the lowest caption line any
     * member resolves, and by failing a field that cannot name its column there to a MISSING
     * occurrence with a NULL key — never to an ordinal counted from somewhere else.
     */
    private static void validateRowGroups(List<FieldSpec> fields) {
        Map<GroupRegionSpec, GroupSpec> groupsByRegion = new HashMap<>();
        for (FieldSpec field : fields) {
            GroupSpec group = field.group();
            if (group == null || group.kind() != GroupKind.ROW || group.region() == null) {
                continue;
            }
            GroupSpec declared = groupsByRegion.putIfAbsent(group.region(), group);
            if (declared == null) {
                continue;
            }
            if (!declared.maxRows().equals(group.maxRows())) {
                throw new IllegalArgumentException(
                        "ROW groups over one region must declare the same maxRows");
            }
            if (!java.util.Objects.equals(declared.rowLabels(), group.rowLabels())) {
                // The labels ARE the key space, and a half-labeled table is the worst of both
                // worlds: the labeled field keys by letter, the unlabeled one by counted
                // ordinal, and "A" can never join "01".
                throw new IllegalArgumentException(
                        "ROW groups over one region must declare the same rowLabels");
            }
        }
    }

    private FieldSpec parseField(JsonNode field) {
        GroupSpec group = field.hasNonNull("group") ? parseGroup(field.get("group")) : null;
        List<ExtractorSpec> extractors = new ArrayList<>();
        for (JsonNode extractor : field.path("extractors")) {
            extractors.add(parseExtractor(extractor));
        }
        if (extractors.isEmpty()) {
            throw new IllegalArgumentException("field has no extractors");
        }
        validateLineOffsets(group, extractors);
        for (ExtractorSpec extractor : extractors) {
            if (extractor.value() != null && extractor.value().joinNextLine() != null
                    && (DataType.fromWire(field.path("dataType").asText()) != DataType.STRING || group != null)) {
                throw new IllegalArgumentException("joinNextLine requires a STRING field without a group");
            }
        }
        DerivationSpec derivation =
                field.hasNonNull("derivation") ? parseDerivation(field.get("derivation")) : null;
        return new FieldSpec(
                field.path("name").asText(),
                DataType.fromWire(field.path("dataType").asText()),
                field.path("required").asBoolean(),
                field.hasNonNull("normalizer") ? field.get("normalizer").asText() : null,
                field.path("sensitive").asBoolean(),
                List.copyOf(extractors),
                group,
                derivation);
    }

    /** A field's schema-declared derivation (this task): {@code sum(plus) - sum(minus)}. */
    private static DerivationSpec parseDerivation(JsonNode node) {
        java.util.Iterator<String> keys = node.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.equals("plus") && !key.equals("minus")) {
                throw new IllegalArgumentException("derivation has unknown key " + key);
            }
        }
        List<String> plus = derivationNames(node, "plus");
        List<String> minus = derivationNames(node, "minus");
        if (plus.isEmpty() && minus.isEmpty()) {
            throw new IllegalArgumentException("derivation has no inputs");
        }
        return new DerivationSpec(plus, minus);
    }

    private static List<String> derivationNames(JsonNode node, String key) {
        if (node.has(key) && !node.get(key).isArray()) {
            throw new IllegalArgumentException("derivation." + key + " must be an array of field names");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : node.path(key)) {
            if (!n.isTextual() || n.asText().isBlank()) {
                throw new IllegalArgumentException("derivation." + key + " must list field names");
            }
            out.add(n.asText());
        }
        return out;
    }

    private static void validateLineOffsets(GroupSpec group, List<ExtractorSpec> extractors) {
        for (ExtractorSpec extractor : extractors) {
            ValueSpec value = extractor.value();
            if (value == null || value.lineOffset() == 0) {
                continue;
            }
            if (group == null
                    || group.kind() != GroupKind.COLUMN
                    || extractor.method() != ExtractionMethod.ANCHOR_LABEL
                    || value.scope() != ValueScope.LINE
                    || value.occurrence() != 0) {
                throw new IllegalArgumentException(
                        "nonzero lineOffset requires ANCHOR_LABEL COLUMN LINE occurrence 0");
            }
        }
    }

    /**
     * A field's repeating-group declaration (Spec 5a). Absent means single-valued, which is what
     * every schema before Spec 5a is and must keep being.
     *
     * <p>Everything a kind needs is REQUIRED, and nothing is defaulted. {@code maxRows} in
     * particular is not the counterpart of LABEL_BELOW's {@code maxDropPt}: a cell-geometry
     * tolerance has a measurable right answer that an author can be spared, while a table's
     * length does not — and an unbounded region whose end anchor failed to match would emit
     * occurrences until the document ran out.
     */
    private GroupSpec parseGroup(JsonNode group) {
        GroupKind kind = GroupKind.fromWire(group.path("kind").asText());
        if (kind == GroupKind.COLUMN && group.has("rowLabels")) {
            // A COLUMN group's keys already ARE its printed labels; a second label list would
            // be a second key space over one group.
            throw new IllegalArgumentException("COLUMN group cannot declare rowLabels");
        }
        if (kind == GroupKind.COLUMN) {
            if (!group.hasNonNull("header")) {
                // Column x-ranges derive from the PRINTED header, never from fixed offsets, so
                // a differently-scaled scan does not shift every key one column over.
                throw new IllegalArgumentException("COLUMN group has no header");
            }
            List<String> keys = new ArrayList<>();
            for (JsonNode key : group.path("keys")) {
                keys.add(key.asText());
            }
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("COLUMN group has no keys");
            }
            if (keys.stream().anyMatch(String::isBlank)
                    || keys.stream().distinct().count() != keys.size()) {
                // A repeated key violates extracted_field_one_current at persist time, and a
                // blank one persists as '' — which coalesce(group_key, '') makes
                // indistinguishable from an ungrouped row.
                throw new IllegalArgumentException(
                        "COLUMN group keys must be distinct and non-blank");
            }
            return GroupSpec.column(parseLabel(group.get("header")), List.copyOf(keys));
        }
        if (!group.hasNonNull("region")) {
            throw new IllegalArgumentException("ROW group has no region");
        }
        JsonNode region = group.get("region");
        if (!region.hasNonNull("start") || !region.hasNonNull("end")) {
            throw new IllegalArgumentException("ROW group region needs start and end");
        }
        if (!group.hasNonNull("maxRows")) {
            throw new IllegalArgumentException("ROW group has no maxRows");
        }
        int maxRows = group.get("maxRows").asInt();
        if (maxRows <= 0 || maxRows > MAX_ROWS_CEILING) {
            // The upper bound is the row key's format, not a taste judgement. A ROW group's key
            // is the ordinal zero-padded to TWO digits, and group_key is text, so every ordering
            // that touches it — the repository's OrderBy, the export's natural-order comparator,
            // the unique index — sorts LEXICALLY. Row 100 would format as "100", which sorts
            // between "01" and "02": every value and evidence box would still be right, and the
            // table would still present scrambled, so a consumer taking "the first entity" takes
            // the wrong one. A group needing three digits is a schema-authoring error, and the
            // loader says so rather than silently mis-sorting.
            throw new IllegalArgumentException("ROW group maxRows must be 1..99");
        }
        GroupRegionSpec bounded =
                new GroupRegionSpec(parseLabel(region.get("start")), parseLabel(region.get("end")));
        if (!group.has("rowLabels")) {
            return GroupSpec.row(bounded, maxRows);
        }
        return GroupSpec.labeledRow(bounded, maxRows, parseRowLabels(group, maxRows));
    }

    /**
     * The row letters a labeled table PREPRINTS in its left-margin gutter (Spec 5a follow-up):
     * single characters {@code 'A'..'Z'}, distinct, strictly ascending, and no more of them than
     * {@code maxRows} admits.
     *
     * <p>Single capital letters because the key is text and every ordering that touches it —
     * {@code OrderBy…GroupKeyAsc}, the export comparator, the one-current unique index — sorts
     * LEXICALLY: a lowercase {@code 'a'} would sort after {@code 'Z'}, and a multi-character
     * label would interleave with single ones. Strictly ascending because declared order is
     * emission order, and it must equal lexical order for the same reason the ordinal scheme
     * zero-pads: a consumer taking "the first entity" must take the one the form prints first.
     */
    private static List<String> parseRowLabels(JsonNode group, int maxRows) {
        List<String> labels = new ArrayList<>();
        for (JsonNode label : group.path("rowLabels")) {
            labels.add(label.asText());
        }
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("rowLabels must not be empty");
        }
        String previous = null;
        for (String label : labels) {
            if (label.length() != 1 || label.charAt(0) < 'A' || label.charAt(0) > 'Z') {
                throw new IllegalArgumentException("row labels must be single letters A..Z");
            }
            if (previous != null && previous.compareTo(label) >= 0) {
                throw new IllegalArgumentException(
                        "row labels must be distinct and strictly ascending");
            }
            previous = label;
        }
        if (labels.size() > maxRows) {
            // maxRows is the cap on occurrences and each declared label IS an occurrence:
            // declaring more letters than the cap admits is a contradiction the engine
            // cannot resolve.
            throw new IllegalArgumentException("rowLabels cannot exceed maxRows");
        }
        return List.copyOf(labels);
    }

    private ExtractorSpec parseExtractor(JsonNode extractor) {
        ExtractionMethod method = ExtractionMethod.fromWire(extractor.path("method").asText());
        LabelSpec label =
                extractor.hasNonNull("label") ? parseLabel(extractor.get("label")) : null;
        TableSpec table =
                extractor.hasNonNull("table")
                        ? new TableSpec(
                                parseLabel(extractor.get("table").get("rowLabel")),
                                parseLabel(extractor.get("table").get("columnHeader")))
                        : null;
        LabelSpec columnHeader =
                extractor.hasNonNull("columnHeader")
                        ? parseLabel(extractor.get("columnHeader"))
                        : null;
        if (method == ExtractionMethod.ANCHOR_LABEL && label == null) {
            throw new IllegalArgumentException("ANCHOR_LABEL extractor has no label");
        }
        if (method == ExtractionMethod.LABEL_BELOW && label == null) {
            throw new IllegalArgumentException("LABEL_BELOW extractor has no label");
        }
        if (method != ExtractionMethod.LABEL_BELOW && extractor.has("joinCells")) {
            // Only the box-grid rung has a cell to join. On any other rung the key would be
            // silently inert — an authoring error, refused like every misplaced parameter.
            throw new IllegalArgumentException("joinCells is a LABEL_BELOW parameter");
        }
        if (method == ExtractionMethod.LABEL_ABOVE && label == null) {
            throw new IllegalArgumentException("LABEL_ABOVE extractor has no label");
        }
        if (method == ExtractionMethod.TABLE_CLUSTER && table == null) {
            throw new IllegalArgumentException("TABLE_CLUSTER extractor has no table");
        }
        if (method == ExtractionMethod.ROW_CELL && columnHeader == null) {
            // Without a column header there is no cell: the rung would take the row's FIRST
            // match, which on Schedule E Part II is the nonpassive LOSS printed left of the
            // income. An authoring error, never a default.
            throw new IllegalArgumentException("ROW_CELL extractor has no columnHeader");
        }
        // The two detector rungs (Spec 3) carry NO value block: their value comes from a
        // CHECKBOX/SIGNATURE detection, not a text pattern. Everything else still requires one.
        if (method == ExtractionMethod.CHECKBOX_STATE) {
            return new ExtractorSpec(
                    method,
                    extractor.path("strength").asDouble(),
                    null,
                    null,
                    null,
                    parseOptions(extractor),
                    parseProximityPt(extractor),
                    null);
        }
        if (method == ExtractionMethod.SIGNATURE_PRESENCE) {
            return new ExtractorSpec(
                    method,
                    extractor.path("strength").asDouble(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    parseRegion(extractor));
        }
        if (!extractor.hasNonNull("value")) {
            throw new IllegalArgumentException("extractor has no value");
        }
        JsonNode value = extractor.get("value");
        String joinNextLine = value.hasNonNull("joinNextLine") ? value.get("joinNextLine").asText() : null;
        if (joinNextLine != null && joinNextLine.isBlank()) {
            throw new IllegalArgumentException("joinNextLine must be a pattern");
        }
        if (joinNextLine != null
                && method != ExtractionMethod.ANCHOR_LABEL
                && method != ExtractionMethod.REGEX) {
            throw new IllegalArgumentException("joinNextLine is an ANCHOR_LABEL or REGEX parameter");
        }
        ValueSpec valueSpec =
                new ValueSpec(
                        value.path("pattern").asText(),
                        value.path("occurrence").asInt(),
                        parseScope(method, value),
                        parseLineOffset(value),
                        joinNextLine);
        if (method == ExtractionMethod.ROW_CELL) {
            return new ExtractorSpec(
                    method,
                    extractor.path("strength").asDouble(),
                    null,
                    null,
                    valueSpec,
                    null,
                    null,
                    null,
                    null, // maxDropPt: the REGION bounds the rows, not a drop from a label
                    extractor.hasNonNull("cellOverlap")
                            ? extractor.get("cellOverlap").asDouble()
                            : DEFAULT_CELL_OVERLAP,
                    columnHeader);
        }
        if (method == ExtractionMethod.LABEL_BELOW) {
            return new ExtractorSpec(
                    method,
                    extractor.path("strength").asDouble(),
                    label,
                    null,
                    valueSpec,
                    null,
                    null,
                    null,
                    extractor.hasNonNull("maxDropPt")
                            ? extractor.get("maxDropPt").asDouble()
                            : DEFAULT_MAX_DROP_PT,
                    extractor.hasNonNull("cellOverlap")
                            ? extractor.get("cellOverlap").asDouble()
                            : DEFAULT_CELL_OVERLAP,
                    null,
                    null,
                    parseJoinCells(extractor));
        }
        if (method == ExtractionMethod.LABEL_ABOVE) {
            return new ExtractorSpec(
                    method,
                    extractor.path("strength").asDouble(),
                    label,
                    null,
                    valueSpec,
                    null,
                    null,
                    null,
                    null, // maxDropPt: this rung reads UP; its reach is maxRisePt
                    extractor.hasNonNull("cellOverlap")
                            ? extractor.get("cellOverlap").asDouble()
                            : DEFAULT_CELL_OVERLAP,
                    null,
                    extractor.hasNonNull("maxRisePt")
                            ? extractor.get("maxRisePt").asDouble()
                            : DEFAULT_MAX_RISE_PT);
        }
        return new ExtractorSpec(
                method, extractor.path("strength").asDouble(), label, table, valueSpec);
    }

    /**
     * LABEL_BELOW's scope IS the cell — derived from the label box, {@code maxDropPt} and
     * {@code cellOverlap} — LABEL_ABOVE's likewise ({@code maxRisePt} in place of the drop), and
     * ROW_CELL's is the cell too, derived from the column header and the row's own band. None of
     * those value blocks need carry a {@code scope}, and none of the rungs consults the one it
     * gets ({@link ValueScope#LINE} stands in so nothing downstream meets a null). Every OTHER
     * value-bearing method still requires a scope: there an absent one is a real authoring error,
     * and defaulting it would silently pick a search direction nobody chose.
     */
    private static ValueScope parseScope(ExtractionMethod method, JsonNode value) {
        if ((method == ExtractionMethod.LABEL_BELOW
                        || method == ExtractionMethod.LABEL_ABOVE
                        || method == ExtractionMethod.ROW_CELL)
                && !value.hasNonNull("scope")) {
            return ValueScope.LINE;
        }
        return ValueScope.fromWire(value.path("scope").asText());
    }

    private static int parseLineOffset(JsonNode value) {
        if (!value.has("lineOffset")) {
            return 0;
        }
        JsonNode offset = value.get("lineOffset");
        if (!offset.isIntegralNumber()) {
            throw new IllegalArgumentException("lineOffset must be an integer");
        }
        if (!offset.canConvertToInt()) {
            throw new IllegalArgumentException("lineOffset must be 0..10");
        }
        int parsed = offset.intValue();
        if (parsed < 0 || parsed > MAX_LINE_OFFSET) {
            throw new IllegalArgumentException("lineOffset must be 0..10");
        }
        return parsed;
    }

    /**
     * LABEL_BELOW's {@code joinCells}: the captions of the adjacent cells on the label's own row
     * whose first value line is read together with the label's cell. Absent ⇒ null (nothing
     * joined — the pre-V47 rung, byte for byte); present ⇒ every entry must be a full label
     * spec, because a join caption that cannot be looked for is an authoring error, not an
     * empty cell.
     */
    private List<LabelSpec> parseJoinCells(JsonNode extractor) {
        if (!extractor.hasNonNull("joinCells")) {
            return null;
        }
        JsonNode joins = extractor.get("joinCells");
        if (!joins.isArray() || joins.isEmpty()) {
            throw new IllegalArgumentException("joinCells must be a non-empty array of labels");
        }
        List<LabelSpec> parsed = new ArrayList<>();
        for (JsonNode join : joins) {
            if (!join.hasNonNull("kind") || !join.hasNonNull("pattern")) {
                throw new IllegalArgumentException("joinCells entry has no label");
            }
            parsed.add(parseLabel(join));
        }
        return List.copyOf(parsed);
    }

    private List<CheckboxOption> parseOptions(JsonNode extractor) {
        List<CheckboxOption> options = new ArrayList<>();
        for (JsonNode option : extractor.path("options")) {
            if (!option.hasNonNull("label") || !option.hasNonNull("value")) {
                throw new IllegalArgumentException("checkbox option needs label and value");
            }
            options.add(
                    new CheckboxOption(
                            parseLabel(option.get("label")), option.get("value").asText()));
        }
        if (options.isEmpty()) {
            throw new IllegalArgumentException("CHECKBOX_STATE extractor has no options");
        }
        return List.copyOf(options);
    }

    private double parseProximityPt(JsonNode extractor) {
        if (!extractor.hasNonNull("proximityPt")) {
            throw new IllegalArgumentException("CHECKBOX_STATE extractor has no proximityPt");
        }
        return extractor.get("proximityPt").asDouble();
    }

    private RegionSpec parseRegion(JsonNode extractor) {
        if (!extractor.hasNonNull("region")) {
            throw new IllegalArgumentException("SIGNATURE_PRESENCE extractor has no region");
        }
        JsonNode region = extractor.get("region");
        if (!region.hasNonNull("label") || !region.hasNonNull("windowPt")) {
            throw new IllegalArgumentException("region needs label and windowPt");
        }
        JsonNode window = region.get("windowPt");
        return new RegionSpec(
                parseLabel(region.get("label")),
                new Window(
                        window.path("left").asDouble(),
                        window.path("right").asDouble(),
                        window.path("above").asDouble(),
                        window.path("below").asDouble()));
    }

    private LabelSpec parseLabel(JsonNode label) {
        return new LabelSpec(
                AnchorKind.fromWire(label.path("kind").asText()), label.path("pattern").asText());
    }

    /** Segment-wise numeric semver compare; non-numeric segments fall back to string order. */
    private static int compareVersions(ExtractionSchema a, ExtractionSchema b) {
        String[] left = a.getVersion().split("\\.");
        String[] right = b.getVersion().split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            String ls = i < left.length ? left[i] : "0";
            String rs = i < right.length ? right[i] : "0";
            int compared;
            try {
                compared = Integer.compare(Integer.parseInt(ls), Integer.parseInt(rs));
            } catch (NumberFormatException e) {
                compared = ls.compareTo(rs);
            }
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }
}
