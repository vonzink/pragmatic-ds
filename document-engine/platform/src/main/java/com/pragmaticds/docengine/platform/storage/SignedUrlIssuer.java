package com.pragmaticds.docengine.platform.storage;

import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Turns an already-authorized {@link SignedObjectRef} into a shareable download URL, and records the
 * issuance. Controllers do the org-scoped existence/access check FIRST (so a cross-tenant or
 * soft-deleted object never gets a token), then hand the ref here; this signs it, audits a PII-free
 * {@code SIGNED_URL_ISSUED} event (object-type code + ttl, never a filename or content), and returns
 * the relative URL the {@code SignedDownloadController} verifies.
 */
@Service
public class SignedUrlIssuer {

    /** The public verify-and-serve path. Single source of truth for issuer and controller. */
    public static final String DOWNLOAD_PATH = "/v1/download";

    private final SignedUrlService signer;
    private final AuditService audit;
    private final Duration ttl;

    public SignedUrlIssuer(
            SignedUrlService signer,
            AuditService audit,
            @Value("${docengine.signed-url.default-ttl-seconds:900}") long ttlSeconds) {
        this.signer = signer;
        this.audit = audit;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** A signed, time-boxed URL plus when it expires. */
    public record IssuedUrl(String url, Instant expiresAt) {}

    public IssuedUrl issue(SignedObjectRef ref) {
        String token = signer.sign(ref, ttl);
        Instant expiresAt = Instant.now().plus(ttl);
        audit.record(
                AuditEvent.ACTION_SIGNED_URL_ISSUED,
                subjectType(ref.type()),
                ref.id(),
                Map.of("objectType", ref.type(), "ttlSeconds", ttl.toSeconds()));
        return new IssuedUrl(DOWNLOAD_PATH + "?token=" + token, expiresAt);
    }

    private static String subjectType(String objectType) {
        return SignedObjectRef.TYPE_PAGE_RENDER.equals(objectType) ? "PAGE" : "SOURCE_FILE";
    }
}
