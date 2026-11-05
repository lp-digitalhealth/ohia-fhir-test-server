package org.ohia.facade.arex;

/** An OAuth 2.0 token-endpoint error (RFC 6749 section 5.2). Rendered as {"error": ..., "error_description": ...}. */
public class OAuthException extends RuntimeException {
    private final int httpStatus;
    private final String error;

    public OAuthException(int httpStatus, String error, String description) {
        super(description);
        this.httpStatus = httpStatus;
        this.error = error;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String error() {
        return error;
    }
}
