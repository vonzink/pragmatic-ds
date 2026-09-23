package com.pragmaticds.rag.lab.engine;

import java.util.Objects;

/**
 * A Document Engine transport or verification failure.
 *
 * <p>Payload-free by construction, exactly like {@link LabContractException}: a stable {@link Code}
 * plus an optional HTTP status number. There is no free-text message and no cause, so no engine
 * body, bearer token, URI, filename or parsed value can travel inside this exception into a
 * response, a log line, or an audit row.
 */
public final class DocumentEngineFailure extends RuntimeException {

    /** Stable, value-free failure taxonomy for the backend-only engine adapter. */
    public enum Code {
        /** The Lab upload was empty; no engine call was made. */
        UPLOAD_EMPTY,
        /** The Lab upload exceeded the configured Lab ceiling; no engine call was made. */
        UPLOAD_TOO_LARGE,
        /** The caller's idempotency key was absent, blank, over-long, or not a header token. */
        IDEMPOTENCY_KEY_INVALID,
        /** A caller argument was absent or out of range; no engine call was made. */
        REQUEST_ARGUMENT_INVALID,
        /** The connection failed, was interrupted, or the stream ended early. */
        ENGINE_REQUEST_FAILED,
        /** The connect or read timeout elapsed. */
        ENGINE_TIMEOUT,
        /** The engine answered a redirect; the adapter never follows one. */
        ENGINE_REDIRECT_REFUSED,
        /** The engine answered a non-2xx status. */
        ENGINE_STATUS_UNEXPECTED,
        /** The response {@code Content-Type} was not the expected media type. */
        ENGINE_CONTENT_TYPE_UNEXPECTED,
        /** No {@code ETag} accompanied an exact-content read. */
        ENGINE_ETAG_MISSING,
        /** The {@code ETag} was not a quoted lowercase 64-character hexadecimal digest. */
        ENGINE_ETAG_MALFORMED,
        /** No {@code Content-Length} accompanied an exact-content read. */
        ENGINE_CONTENT_LENGTH_MISSING,
        /** The {@code Content-Length} was not a non-negative integer. */
        ENGINE_CONTENT_LENGTH_MALFORMED,
        /** The received byte count did not equal the declared {@code Content-Length}. */
        ENGINE_CONTENT_LENGTH_MISMATCH,
        /** The SHA-256 of the received bytes did not equal the quoted {@code ETag}. */
        ENGINE_DIGEST_MISMATCH,
        /** The declared or received envelope exceeded the configured ceiling. */
        ENGINE_ENVELOPE_TOO_LARGE,
        /** The envelope declared a different package than the one requested. */
        ENGINE_PACKAGE_MISMATCH,
        /** The envelope declared a different revision than the one requested. */
        ENGINE_REVISION_MISMATCH,
        /** An operational response (upload, job, history) was absent, unparseable, or incomplete. */
        ENGINE_RESPONSE_MALFORMED,
        /** The read model answered non-2xx, timed out, or could not be reached. */
        ENGINE_READMODEL_UNAVAILABLE,
        /** The read-model body failed the strict fields-view contract or named another document. */
        ENGINE_READMODEL_MALFORMED,
        /** The read model's document set or types did not match the pinned envelope. */
        READMODEL_DOCUMENT_MISMATCH,
        /** A document's read-model field keys did not match the pinned envelope's. */
        READMODEL_FIELD_MISMATCH
    }

    /** Sentinel for a failure that never reached, or never received, an HTTP status. */
    public static final int NO_HTTP_STATUS = 0;

    private final Code code;
    private final int httpStatus;

    public DocumentEngineFailure(Code code) {
        this(code, NO_HTTP_STATUS);
    }

    public DocumentEngineFailure(Code code, int httpStatus) {
        // No cause, ever: a wrapped IOException carries the request URI, and a wrapped provider
        // error carries its body. The stack trace stays writable — class and method names only.
        super(Objects.requireNonNull(code, "code").name(), null, false, true);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public Code code() {
        return code;
    }

    /** The engine's HTTP status, or {@link #NO_HTTP_STATUS} when there was none. */
    public int httpStatus() {
        return httpStatus;
    }

    /** Code and status only — never a URI, header value, body, or credential. */
    @Override
    public String toString() {
        return "DocumentEngineFailure[code=" + code + ", httpStatus=" + httpStatus + "]";
    }
}
