package com.pragmaticds.docengine.extraction.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.extraction.domain.ExtractionSchema;
import com.pragmaticds.docengine.extraction.repo.ExtractionSchemaRepository;
import com.pragmaticds.docengine.extraction.web.ExtractionSchemaView.AuthoredHistory;
import com.pragmaticds.docengine.extraction.web.ExtractionSchemaView.AuthoredVersion;
import com.pragmaticds.docengine.extraction.web.ExtractionSchemaView.EffectiveSchema;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads back what {@link SchemaAuthoringService} wrote.
 *
 * <p>Authoring shipped without one, which left an org able to write a schema and unable to see it.
 * That is a poor place to leave a configuration surface: iterating on a definition meant keeping
 * the posted bytes somewhere outside the system and trusting they still matched.
 *
 * <h2>What "effective" means, and why the loader answers it</h2>
 *
 * <p>The listing reports the schema that DECIDES extraction, not every row the org can see. Which
 * row wins is {@link ExtractionSchemaLoader}'s rule — org scope shadows global wholesale per type,
 * highest version wins — and it is asked rather than re-derived. A second implementation of
 * shadowing in a reader is a description of something that did not happen, which is the same
 * reasoning the loader's own snapshot is built on.
 *
 * <h2>Definitions come back only for the org's OWN schemas</h2>
 *
 * <p>The effective listing names global built-ins but does not hand over their internals, and the
 * history endpoint refuses a type the org has not authored. A tenant needs its own definitions back
 * to iterate on them; it does not need the platform's regexes, and publishing those to every tenant
 * is a business decision rather than a side effect of adding a reader. Widening this is one line
 * and a conversation.
 */
@Service
public class SchemaListingService {

    private final ExtractionSchemaRepository schemas;
    private final ExtractionSchemaLoader loader;
    private final ObjectMapper mapper = new ObjectMapper();

    public SchemaListingService(ExtractionSchemaRepository schemas, ExtractionSchemaLoader loader) {
        this.schemas = schemas;
        this.loader = loader;
    }

    /** The schema deciding each document type for this org, ordered by type code. */
    @Transactional(readOnly = true)
    public List<EffectiveSchema> effectiveForCurrentOrg() {
        UUID orgId = TenantContext.require();

        // Which types this org has authored for itself. Read from the same visibility query the
        // loader composes its answer from, so "ORG" here means exactly "an own-org row won".
        Set<String> ownedTypes = new HashSet<>();
        for (ExtractionSchema row : schemas.findActiveVisibleTo(orgId)) {
            if (row.getOrgId() != null) {
                ownedTypes.add(row.getDocumentTypeCode());
            }
        }

        return loader.fingerprintViewForCurrentOrg().stream()
                .map(
                        winner ->
                                new EffectiveSchema(
                                        winner.documentTypeCode(),
                                        winner.version(),
                                        ownedTypes.contains(winner.documentTypeCode())
                                                ? EffectiveSchema.SCOPE_ORG
                                                : EffectiveSchema.SCOPE_GLOBAL,
                                        fieldCount(winner.definition())))
                .sorted(Comparator.comparing(EffectiveSchema::documentTypeCode))
                .toList();
    }

    /**
     * Every version this org authored for one type, newest first.
     *
     * <p>404 when the org has authored none — including for a type that exists globally. "You have
     * not authored this" and "no such type" are the same answer here on purpose: the endpoint is
     * about the org's own authoring history, and distinguishing them would report the existence of
     * global built-ins through a status code.
     */
    @Transactional(readOnly = true)
    public AuthoredHistory historyForCurrentOrg(String documentTypeCode) {
        UUID orgId = TenantContext.require();
        List<ExtractionSchema> owned = schemas.findOwnedByType(orgId, documentTypeCode);
        if (owned.isEmpty()) {
            throw DomainException.notFound(ErrorCode.NOT_FOUND);
        }

        List<AuthoredVersion> versions =
                owned.stream()
                        .sorted(
                                Comparator.comparing(
                                        ExtractionSchema::getVersion, SchemaVersions.DESCENDING))
                        .map(
                                row ->
                                        new AuthoredVersion(
                                                row.getVersion(),
                                                row.isActive(),
                                                definitionOf(row)))
                        .toList();
        return new AuthoredHistory(documentTypeCode, versions);
    }

    /**
     * The stored definition as JSON.
     *
     * <p>Read with a PLAIN mapper, not the strict one authoring admits bytes through. These bytes
     * already survived the strict parse on the way in, and a reader that could fail on its own
     * stored row would make a schema unreadable after the fact — the one state from which an author
     * cannot recover by authoring a correction.
     */
    private JsonNode definitionOf(ExtractionSchema row) {
        try {
            return mapper.readTree(row.getDefinition());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A stored definition that will not parse is corruption, not a bad request. Ids only —
            // the definition itself never reaches an error path.
            throw new DomainException(ErrorCode.INTERNAL, 500, java.util.Map.of("schemaId", row.getId()));
        }
    }

    private int fieldCount(String definition) {
        try {
            JsonNode fields = mapper.readTree(definition).path("fields");
            return fields.isArray() ? fields.size() : 0;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A count is a convenience, not the answer. An unparseable definition still LISTS —
            // reporting zero is honest and keeps one corrupt row from hiding every other type.
            return 0;
        }
    }
}
