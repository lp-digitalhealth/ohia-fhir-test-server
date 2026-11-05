package org.ohia.facade.seed;

import java.util.List;

/**
 * What was seeded: the list of resources in the UC07 I1 package, in authored order, and the
 * ARex payload files. The resources themselves live in HAPI; the sender reads them from there.
 */
public record PackageIndex(String ref, String manifestId, List<Res> resources, List<Payload> payloads) {

    public record Res(String type, String id, String fullUrl) {
    }

    /** One ARex content object: the original bytes live under the facade's data directory. */
    public record Payload(String entryId, String contentType, long size, String sha256) {
    }
}
