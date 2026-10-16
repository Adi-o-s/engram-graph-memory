package com.memora.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Base class for errors that map to a specific HTTP status and a stable machine-readable code. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    /** Returned for missing records and for records owned by another tenant, so IDs leak nothing. */
    public static ApiException notFound(String entity, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", entity + " " + id + " was not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }

    public static ApiException conflict(String code, String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.CONFLICT, code, message, details);
    }

    public static ApiException staleVersion(String entity, Object id, long expected, long actual) {
        return conflict("stale_version",
                entity + " " + id + " is at version " + actual + " but the request expected " + expected,
                Map.of("expectedVersion", expected, "currentVersion", actual));
    }
}
