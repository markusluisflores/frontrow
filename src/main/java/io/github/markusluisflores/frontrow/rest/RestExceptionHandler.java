package io.github.markusluisflores.frontrow.rest;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/**
 * Renders every failure a REST controller raises as Problem Details (spec §6). A DomainException carries its code.
 * Malformed input becomes invalid_request naming the parameter. Anything else is a bug and gets a generic 500: no
 * message is echoed (an IllegalStateException can carry internal ids), and no database failure is guessed into a
 * conflict code (plan 4A decision 7).
 */
@RestControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domainFailure(DomainException failure) {
        ProblemDetail problem = Problems.of(failure);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus());
        if (failure.errorCode() == ErrorCode.CONTENTION_RETRY) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpectedFailure(Exception failure) {
        LOG.error("Unhandled failure in a REST request", failure);
        return ResponseEntity.internalServerError().body(Problems.plain(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (causedBy(failure, RequestBodyLimitFilter.BodyTooLargeException.class)) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(Problems.plain(HttpStatus.CONTENT_TOO_LARGE));
        }
        return invalid(Problems.invalidParameter(jsonParameter(failure)));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = failure.getPropertyName();
        return invalid(Problems.invalidParameter(name == null ? "request" : snakeCase(name)));
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (failure instanceof MissingRequestHeaderException missing) {
            return invalid(Problems.invalidParameter(missing.getHeaderName()));
        }
        return super.handleServletRequestBindingException(failure, headers, status, request);
    }

    private static ResponseEntity<Object> invalid(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** The first JSON property on the failing path — already snake_case — or "body" if Jackson names none. */
    private static String jsonParameter(HttpMessageNotReadableException failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson) {
                for (JacksonException.Reference reference : jackson.getPath()) {
                    if (reference.getPropertyName() != null) {
                        return reference.getPropertyName();
                    }
                }
            }
        }
        return "body";
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /** A path variable's Java name (holdGroupId) as the wire names it (hold_group_id). */
    static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
