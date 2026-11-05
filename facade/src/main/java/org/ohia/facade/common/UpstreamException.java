package org.ohia.facade.common;

/** A server we depend on (HAPI, the data host) failed or refused. Maps to HTTP 502. */
public class UpstreamException extends RuntimeException {
    private final int upstreamStatus;

    public UpstreamException(int upstreamStatus, String message) {
        super(message);
        this.upstreamStatus = upstreamStatus;
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
        this.upstreamStatus = -1;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }
}
