package com.example.bookingpoc.common;

import org.springframework.http.HttpStatus;

public class BookingException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public BookingException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static BookingException conflict(String code, String message) {
        return new BookingException(HttpStatus.CONFLICT, code, message);
    }

    public static BookingException notFound(String code, String message) {
        return new BookingException(HttpStatus.NOT_FOUND, code, message);
    }

    public static BookingException badRequest(String code, String message) {
        return new BookingException(HttpStatus.BAD_REQUEST, code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
