package org.ohia.facade.send;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.ohia.facade.common.BadDataException;

/**
 * Turns the authored UC07 I1 package into one send's own copy.
 *
 * <p>Every send is a separate referral, so the copy gets a fresh referral id and fresh ids for
 * the interaction-scoped resources (the referral, task, imaging, notes, communication, provenance
 * and the ARex manifest and entries). The stable entities (organizations, practitioners, patient,
 * coverage and so on) keep their authored ids, because every send is about the same people.
 * ARex content URLs point at this server with opaque random ids, the manifest is given a fresh
 * expiry, and its requesting organization is the registered tester's.
 */
public final class PerSendCopier {

    static final String ARex = "http://fhir.onyxhealth.io/us/arex/StructureDefinition/";
    static final String REFERRAL_ID_SYSTEM = "urn:ohia:referral-id";

    /** Resource types that belong to one interaction (everything else is stable background data). */
    static final Set<String> INTERACTION_TYPES = Set.of("Encounter", "Condition", "Observation", "Procedure",
            "ImagingStudy", "DiagnosticReport", "DocumentReference", "ServiceRequest", "Task", "Communication",
            "Provenance", "List");

    public record Entry(String originalEntryId, String binaryId, String contentType, long size, String sha256) {
    }

    public record Copy(ObjectNode bundle, String referralId, String manifestId, ObjectNode manifest,
            List<Entry> entries) {
    }

    private PerSendCopier() {
    }

    public static Copy copy(ObjectNode readBack, String sendShort, String publicBaseUrl, String requestingOrganization,
            Instant expires, Supplier<String> opaqueIds) {
        ObjectNode bundle = readBack.deepCopy();
        String suffix = "-" + sendShort;

        // The manifest names the retrieving organization by reference, so that organization must be
        // part of this package; otherwise the receiver would get a reference to nothing.
        boolean organizationPresent = false;
        for (JsonNode e : bundle.get("entry")) {
            JsonNode r = e.get("resource");
            if ((r.get("resourceType").asText() + "/" + r.get("id").asText()).equals(requestingOrganization)) {
                organizationPresent = true;
            }
        }
        if (!organizationPresent) {
            throw new BadDataException("The registered organization " + requestingOrganization
                    + " is not in the package being sent, so the sent manifest would point at nothing. Fix the registry entry.");
        }

        // 1. which resources get new ids
        Map<String, String> renamed = new HashMap<>(); // "Type/oldId" -> "Type/newId"
        String originalReferralId = null;
        for (JsonNode e : bundle.get("entry")) {
            JsonNode r = e.get("resource");
            String type = r.get("resourceType").asText();
            String id = r.get("id").asText();
            if (INTERACTION_TYPES.contains(type)) {
                renamed.put(type + "/" + id, type + "/" + id + suffix);
            }
            if (originalReferralId == null) {
                originalReferralId = findReferralId(r);
            }
        }
        String newReferralId = originalReferralId == null ? null : originalReferralId + suffix;

        // 2. rewrite ids, references and the referral id everywhere
        for (JsonNode e : bundle.get("entry")) {
            ObjectNode entry = (ObjectNode) e;
            ObjectNode resource = (ObjectNode) entry.get("resource");
            String key = resource.get("resourceType").asText() + "/" + resource.get("id").asText();
            String newKey = renamed.get(key);
            if (newKey != null) {
                resource.put("id", newKey.substring(newKey.indexOf('/') + 1));
                ((ObjectNode) entry.get("request")).put("url", newKey);
                entry.put("fullUrl", "urn:uuid:" + UUID.randomUUID());
            }
            rewrite(resource, renamed, originalReferralId, newReferralId);
        }

        // 3. ARex: manifest expiry, requesting organization, identifier, content URLs
        ObjectNode manifest = null;
        List<Entry> entries = new ArrayList<>();
        for (JsonNode e : bundle.get("entry")) {
            ObjectNode r = (ObjectNode) e.get("resource");
            if (hasProfile(r, "arex-manifest")) {
                manifest = r;
                prepareManifest(r, requestingOrganization, expires, sendShort);
            }
        }
        for (JsonNode e : bundle.get("entry")) {
            ObjectNode r = (ObjectNode) e.get("resource");
            if (hasProfile(r, "arex-content-entry")) {
                entries.add(rewriteContentEntry(r, suffix, publicBaseUrl, opaqueIds.get()));
            }
        }
        if (manifest == null || entries.isEmpty()) {
            throw new IllegalStateException("The package has no ARex manifest and content entries.");
        }
        return new Copy(bundle, newReferralId, manifest.get("id").asText(), manifest, entries);
    }

