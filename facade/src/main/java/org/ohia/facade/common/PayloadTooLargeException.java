package org.ohia.facade.common;

/** A request body is over the limit. Maps to HTTP 413. Nothing was stored. */
public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
