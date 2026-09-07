package com.luky.nexusmind.exception;

import org.springframework.http.HttpStatus;

public class CustomException  extends RuntimeException{
    private final HttpStatus status;
    private final Object code;

    public CustomException(String message, HttpStatus status) {
        this(message, status, status.value());
    }

    public CustomException(String message, HttpStatus status, Object code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Object getCode() {
        return code;
    }
}
