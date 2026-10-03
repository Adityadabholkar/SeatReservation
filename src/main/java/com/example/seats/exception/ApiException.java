package com.example.seats.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** A domain/client error that maps to a clean 4xx response (never a 500). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        // no stack trace: declines are expected, high-volume outcomes and must stay cheap
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, Object> details() { return details; }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
