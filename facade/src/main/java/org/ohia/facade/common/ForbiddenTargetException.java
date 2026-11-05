package org.ohia.facade.common;

/** The requested send target is not allowed (not allow-listed, or an unsafe address). Maps to HTTP 403. */
public class ForbiddenTargetException extends RuntimeException {
    public ForbiddenTargetException(String message) {
        super(message);
    }
}
