package org.ohia.facade.receive;

import java.time.Instant;
import java.util.List;
import org.ohia.facade.arexclient.ArexClient;

/**
 * The result of receiving one payload: what we stored, how the pull went, and every finding.
 * A failed or stopped pull is a result recorded here, not an error.
 *
 * @param accepted     the payload was stored in HAPI exactly as received
 * @param hapiEntries  how many entries the stored transaction had
 * @param stoppedBecause why the pull stopped early, or null
 * @param manifestId   the manifest id; when a payload carries several, their ids joined with ", "
 * @param sender       the sender's ARex base derived from the content URLs, or null
 * @param state        {@code pending} while the pull is still running, then {@code complete}; {@code failed} if the pull
 *                     itself crashed and {@code interrupted} if the server restarted during it. Receipts written before
 *                     this field existed are {@code complete}.
 */
public record Receipt(String id, Instant receivedAt, String referralId, String manifestId, boolean accepted,
        int hapiEntries, String sender, String tokenEndpoint, String authorizationDetailsType, String stoppedBecause,
        List<ArexClient.Step> steps, List<ReceiptObject> objects, List<ArexClient.Finding> findings, String useCase,
        String state) {

    public static final String PENDING = "pending";
    public static final String COMPLETE = "complete";
    public static final String FAILED = "failed";
    public static final String INTERRUPTED = "interrupted";

    /** A receipt written before {@code state} existed: the pull had finished. */
    public Receipt(String id, Instant receivedAt, String referralId, String manifestId, boolean accepted, int hapiEntries,
            String sender, String tokenEndpoint, String authorizationDetailsType, String stoppedBecause,
            List<ArexClient.Step> steps, List<ReceiptObject> objects, List<ArexClient.Finding> findings, String useCase) {
        this(id, receivedAt, referralId, manifestId, accepted, hapiEntries, sender, tokenEndpoint, authorizationDetailsType,
                stoppedBecause, steps, objects, findings, useCase, COMPLETE);
    }

    /** A receipt with no use case named is UC04a (the only kind there was before UC07 could be received). */
    public Receipt(String id, Instant receivedAt, String referralId, String manifestId, boolean accepted, int hapiEntries,
            String sender, String tokenEndpoint, String authorizationDetailsType, String stoppedBecause,
            List<ArexClient.Step> steps, List<ReceiptObject> objects, List<ArexClient.Finding> findings) {
        this(id, receivedAt, referralId, manifestId, accepted, hapiEntries, sender, tokenEndpoint, authorizationDetailsType,
                stoppedBecause, steps, objects, findings, null, COMPLETE);
    }

    /** Receipts written before UC07 could be received are all UC04a; ones written before {@code state} existed are complete. */
    public Receipt {
        if (useCase == null || useCase.isBlank()) {
            useCase = "uc04a";
        }
        if (state == null || state.isBlank()) {
            state = COMPLETE;
        }
    }

    /**
     * One content object. {@code entryId} is what the sender called it (display text only);
     * {@code fileKey} is the unique, safe name we store it under and the API uses. {@code viewable}
     * is true only for a saved object whose BYTES are a JPEG or PNG; never by the declared type.
     */
    public record ReceiptObject(String entryId, String fileKey, String url, String declaredContentType, long declaredSize,
            String declaredSha256, String status, long bytes, String sha256, String sniffedType, boolean viewable,
            String manifestId) {

        /** An object with no manifest named (receipts written before a payload could carry several). */
        public ReceiptObject(String entryId, String fileKey, String url, String declaredContentType, long declaredSize,
                String declaredSha256, String status, long bytes, String sha256, String sniffedType, boolean viewable) {
            this(entryId, fileKey, url, declaredContentType, declaredSize, declaredSha256, status, bytes, sha256, sniffedType,
                    viewable, null);
        }
    }
}