    private static void prepareManifest(ObjectNode manifest, String requestingOrganization, Instant expires,
            String sendShort) {
        for (JsonNode ext : manifest.path("extension")) {
            String url = ext.path("url").asText();
            if ((ARex + "arex-expires").equals(url)) {
                ((ObjectNode) ext).put("valueInstant", expires.truncatedTo(ChronoUnit.SECONDS).toString());
            } else if ((ARex + "arex-transaction-context").equals(url)) {
                for (JsonNode part : ext.path("extension")) {
                    if ("requestingOrganization".equals(part.path("url").asText())) {
                        ((ObjectNode) part.get("valueReference")).put("reference", requestingOrganization);
                    }
                }
            }
        }
        for (JsonNode id : manifest.path("identifier")) {
            ((ObjectNode) id).put("value", id.path("value").asText() + "-" + sendShort);
        }
    }

    private static Entry rewriteContentEntry(ObjectNode resource, String suffix, String publicBaseUrl, String binaryId) {
        String originalId = resource.get("id").asText();
        if (originalId.endsWith(suffix)) {
            originalId = originalId.substring(0, originalId.length() - suffix.length());
        }
        ObjectNode attachment = (ObjectNode) resource.path("content").get(0).get("attachment");
        attachment.put("url", publicBaseUrl + "/arex/Binary/" + binaryId);
        String sha = null;
        for (JsonNode ext : attachment.path("extension")) {
            if ((ARex + "arex-digest").equals(ext.path("url").asText())) {
                for (JsonNode part : ext.path("extension")) {
                    if ("value".equals(part.path("url").asText())) {
                        sha = part.path("valueString").asText();
                    }
                }
            }
        }
        return new Entry(originalId, binaryId, attachment.path("contentType").asText(), attachment.path("size").asLong(), sha);
    }

    private static void rewrite(JsonNode node, Map<String, String> renamed, String oldRef, String newRef) {
        if (node instanceof ObjectNode obj) {
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                JsonNode v = f.getValue();
                if (v.isTextual()) {
                    String s = v.asText();
                    if (f.getKey().equals("reference") && renamed.containsKey(s)) {
                        f.setValue(TextNode.valueOf(renamed.get(s)));
                    } else if (oldRef != null && s.contains(oldRef)) {
                        f.setValue(TextNode.valueOf(s.replace(oldRef, newRef)));
                    }
                } else {
                    rewrite(v, renamed, oldRef, newRef);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode v = arr.get(i);
                if (v.isTextual() && oldRef != null && v.asText().contains(oldRef)) {
                    arr.set(i, TextNode.valueOf(v.asText().replace(oldRef, newRef)));
                } else {
                    rewrite(v, renamed, oldRef, newRef);
                }
            }
        }
    }

    private static String findReferralId(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            if (REFERRAL_ID_SYSTEM.equals(obj.path("system").asText()) && obj.hasNonNull("value")) {
                return obj.get("value").asText();
            }
            for (JsonNode child : obj) {
                String found = findReferralId(child);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                String found = findReferralId(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean hasProfile(JsonNode resource, String name) {
        for (JsonNode p : resource.path("meta").path("profile")) {
            if (p.asText().equals(ARex + name)) {
                return true;
            }
        }
        return false;
    }
}
