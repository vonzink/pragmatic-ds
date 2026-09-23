package com.pragmaticds.docengine.parsing.client;

import com.pragmaticds.docengine.platform.error.ErrorCode;

/**
 * A worker call failed. Carries ONLY stable codes — never response body text, which could quote
 * document content (contract invariant 4; the message here may end up in logs).
 *
 * <p>{@code workerCode} is the stable code from the worker's error envelope ({@code
 * {"error": "<CODE>"}}), null when the failure was transport-level or the body was not
 * contract-shaped. {@code errorCode} is the platform taxonomy mapping the adapter records on the
 * stage row.
 */
public class WorkerCallException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String workerCode;
    private final int httpStatus;

    private WorkerCallException(ErrorCode errorCode, String workerCode, int httpStatus) {
        // Codes and a status only. NEVER body text.
        super("worker call failed: " + errorCode + " (http " + httpStatus + ")");
        this.errorCode = errorCode;
        this.workerCode = workerCode;
        this.httpStatus = httpStatus;
    }

    /** Transport-level failure: nothing came back. httpStatus is 0. */
    public static WorkerCallException transport(ErrorCode errorCode) {
        return new WorkerCallException(errorCode, null, 0);
    }

    /** Non-2xx with a contract error envelope ({@code workerCode} null when unparseable). */
    public static WorkerCallException http(int status, String workerCode, ErrorCode errorCode) {
        return new WorkerCallException(errorCode, workerCode, status);
    }

    /** A 2xx whose body did not match the contract shape. */
    public static WorkerCallException malformed(int status) {
        return new WorkerCallException(ErrorCode.WORKER_UNAVAILABLE, null, status);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String workerCode() {
        return workerCode;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
