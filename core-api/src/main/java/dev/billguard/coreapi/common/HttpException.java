package dev.billguard.coreapi.common;

import org.springframework.http.HttpStatus;

public final class HttpException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public HttpException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public HttpException(HttpStatus status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
