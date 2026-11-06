package org.ohia.facade.arexclient;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The few facts about an ARex manifest that a consumer acts on, read from the manifest {@code List}:
 * its status, its declared expiry, the organization entitled to retrieve it, the business identifier
 * of the transaction it belongs to, and the content entries it indexes.
 */
public record ManifestFacts(String status, String expires, String requestingOrganization, String businessIdentifier,
        SortedSet<String> entries) {

    public static ManifestFacts of(JsonNode manifest) {
        String expires = null;
        String org = null;
        String business = null;
        for (JsonNode ext : manifest.path("extension")) {
            String url = ext.path("url").asText();
            if (url.endsWith("arex-expires")) {
                expires = ext.path("valueInstant").asText(null);
            } else if (url.endsWith("arex-transaction-context")) {
                for (JsonNode part : ext.path("extension")) {
                    String name = part.path("url").asText();
                    if ("requestingOrganization".equals(name)) {
                        org = FhirRefs.normalize(part.path("valueReference").path("reference").asText(null));
                    } else if ("businessIdentifier".equals(name) && business == null) {
                        JsonNode id = part.path("valueIdentifier");
                        business = id.path("system").asText("") + "|" + id.path("value").asText("");
                    }
                }
            }
        }
        SortedSet<String> entries = new TreeSet<>();
        for (JsonNode e : manifest.path("entry")) {
            String ref = e.path("item").path("reference").asText(null);
            if (ref != null) {
                entries.add(FhirRefs.normalize(ref));
            }
        }
        return new ManifestFacts(manifest.path("status").asText(null), expires, org, business, entries);
    }

    /** True if everything a consumer relies on is the same in both copies. */
    public boolean sameAs(ManifestFacts other) {
        return java.util.Objects.equals(expires, other.expires)
                && java.util.Objects.equals(requestingOrganization, other.requestingOrganization)
                && entries.equals(other.entries);
    }
}
