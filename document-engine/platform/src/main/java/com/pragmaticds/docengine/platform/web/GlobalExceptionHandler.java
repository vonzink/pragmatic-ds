package com.pragmaticds.docengine.platform.web;

import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to RFC 9457 problem responses carrying only the stable error code and its
 * non-sensitive parameters.
 *
 * <p>The unexpected-exception branch is the PII seam that matters: an arbitrary exception message
 * may quote document content, so it is logged (operators need it) but NEVER serialised into the
 * response, which says only INTERNAL.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(exception.httpStatus());
        problem.setTitle(exception.code().name());
        problem.setProperty("code", exception.code().name());
        problem.setProperty("params", exception.params());
        return problem;
    }

    /**
     * An upload exceeding the SERVLET multipart cap dies in Tomcat before the service-level size
     * check can run. Review finding: the catch-all turned that into 500 INTERNAL. It is the same
     * client mistake as the service-level case, so it gets the same code — FILE_TOO_LARGE — with
     * 413, the status HTTP defines for exactly this.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleOversizeUpload(MaxUploadSizeExceededException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setTitle(ErrorCode.FILE_TOO_LARGE.name());
        problem.setProperty("code", ErrorCode.FILE_TOO_LARGE.name());
        return problem;
    }

    /**
     * Framework-raised HTTP errors (404 for an unknown route, 405, 406, 415…) already carry the
     * right status. Review finding: the catch-all was flattening them all to 500. Status is kept;
     * the body is still rebuilt from the status alone — framework messages can echo request
     * content, and nothing client-supplied is reflected back.
     *
     * <p>{@link NoResourceFoundException} is listed EXPLICITLY: it implements the
     * {@code ErrorResponse} interface but does NOT extend {@link ErrorResponseException}, so
     * catching the latter alone let every unmapped URL fall through to the catch-all and answer
     * 500 — with an ERROR-level stack trace for ordinary 404 traffic. Phase 6 finding.
     */
    @ExceptionHandler({
        ErrorResponseException.class,
        ResponseStatusException.class,
        NoResourceFoundException.class
    })
    public ProblemDetail handleFrameworkStatus(Exception exception) {
        // All three implement ErrorResponse, which is where the status lives. Reading it through
        // the interface is why the list above can grow without this body changing.
        HttpStatus status =
                exception instanceof ErrorResponse errorResponse
                        ? HttpStatus.valueOf(errorResponse.getStatusCode().value())
                        : HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatus(status);
        ErrorCode code = status.is4xxClientError() ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL;
        problem.setTitle(code.name());
        problem.setProperty("code", code.name());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        log.error("unhandled exception", exception);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle(ErrorCode.INTERNAL.name());
        problem.setProperty("code", ErrorCode.INTERNAL.name());
        return problem;
    }
}
