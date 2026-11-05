package org.ohia.facade.send;

import java.time.Instant;

/**
 * One delivery attempt, as logged. The target bearer token (if any) is never part of this.
 *
 * @param outcome delivered (target answered 2xx), rejected-by-target (non-2xx) or unreachable
 */
public record SendRecord(String sendId, Instant at, String clientId, String targetBaseUrl, String referralId,
        String manifestId, Instant manifestExpires, String outcome, int targetStatus, String targetResponseExcerpt,
        long millis, String error) {
}
