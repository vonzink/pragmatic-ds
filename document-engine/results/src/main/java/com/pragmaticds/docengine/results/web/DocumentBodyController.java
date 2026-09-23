package com.pragmaticds.docengine.results.web;

import com.pragmaticds.docengine.platform.web.EntityTags;
import com.pragmaticds.docengine.results.body.DocumentBody;
import com.pragmaticds.docengine.results.body.DocumentBodyComposer;
import com.pragmaticds.docengine.results.body.DocumentBodyMarkdown;
import com.pragmaticds.docengine.results.body.DocumentBodySource;
import com.pragmaticds.docengine.results.body.DocumentBodySourceLoader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/documents/{id}/body.md} — one document's own prose as deterministic Markdown, for
 * a consumer that must READ the page rather than cite a field.
 *
 * <h2>Why this exists beside {@code /fields.md} rather than instead of it</h2>
 *
 * <p>The two renderings answer different questions and neither substitutes for the other.
 * {@code /fields.md} projects the EXTRACTION read model: typed, masked, evidence-bearing values,
 * which is what a calculation must consume — an LLM misreads digits out of a tabular layout, so a
 * number a schema can supply should never be read off prose. But a schema is a fixed field list
 * authored in advance, so {@code /fields.md} can only ever answer what someone anticipated, and it
 * renders an EMPTY document when classification produced no type: no type, no schema, no
 * occurrences.
 *
 * <p>{@link DocumentBody} states the complementary property in its own contract — a body "needs no
 * schema at all, which is also why it is the only artifact that survives a document classified
 * {@code UNKNOWN}". That is precisely the case a report-writing consumer cannot afford to lose: the
 * documents hardest to classify are the ones whose words matter most, and until this endpoint the
 * only rendering on the wire went blank on exactly those.
 *
 * <p>Composition and rendering were already here and already proven ({@code DocumentBodyComposer},
 * {@code DocumentBodyMarkdown}, {@code DocumentBodySourceLoader}, each with its own tests). Nothing
 * served them: {@code DocumentBodyMarkdown} had no caller outside its own package. This class is
 * that caller and adds no logic of its own — ordering, escaping and determinism all stay where they
 * were proven.
 *
 * <h2>Security posture</h2>
 *
 * <p><b>ADMIN or {@code SCOPE_ENGINE_RESULT_READ} only</b> — the raw-content line in
 * {@code SecurityConfig}, beside {@code /spans}, {@code /structure} and the engine-result bytes.
 * Unlike {@code /fields.md}, the body is NOT masked: it is every captured word on the page, and
 * masking is defined per named sensitive field, which a paragraph of body text does not have. It
 * first shipped under the broad READONLY read rule on the reasoning that it is "a rendering, not the
 * envelope"; that was wrong — the shape of the text does not change what the text reveals.
 * {@code RawContentAdminBoundaryIT} now pins it. Tenancy and soft-delete are the loader's job — a
 * cross-tenant, tombstoned or nonexistent id all answer the same opaque 404, so a caller cannot
 * probe for the existence of another tenant's document.
 *
 * <p>Headers mirror {@code /fields.md}: an ETag that is the SHA-256 of the rendered bytes
 * themselves — an honest cache validator that claims nothing about the machine parse, which a
 * borrowed envelope hash would — with {@code must-revalidate} rather than {@code no-store}.
 */
@RestController
public class DocumentBodyController {

    private static final MediaType MARKDOWN =
            new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final DocumentBodySourceLoader loader;

    public DocumentBodyController(DocumentBodySourceLoader loader) {
        this.loader = loader;
    }

    /**
     * Named {@code bodyMarkdown}, not {@code get}: springdoc derives the operationId from the
     * METHOD name, and a second {@code get} across the controllers is resolved by numbering them in
     * scan order — which would make some other controller's public operationId depend on where this
     * class happens to be scanned. {@code OperationIdStabilityIT} enforces it.
     */
    @GetMapping(path = "/v1/documents/{id}/body.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<byte[]> bodyMarkdown(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Optional<DocumentBodySource> source = loader.load(id);
        if (source.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DocumentBody body = DocumentBodyComposer.compose(source.get());

        // byte[], not String: the rendered bytes reach the wire without a converter getting a
        // chance to re-encode them, which is what makes the ETag a claim about the RESPONSE.
        byte[] rendered = DocumentBodyMarkdown.render(body).getBytes(StandardCharsets.UTF_8);
        String etag = "\"" + sha256(rendered) + "\"";

        // EntityTags, not etag.equals(ifNoneMatch): RFC 9110 lets a client send `*`, a
        // comma-separated list, or a weak W/"..." validator, and naive equality answers 200 to all
        // three — a silent cache miss on every conditional request that was not byte-exact.
        if (EntityTags.anyMatch(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok()
                .contentType(MARKDOWN)
                .eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, "private, must-revalidate")
                .body(rendered);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
