package com.example.espoc.common.web;

import org.springframework.http.HttpStatus;

/** Uniform error class for all POCs. Carries an HTTP status the handler maps directly. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException internal(String code, String message, Throwable cause) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, code, message, cause);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
