package org.ohia.facade.receive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ohia.facade.arexclient.ArexClient;
import org.ohia.facade.arexclient.FhirRefs;
import org.ohia.facade.arexclient.ManifestFacts;
import org.ohia.facade.common.BadDataException;
import org.ohia.facade.common.UpstreamException;
import org.ohia.facade.common.UseCase;
import org.ohia.facade.config.FacadeProperties;
import org.ohia.facade.config.UseCaseProperties;
import org.ohia.facade.hapi.HapiClient;
import org.springframework.stereotype.Service;

/**
 * Receives a UC04a or UC07 referral (a FHIR transaction Bundle that carries an ARex manifest), stores it,
 * pulls the images with ARex, and records everything as a {@link Receipt}.
 *
 * <p>Order: every refusal comes before anything is written. The payload is stored in HAPI exactly
 * as received; a HAPI refusal ends the request with no pull. Then the pull runs. It is report-first:
 * a gap the draft spec makes us unable to close (an unverifiable signature, a missing published
 * manifest) becomes a named finding and the images are still pulled, but a positive stop signal (a
 * retracted manifest, a refused token) ends the pull. A failed pull is a result, not an error.
 */
@Service
public class ReceiveService {

    public static final int MAX_BODY_BYTES = 10_000_000;
    private static final String ARex = "http://fhir.onyxhealth.io/us/arex/StructureDefinition/";
    private static final String REFERRAL_ID_SYSTEM = "urn:ohia:referral-id";

    private final HapiClient hapi;
    private final ArexClient arex;
    private final ReceiptStore store;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String uc04aOrganization;
    private final String uc07Organization;

    public ReceiveService(HapiClient hapi, ArexClient arex, ReceiptStore store, ObjectMapper mapper, Clock clock,
            FacadeProperties props, UseCaseProperties useCases) {
        this.hapi = hapi;
        this.arex = arex;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
        this.uc04aOrganization = props.client().requestingOrganization();
        this.uc07Organization = useCases.receiving().uc07RequestingOrganization();
    }

    public Receipt receive(String body) {
        return receive(UseCase.UC04A, body);
    }

    /** The organization we play when this use case's referral is sent to us (and so the one we retrieve as). */
    private String ourOrganization(UseCase useCase) {
        return useCase == UseCase.UC07 ? uc07Organization : uc04aOrganization;
    }

    public Receipt receive(UseCase useCase, String body) {
        JsonNode bundle = parseBundle(body);
        List<JsonNode> resources = new ArrayList<>();
        for (JsonNode e : bundle.get("entry")) {
            if (e.path("resource").isObject()) {
                resources.add(e.get("resource"));
            }
        }
        List<JsonNode> manifests = all(resources, "List", "arex-manifest");
        List<JsonNode> contentEntries = all(resources, "DocumentReference", "arex-content-entry");
        String referralId = referralId(resources);
        List<ArexClient.Finding> findings = new ArrayList<>();
        boolean several = manifests.size() > 1;

        if (manifests.isEmpty() || contentEntries.isEmpty()) {
            findings.add(new ArexClient.Finding("NO_AREX_MANIFEST", "F29", "The payload carries no ARex manifest with content entries; nothing to pull."));
        } else {
            for (JsonNode manifest : manifests) {
                inspectEmbedded(manifest, resources, findings, ourOrganization(useCase), several);
            }
        }

        // Store exactly what was received. A HAPI refusal throws (502) and nothing else happens, except
        // for one named case: see storeReportingSubscriptions.
        int stored = storeReportingSubscriptions(body, bundle, findings);

        String receiptId = UUID.randomUUID().toString();
        List<Receipt.ReceiptObject> objects = new ArrayList<>();
        List<ArexClient.Step> steps = new ArrayList<>();
        List<String> stops = new ArrayList<>();
        String arexBase = null;
        String tokenEndpoint = null;
        String adType = null;
        Set<String> usedKeys = new HashSet<>();
        if (!manifests.isEmpty() && !contentEntries.isEmpty()) {
            for (Map.Entry<JsonNode, List<JsonNode>> group : entriesByManifest(manifests, contentEntries, findings, objects, usedKeys).entrySet()) {
                String id = group.getKey().path("id").asText();
                String tag = several ? "[" + id + "] " : "";
                ArexClient.Outcome o = pullAndRecord(receiptId, group.getKey(), group.getValue(), objects, findings, usedKeys);
                arexBase = arexBase == null ? o.arexBase() : arexBase;
                tokenEndpoint = tokenEndpoint == null ? o.tokenEndpoint() : tokenEndpoint;
                adType = adType == null ? o.authorizationDetailsType() : adType;
                for (ArexClient.Step st : o.steps()) {
                    steps.add(new ArexClient.Step(tag + st.name(), st.ok(), st.detail()));
                }
                if (o.stoppedBecause() != null) {
                    stops.add(tag + o.stoppedBecause());
                }
            }
        }

        String manifestIds = manifests.isEmpty() ? null : String.join(", ", manifests.stream().map(m -> m.path("id").asText()).toList());
        Receipt receipt = new Receipt(receiptId, clock.instant(), referralId, manifestIds, true, stored, arexBase, tokenEndpoint, adType,
                stops.isEmpty() ? null : String.join(" | ", stops), steps, objects, findings, useCase.id());
        store.saveReceipt(receipt);
        return receipt;
    }

