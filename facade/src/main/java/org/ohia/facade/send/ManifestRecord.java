package org.ohia.facade.send;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * What the ARex content server needs to know about one sent manifest. Written before the
 * referral is delivered, so a receiver that fetches an image the moment it gets the referral
 * is already covered.
 *
 * @param manifestId             the id of the manifest List resource (also its id in /arex/List/{id})
 * @param clientId               the one registered tester this manifest was published for
 * @param requestingOrganization that tester's organization reference; checked on every retrieval
 * @param expiresAt              retrieval is refused after this instant, whatever the token says
 * @param retracted              true once the manifest has been withdrawn
 * @param manifest               the manifest List resource exactly as sent
 * @param entries                the content objects, by their opaque ids
 */
public record ManifestRecord(String manifestId, String sendId, String clientId, String requestingOrganization,
        Instant createdAt, Instant expiresAt, boolean retracted, String referralId, JsonNode manifest,
        List<PerSendCopier.Entry> entries) {
}
