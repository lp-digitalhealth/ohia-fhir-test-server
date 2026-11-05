package org.ohia.facade.common;

/** The input or the source data is wrong. Maps to HTTP 422. Nothing was written. */
public class BadDataException extends RuntimeException {
    public BadDataException(String message) {
        super(message);
    }
}