    /**
     * Which content entries belong to which manifest. One manifest owns every entry (as before). With several, each
     * manifest owns the entries its own list names (item references, relative or absolute); an entry no manifest names
     * is reported and not pulled, because pulling it needs a token for a manifest that covers it.
     */
    private Map<JsonNode, List<JsonNode>> entriesByManifest(List<JsonNode> manifests, List<JsonNode> contentEntries,
            List<ArexClient.Finding> findings, List<Receipt.ReceiptObject> objects, Set<String> usedKeys) {
        Map<JsonNode, List<JsonNode>> groups = new java.util.LinkedHashMap<>();
        if (manifests.size() == 1) {
            groups.put(manifests.get(0), contentEntries);
            return groups;
        }
        Set<String> claimed = new HashSet<>();
        for (JsonNode manifest : manifests) {
            Set<String> named = ManifestFacts.of(manifest).entries();
            List<JsonNode> mine = new ArrayList<>();
            for (JsonNode entry : contentEntries) {
                String ref = "DocumentReference/" + entry.path("id").asText();
                if (named.contains(ref)) {
                    mine.add(entry);
                    claimed.add(ref);
                }
            }
            if (!mine.isEmpty()) {
                groups.put(manifest, mine);
            }
        }
        for (JsonNode entry : contentEntries) {
            if (!claimed.contains("DocumentReference/" + entry.path("id").asText())) {
                JsonNode att = entry.path("content").path(0).path("attachment");
                findings.add(new ArexClient.Finding("ENTRY_NOT_IN_A_MANIFEST", "arex-3",
                        "Content entry '" + entry.path("id").asText() + "' is not listed by any of the payload's " + manifests.size()
                                + " manifests, so it has no token to pull it with and was not pulled."));
                objects.add(new Receipt.ReceiptObject(entry.path("id").asText(""), null, att.path("url").asText(null),
                        att.path("contentType").asText(null), att.path("size").asLong(-1), declaredSha256(att), "not-pulled", 0, null, null, false, null));
            }
        }
        return groups;
    }

    /**
     * Stores the payload as received. Real HAPI refuses a Subscription that lacks the backport
     * filter-criteria extension (HAPI-0011), which the UC04a data does not carry. Report-first: when
     * HAPI refuses a payload that holds Subscriptions, store it again WITHOUT those resources, say so
     * as a finding, and go on. If HAPI still refuses, that refusal is final (502).
     *
     * @return the number of entries stored
     */
    private int storeReportingSubscriptions(String body, JsonNode bundle, List<ArexClient.Finding> findings) {
        try {
            hapi.postTransaction(body);
            return bundle.get("entry").size();
        } catch (UpstreamException refused) {
            int status = refused.upstreamStatus();
            if (status < 400 || status >= 500) {
                throw refused;
            }
            com.fasterxml.jackson.databind.node.ArrayNode kept = mapper.createArrayNode();
            int dropped = 0;
            for (JsonNode e : bundle.get("entry")) {
                if ("Subscription".equals(e.path("resource").path("resourceType").asText())) {
                    dropped++;
                } else {
                    kept.add(e);
                }
            }
            if (dropped == 0 || kept.isEmpty()) {
                throw refused;
            }
            com.fasterxml.jackson.databind.node.ObjectNode reduced = ((com.fasterxml.jackson.databind.node.ObjectNode) bundle).deepCopy();
            reduced.set("entry", kept);
            hapi.postTransaction(reduced.toString());
            findings.add(new ArexClient.Finding("SUBSCRIPTION_NOT_STORED", "F29, O9",
                    "HAPI refused the payload as sent (" + refused.getMessage().replaceAll("\\s+", " ")
                            + "). It was stored again without its " + dropped + " Subscription resource(s); everything else is stored as received."));
            return kept.size();
        }
    }

