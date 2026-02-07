package com.nuti.traffic.grid;

public class GridValidationException extends RuntimeException {

    public GridValidationException(String message) {
        super(message);
    }

    public GridValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
