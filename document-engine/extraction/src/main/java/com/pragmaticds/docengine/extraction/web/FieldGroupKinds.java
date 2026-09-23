package com.pragmaticds.docengine.extraction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.extraction.schema.GroupKind;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The read-side answer to "is this field grouped, and how?" — design 5b D10.
 *
 * <p><b>Why this exists.</b> {@code groupKey} alone cannot say whether a field repeats. A
 * ROW-grouped field whose table region could not be located at all persists exactly ONE occurrence
 * with a NULL key ({@code DefaultFieldExtractionEngine}), and on the wire that is byte-identical to
 * an ungrouped missing field — there is no sibling occurrence to reveal the groupedness. The
 * committed Schedule E fixture carries a live instance ({@code remicExcessInclusion}). The server
 * knows, because the schema declares the group; before this class the wire did not say.
 *
 * <p><b>Why DERIVED and not persisted.</b> Every {@code extracted_field} row already cites the
 * schema that produced it ({@code schema_id}), and {@code extraction_schema} rows are immutable
 * (every column {@code updatable = false}, the entity {@code @Immutable}). So the group declaration
 * behind a row is already stored, exactly once, in the place that owns it. Copying the kind onto
 * the field row would add a second copy of a fact that cannot change — a column that can only ever
 * be right by accident of a backfill, and wrong the day a backfill misreads a definition. It would
 * also need a migration plus a backfill that re-implements JSON traversal in SQL against a
 * definition format the loader owns. Deriving costs one extra SELECT per read (the fields path
 * already issues one for the schema VERSION; this replaces it with a single row fetch that returns
 * both), and it is historically faithful for free: the kind reported is the kind the PRODUCING
 * schema declared, not the kind today's active schema declares.
 *
 * <p>Tenancy: schema ids arrive from rows already org-guarded, and the lookup still applies the
 * own-org-or-global visibility rule {@code ExtractionSchemaRepository} uses, so a stray id cannot
 * read another tenant's schema.
 */
@Component
public class FieldGroupKinds {

    private static final Logger log = LoggerFactory.getLogger(FieldGroupKinds.class);

    /**
     * The wire value for a field the schema does not group. A VALUE, not a null: the whole point of
     * D10 is to end the guessing a null invites, and "absent key + absent kind" is precisely the
     * ambiguity this component was added to remove.
     */
    public static final String NONE = "NONE";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public FieldGroupKinds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One lookup covering every schema the caller's rows cite. Batched deliberately: the export
     * path spans a whole package's documents, and a per-row query there would be one SELECT per
     * occurrence.
     */
    public Lookup forSchemas(Collection<UUID> schemaIds) {
        Set<UUID> distinct = new LinkedHashSet<>(schemaIds);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return new Lookup(Map.of(), Map.of());
        }
        UUID orgId = TenantContext.require();
        String placeholders = distinct.stream().map(id -> "?").collect(Collectors.joining(", "));
        Object[] arguments = new Object[distinct.size() + 1];
        int index = 0;
        for (UUID id : distinct) {
            arguments[index++] = id;
        }
        arguments[index] = orgId;

        Map<UUID, Map<String, String>> kinds = new HashMap<>();
        Map<UUID, String> versions = new HashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT id, version, definition FROM extraction_schema"
                                + " WHERE id IN ("
                                + placeholders
                                + ") AND (org_id = ? OR org_id IS NULL)",
                        arguments)) {
            UUID id = (UUID) row.get("id");
            versions.put(id, (String) row.get("version"));
            kinds.put(id, parseKinds(id, String.valueOf(row.get("definition"))));
        }
        return new Lookup(Map.copyOf(kinds), Map.copyOf(versions));
    }

    /**
     * {@code fieldName → kind} for one schema definition. Only the two members that matter are
     * read ({@code fields[].name} and {@code fields[].group.kind}) rather than running the full
     * {@code ExtractionSchemaLoader} parse: a read of already-extracted rows must not start failing
     * because some unrelated authoring rule tightened after those rows were written.
     */
    private Map<String, String> parseKinds(UUID schemaId, String definition) {
        try {
            Map<String, String> kinds = new HashMap<>();
            JsonNode parsed = mapper.readTree(definition);
            for (JsonNode field : parsed.path("fields")) {
                String name = field.path("name").asText();
                if (name.isEmpty()) {
                    continue;
                }
                JsonNode group = field.get("group");
                if (group == null || group.isNull() || !group.hasNonNull("kind")) {
                    kinds.put(name, NONE);
                    continue;
                }
                kinds.put(name, GroupKind.fromWire(group.get("kind").asText()).name());
            }
            return Map.copyOf(kinds);
        } catch (Exception e) {
            // Schema id ONLY — a definition body is operator-supplied data and never reaches a log
            // line or an error param (the same rule ExtractionSchemaLoader documents).
            log.error("unreadable extraction schema definition id={}", schemaId);
            throw new DomainException(
                    ErrorCode.INTERNAL, 500, Map.of("schemaId", String.valueOf(schemaId)));
        }
    }

    /** The resolved kinds (and versions) for one read. */
    public record Lookup(Map<UUID, Map<String, String>> bySchema, Map<UUID, String> versions) {

        /**
         * The declared kind of {@code fieldName} under {@code schemaId}, or {@link #NONE}.
         *
         * <p>{@code NONE} is also the answer when the schema row is no longer visible or no longer
         * declares the name. That is the honest floor: the only rows that can reach it are rows
         * whose producing schema was deleted out from under them, and reporting a grouping the
         * current definition does not state would be an invention.
         */
        public String kindOf(UUID schemaId, String fieldName) {
            return bySchema.getOrDefault(schemaId, Map.of()).getOrDefault(fieldName, NONE);
        }

        /** The {@code extraction_schema.version} of a producing schema, or null when unknown. */
        public String versionOf(UUID schemaId) {
            return versions.get(schemaId);
        }
    }
}