    // ------------------------------------------------------------------ the pull

    private ArexClient.Outcome pullAndRecord(String receiptId, JsonNode manifest, List<JsonNode> contentEntries,
            List<Receipt.ReceiptObject> objects, List<ArexClient.Finding> findings, Set<String> usedKeys) {
        String manifestId = manifest.path("id").asText(null);
        List<ArexClient.ContentRef> refs = new ArrayList<>();
        Map<String, JsonNode> attachments = new HashMap<>();
        Map<String, String> fileKeys = new HashMap<>();
        for (JsonNode entry : contentEntries) {
            String entryId = entry.path("id").asText("");
            JsonNode att = entry.path("content").path(0).path("attachment");
            String digest = declaredSha256(att);
            if (entryId.isEmpty() || !att.path("url").isTextual() || digest == null) {
                findings.add(new ArexClient.Finding("ENTRY_INCOMPLETE", "arex-3, arex-4",
                        "Content entry '" + entryId + "' has no id, no absolute url or no sha-256 digest; it was not pulled."));
                objects.add(new Receipt.ReceiptObject(entryId, null, att.path("url").asText(null), att.path("contentType").asText(null),
                        att.path("size").asLong(-1), digest, "incomplete", 0, null, null, false, manifestId));
                continue;
            }
            String key = ReceiptStore.safeEntryName(entryId);
            for (int n = 2; !usedKeys.add(key); n++) {
                key = ReceiptStore.safeEntryName(entryId) + "-" + n;
            }
            if (!key.equals(entryId)) {
                findings.add(new ArexClient.Finding("ENTRY_ID_SANITIZED", "local safety rule",
                        "Entry id '" + entryId + "' is not a safe file name; stored as '" + key + "'."));
            }
            fileKeys.put(entryId, key);
            attachments.put(entryId, att);
            refs.add(new ArexClient.ContentRef(entryId, att.path("url").asText(), att.path("contentType").asText(null),
                    att.path("size").asLong(-1), digest));
        }
        Map<String, String> sniffed = new HashMap<>();
        ArexClient.Outcome outcome = arex.pull(manifestId, manifest, refs, (entryId, bytes, type) -> {
            store.saveImage(receiptId, fileKeys.get(entryId), bytes);
            sniffed.put(entryId, ImageSniffer.sniff(bytes));
        });
        for (ArexClient.Finding f : outcome.findings()) {
            if (findings.stream().noneMatch(x -> x.code().equals(f.code()) && x.message().equals(f.message()))) {
                findings.add(f);
            }
        }
        Map<String, ArexClient.ObjectResult> byEntry = new HashMap<>();
        outcome.objects().forEach(r -> byEntry.put(r.entryId(), r));
        for (ArexClient.ContentRef ref : refs) {
            ArexClient.ObjectResult r = byEntry.get(ref.entryId());
            if (r == null) { // the pull stopped before this object
                objects.add(new Receipt.ReceiptObject(ref.entryId(), fileKeys.get(ref.entryId()), ref.url(), ref.contentType(), ref.size(),
                        ref.sha256(), "not-pulled", 0, null, null, false, manifestId));
                continue;
            }
            String type = sniffed.get(ref.entryId());
            boolean saved = "saved".equals(r.status());
            objects.add(new Receipt.ReceiptObject(ref.entryId(), fileKeys.get(ref.entryId()), ref.url(), ref.contentType(), ref.size(),
                    ref.sha256(), r.status(), r.bytes(), r.sha256(), saved ? type : null, saved && type != null, manifestId));
        }
        return outcome;
    }

    // ------------------------------------------------------------------ findings about the embedded manifest

    private void inspectEmbedded(JsonNode manifest, List<JsonNode> resources, List<ArexClient.Finding> findings, String ourOrganization,
            boolean several) {
        ManifestFacts facts = ManifestFacts.of(manifest);
        String who = several ? "Manifest " + manifest.path("id").asText() + ": " : "";
        if (facts.expires() != null) {
            try {
                if (!Instant.parse(facts.expires()).isAfter(clock.instant())) {
                    findings.add(new ArexClient.Finding("MANIFEST_EXPIRED_PER_PAYLOAD", "arex-26",
                            who + "The manifest declares an expiry of " + facts.expires() + ", which has passed. The pull is still attempted; the sender decides."));
                }
            } catch (java.time.format.DateTimeParseException e) {
                findings.add(new ArexClient.Finding("MANIFEST_EXPIRY_UNREADABLE", "arex-30", who + "The manifest's arex-expires is not a valid instant: " + facts.expires()));
            }
        } else {
            findings.add(new ArexClient.Finding("MANIFEST_EXPIRY_MISSING", "arex-30", who + "The manifest declares no arex-expires."));
        }
        if (!FhirRefs.same(facts.requestingOrganization(), ourOrganization)) {
            findings.add(new ArexClient.Finding("REQUESTING_ORG_MISMATCH", "arex-25",
                    who + "The manifest's requesting organization is '" + facts.requestingOrganization() + "', ours is '" + ourOrganization + "'. The pull is still attempted; the sender decides."));
        }
        findings.add(signatureFinding(manifest, resources, who));
    }

