package com.pragmaticds.docengine.extraction.web;

import com.pragmaticds.docengine.extraction.schema.AuthoredSchema;
import com.pragmaticds.docengine.extraction.schema.SchemaAuthoringService;
import com.pragmaticds.docengine.extraction.schema.SchemaListingService;
import com.pragmaticds.docengine.extraction.web.ExtractionSchemaView.AuthoredHistory;
import com.pragmaticds.docengine.extraction.web.ExtractionSchemaView.EffectiveSchemaList;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /v1/extraction-schemas} — author extraction schema versions for the calling org, and read
 * back what this org authored.
 *
 * <h2>RBAC</h2>
 *
 * <p>There is no annotation here, and that is the point. {@code SecurityConfig.matrix} is the single
 * central role matrix, and its final {@code /v1/**} rule reserves every write not named above it to
 * ADMIN. The POST is named nowhere above it, so it is ADMIN — through the same mechanism as every
 * other administrative write, with nothing to keep in sync. A {@code @PreAuthorize} here would be a
 * SECOND authorization surface for one endpoint, and the two would eventually disagree; the matrix's
 * own javadoc says the RBAC rules live in exactly one place.
 *
 * <p>{@code ExtractionSchemaAuthoringRbacIT} measures that claim rather than trusting it, because
 * "covered by a catch-all" is exactly the kind of property that is true until someone inserts a
 * broader rule above it.
 *
 * <h2>The reads sit at ADMIN too, and their matcher is load-bearing</h2>
 *
 * <p>A GET is NOT covered by that catch-all: the matrix grants {@code GET /v1/**} to READONLY. So
 * both read paths are named explicitly in the matrix AHEAD of that broad rule. Without those lines
 * they fall through it and hand every read-only principal the org's authored regexes and label
 * text.
 *
 * <p>Unlike the triage matcher — where a service-side check also enforces REVIEWER, so mis-ordering
 * costs a layer rather than the gate — this matcher IS the gate. Nothing else enforces a role on
 * these paths. {@code ExtractionSchemaListingIT} therefore measures the ordering the way
 * {@code RawContentAdminBoundaryIT} does, with a nonexistent id: a READONLY caller must get 403,
 * and an ADMIN caller must get 404 from the controller. That difference IS the ordering.
 */
@RestController
public class ExtractionSchemaAuthoringController {

    private final SchemaAuthoringService authoring;
    private final SchemaListingService listing;

    public ExtractionSchemaAuthoringController(
            SchemaAuthoringService authoring, SchemaListingService listing) {
        this.authoring = authoring;
        this.listing = listing;
    }

    /**
     * The body is taken as a raw String and parsed inside the service, NOT bound to a record.
     *
     * <p>Binding would have been tidier and would have silently defeated the strictest admission
     * test this endpoint has. Spring's default {@code ObjectMapper} has duplicate-key detection OFF:
     * it parses {@code {"a":1,"a":2}} to {@code {"a":2}} without complaint. A definition bound
     * through it and re-serialized would arrive at the validator already deduplicated — passing the
     * strict check trivially, and storing a definition whose author believes it says something else.
     * The one mapper that must see these bytes is the strict one, so the bytes are what the
     * controller hands over.
     *
     * <p>The envelope shape is {@code {documentTypeCode, version, definition}} — no {@code active}
     * member and no {@code orgId} member, both omissions deliberate. Activation is not the author's
     * to choose: a new version is active by definition, because the alternative is a row that exists
     * and does nothing. And the org comes from the authenticated principal's {@code TenantContext},
     * never from the body — a writable {@code orgId} would be a cross-tenant write dressed as a
     * field.
     */
    @PostMapping(path = "/v1/extraction-schemas", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthoredSchema author(@RequestBody String body) {
        return authoring.authorFromRequest(body);
    }

    /**
     * The schema DECIDING each document type for this org, after shadowing — not every row the org
     * can see.
     *
     * <p>Each entry says whether this org authored the winner or inherits the global built-in,
     * which is the question an author actually has, and carries the winner's field count. That
     * count is the cheapest way to notice the behaviour that surprises people: a tenant schema
     * shadows a global WHOLESALE rather than merging, so a count well below the global's means
     * fields that used to extract no longer do.
     */
    @GetMapping("/v1/extraction-schemas")
    public EffectiveSchemaList effective() {
        return new EffectiveSchemaList(listing.effectiveForCurrentOrg());
    }

    /**
     * Every version this org authored for one document type, newest first, retired ones included.
     *
     * <p>404 when this org has authored none — including for a type that exists globally. The
     * endpoint is about the org's own authoring history, and answering differently for a global
     * type would report the existence of built-ins through a status code.
     *
     * <p><b>Named {@code authoredVersions}, not {@code history}.</b> springdoc derives an
     * {@code operationId} from the METHOD name, and {@code DocumentReviewController.history}
     * already owns {@code history}. A second one does not fail — springdoc silently renames a
     * collision to {@code history_1}, and which of the two keeps the bare name is not something
     * this class controls. That would have changed the generated client method name for an endpoint
     * that already shipped. {@code EngineResultApiIT} pins the existing id and caught it.
     */
    @GetMapping("/v1/extraction-schemas/{documentTypeCode}")
    public AuthoredHistory authoredVersions(@PathVariable String documentTypeCode) {
        return listing.historyForCurrentOrg(documentTypeCode);
    }
}
