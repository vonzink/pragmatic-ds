package com.pragmaticds.docengine.platform.error;

import java.util.Map;

/**
 * The one exception domain code throws. Carries a stable {@link ErrorCode} and non-sensitive
 * parameters; the message is built FROM the code and params, so it can never contain document
 * content by construction.
 */
public class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final int httpStatus;
    private final Map<String, Object> params;

    public DomainException(ErrorCode code, int httpStatus) {
        this(code, httpStatus, Map.of());
    }

    /** @param params non-sensitive parameters only — sizes, counts, limits. Never content. */
    public DomainException(ErrorCode code, int httpStatus, Map<String, Object> params) {
        super(code.name() + (params.isEmpty() ? "" : " " + params));
        this.code = code;
        this.httpStatus = httpStatus;
        this.params = Map.copyOf(params);
    }

    public static DomainException notFound(ErrorCode code) {
        return new DomainException(code, 404);
    }

    public static DomainException badRequest(ErrorCode code, Map<String, Object> params) {
        return new DomainException(code, 400, params);
    }

    public static DomainException conflict(ErrorCode code, Map<String, Object> params) {
        return new DomainException(code, 409, params);
    }

    public ErrorCode code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Map<String, Object> params() {
        return params;
    }
}