    /**
     * arex-8 says a consumer shall verify the manifest signature. We do NOT: the draft does not define
     * the canonical form, the key source or the trust anchor. So we say exactly what we saw and never
     * skip it silently.
     */
    private ArexClient.Finding signatureFinding(JsonNode manifest, List<JsonNode> resources, String who) {
        String target = "List/" + manifest.path("id").asText();
        for (JsonNode p : resources) {
            if (!"Provenance".equals(p.path("resourceType").asText()) || !hasProfile(p, "arex-provenance")) {
                continue;
            }
            boolean targetsManifest = false;
            for (JsonNode t : p.path("target")) {
                targetsManifest |= FhirRefs.same(target, t.path("reference").asText());
            }
            if (!targetsManifest) {
                continue;
            }
            String data = p.path("signature").path(0).path("data").asText("");
            String text = "";
            try {
                text = new String(Base64.getDecoder().decode(data), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // not base64: treated below as not a JWS
            }
            boolean compactJws = data.length() > 0 && text.chars().filter(c -> c == '.').count() == 2 && !text.contains(" ");
            if (text.toUpperCase().contains("PLACEHOLDER") || text.isBlank() || !compactJws) {
                return new ArexClient.Finding("SIGNATURE_PLACEHOLDER", "arex-8",
                        who + "The manifest's Provenance signature is empty, a placeholder or not a compact JWS, so it cannot be verified.");
            }
            return new ArexClient.Finding("SIGNATURE_NOT_VERIFIED", "arex-8",
                    who + "A signature is present but was not verified: the draft does not define the canonical form, the key source or the trust anchor.");
        }
        return new ArexClient.Finding("SIGNATURE_NOT_VERIFIED", "arex-5, arex-8", who + "No ARex Provenance with a signature targets the manifest.");
    }

    // ------------------------------------------------------------------ parsing helpers

    private JsonNode parseBundle(String body) {
        JsonNode n;
        try {
            n = mapper.readTree(body);
        } catch (IOException e) {
            throw new BadDataException("The body is not valid JSON.");
        }
        if (n == null || !n.isObject() || !"Bundle".equals(n.path("resourceType").asText())
                || !"transaction".equals(n.path("type").asText()) || !n.path("entry").isArray() || n.get("entry").isEmpty()) {
            throw new BadDataException("The body must be a FHIR Bundle of type transaction with at least one entry.");
        }
        return n;
    }

    private static List<JsonNode> all(List<JsonNode> resources, String type, String profile) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode r : resources) {
            if (type.equals(r.path("resourceType").asText()) && hasProfile(r, profile)) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean hasProfile(JsonNode resource, String name) {
        for (JsonNode p : resource.path("meta").path("profile")) {
            if (p.asText().equals(ARex + name)) {
                return true;
            }
        }
        return false;
    }

    private static String referralId(List<JsonNode> resources) {
        for (JsonNode r : resources) {
            if ("ServiceRequest".equals(r.path("resourceType").asText())) {
                for (JsonNode id : r.path("identifier")) {
                    if (REFERRAL_ID_SYSTEM.equals(id.path("system").asText())) {
                        return id.path("value").asText(null);
                    }
                }
            }
        }
        return null;
    }

    private static String declaredSha256(JsonNode attachment) {
        for (JsonNode ext : attachment.path("extension")) {
            if (ext.path("url").asText().endsWith("arex-digest")) {
                String algorithm = null;
                String value = null;
                for (JsonNode part : ext.path("extension")) {
                    if ("algorithm".equals(part.path("url").asText())) {
                        algorithm = part.path("valueCode").asText();
                    } else if ("value".equals(part.path("url").asText())) {
                        value = part.path("valueString").asText();
                    }
                }
                return "sha-256".equals(algorithm) && value != null && !value.isBlank() ? value.toLowerCase() : null;
            }
        }
        return null;
    }
}
