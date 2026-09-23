package com.pragmaticds.docengine.results.web;

import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import com.pragmaticds.docengine.results.service.EngineResultQueryService;
import com.pragmaticds.docengine.results.service.EngineResultQueryService.VerifiedContent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated immutable-result metadata and ADMIN-only exact-content reads. */
@RestController
public class EngineResultController {

    private static final MediaType CANONICAL_MEDIA_TYPE =
            MediaType.parseMediaType(EngineResultQueryService.CANONICAL_MEDIA_TYPE);

    private final EngineResultQueryService queries;
    private final AuditService audit;

    public EngineResultController(EngineResultQueryService queries, AuditService audit) {
        this.queries = queries;
        this.audit = audit;
    }

    @GetMapping("/v1/packages/{packageId}/engine-results")
    public List<EngineResultMetadataView> engineResultHistory(
            @PathVariable("packageId") UUID packageId) {
        return queries.history(packageId).stream().map(EngineResultMetadataView::from).toList();
    }

    @GetMapping(
            path = "/v1/packages/{packageId}/engine-result",
            produces = EngineResultQueryService.CANONICAL_MEDIA_TYPE)
    public ResponseEntity<byte[]> current(@PathVariable("packageId") UUID packageId) {
        return auditedResponse(queries.current(packageId));
    }

    @GetMapping(
            path = "/v1/packages/{packageId}/engine-results/{revision}",
            produces = EngineResultQueryService.CANONICAL_MEDIA_TYPE)
    public ResponseEntity<byte[]> revision(
            @PathVariable("packageId") UUID packageId,
            @PathVariable("revision") int revision) {
        return auditedResponse(queries.revision(packageId, revision));
    }

    private ResponseEntity<byte[]> auditedResponse(VerifiedContent content) {
        byte[] bytes = content.bytes();
        audit.record(
                AuditEvent.ACTION_ENGINE_RESULT_ACCESSED,
                "ENGINE_RESULT",
                content.resultId(),
                Map.of(
                        "packageId", content.packageId(),
                        "processingJobId", content.processingJobId(),
                        "revision", content.revision(),
                        "envelopeSha256", content.envelopeSha256(),
                        "byteCount", bytes.length));
        return ResponseEntity.ok()
                .contentType(CANONICAL_MEDIA_TYPE)
                .contentLength(bytes.length)
                .eTag("\"" + content.envelopeSha256() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }
}
