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
        JsonNode manifest = first(resources, "List", "arex-manifest");
        List<JsonNode> contentEntries = all(resources, "DocumentReference", "arex-content-entry");
        String referralId = referralId(resources);
        List<ArexClient.Finding> findings = new ArrayList<>();

        if (manifest == null || contentEntries.isEmpty()) {
            findings.add(new ArexClient.Finding("NO_AREX_MANIFEST", "F29", "The payload carries no ARex manifest with content entries; nothing to pull."));
        } else {
            inspectEmbedded(manifest, resources, findings, ourOrganization(useCase));
        }

        // Store exactly what was received. A HAPI refusal throws (502) and nothing else happens, except
        // for one named case: see storeReportingSubscriptions.
        int stored = storeReportingSubscriptions(body, bundle, findings);

        String receiptId = UUID.randomUUID().toString();
        List<Receipt.ReceiptObject> objects = new ArrayList<>();
        ArexClient.Outcome outcome = null;
        if (manifest != null && !contentEntries.isEmpty()) {
            outcome = pullAndRecord(receiptId, manifest, contentEntries, objects, findings);
        }

        Receipt receipt = new Receipt(receiptId, clock.instant(), referralId, manifest == null ? null : manifest.path("id").asText(null),
                true, stored, outcome == null ? null : outcome.arexBase(), outcome == null ? null : outcome.tokenEndpoint(),
                outcome == null ? null : outcome.authorizationDetailsType(), outcome == null ? null : outcome.stoppedBecause(),
                outcome == null ? List.of() : outcome.steps(), objects, findings, useCase.id());
        store.saveReceipt(receipt);
        return receipt;
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
            List<Receipt.ReceiptObject> objects, List<ArexClient.Finding> findings) {
        List<ArexClient.ContentRef> refs = new ArrayList<>();
        Map<String, JsonNode> attachments = new HashMap<>();
        Map<String, String> fileKeys = new HashMap<>();
        Set<String> usedKeys = new HashSet<>();
        for (JsonNode entry : contentEntries) {
            String entryId = entry.path("id").asText("");
            JsonNode att = entry.path("content").path(0).path("attachment");
            String digest = declaredSha256(att);
            if (entryId.isEmpty() || !att.path("url").isTextual() || digest == null) {
                findings.add(new ArexClient.Finding("ENTRY_INCOMPLETE", "arex-3, arex-4",
                        "Content entry '" + entryId + "' has no id, no absolute url or no sha-256 digest; it was not pulled."));
                objects.add(new Receipt.ReceiptObject(entryId, null, att.path("url").asText(null), att.path("contentType").asText(null),
                        att.path("size").asLong(-1), digest, "incomplete", 0, null, null, false));
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
        ArexClient.Outcome outcome = arex.pull(manifest.path("id").asText(), manifest, refs, (entryId, bytes, type) -> {
            store.saveImage(receiptId, fileKeys.get(entryId), bytes);
            sniffed.put(entryId, ImageSniffer.sniff(bytes));
        });
        findings.addAll(outcome.findings());
        Map<String, ArexClient.ObjectResult> byEntry = new HashMap<>();
        outcome.objects().forEach(r -> byEntry.put(r.entryId(), r));
        for (ArexClient.ContentRef ref : refs) {
            ArexClient.ObjectResult r = byEntry.get(ref.entryId());
            if (r == null) { // the pull stopped before this object
                objects.add(new Receipt.ReceiptObject(ref.entryId(), fileKeys.get(ref.entryId()), ref.url(), ref.contentType(), ref.size(),
                        ref.sha256(), "not-pulled", 0, null, null, false));
                continue;
            }
            String type = sniffed.get(ref.entryId());
            boolean saved = "saved".equals(r.status());
            objects.add(new Receipt.ReceiptObject(ref.entryId(), fileKeys.get(ref.entryId()), ref.url(), ref.contentType(), ref.size(),
                    ref.sha256(), r.status(), r.bytes(), r.sha256(), saved ? type : null, saved && type != null));
        }
        return outcome;
    }

    // ------------------------------------------------------------------ findings about the embedded manifest

    private void inspectEmbedded(JsonNode manifest, List<JsonNode> resources, List<ArexClient.Finding> findings, String ourOrganization) {
        ManifestFacts facts = ManifestFacts.of(manifest);
        if (facts.expires() != null) {
            try {
                if (!Instant.parse(facts.expires()).isAfter(clock.instant())) {
                    findings.add(new ArexClient.Finding("MANIFEST_EXPIRED_PER_PAYLOAD", "arex-26",
                            "The manifest declares an expiry of " + facts.expires() + ", which has passed. The pull is still attempted; the sender decides."));
                }
            } catch (java.time.format.DateTimeParseException e) {
                findings.add(new ArexClient.Finding("MANIFEST_EXPIRY_UNREADABLE", "arex-30", "The manifest's arex-expires is not a valid instant: " + facts.expires()));
            }
        } else {
            findings.add(new ArexClient.Finding("MANIFEST_EXPIRY_MISSING", "arex-30", "The manifest declares no arex-expires."));
        }
        if (facts.requestingOrganization() == null || !facts.requestingOrganization().equals(ourOrganization)) {
            findings.add(new ArexClient.Finding("REQUESTING_ORG_MISMATCH", "arex-25",
                    "The manifest's requesting organization is '" + facts.requestingOrganization() + "', ours is '" + ourOrganization + "'. The pull is still attempted; the sender decides."));
        }
        findings.add(signatureFinding(manifest, resources));
    }

    /**
     * arex-8 says a consumer shall verify the manifest signature. We do NOT: the draft does not define
     * the canonical form, the key source or the trust anchor. So we say exactly what we saw and never
     * skip it silently.
     */
    private ArexClient.Finding signatureFinding(JsonNode manifest, List<JsonNode> resources) {
        String target = "List/" + manifest.path("id").asText();
        for (JsonNode p : resources) {
            if (!"Provenance".equals(p.path("resourceType").asText()) || !hasProfile(p, "arex-provenance")) {
                continue;
            }
            boolean targetsManifest = false;
            for (JsonNode t : p.path("target")) {
                targetsManifest |= target.equals(t.path("reference").asText());
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
                        "The manifest's Provenance signature is empty, a placeholder or not a compact JWS, so it cannot be verified.");
            }
            return new ArexClient.Finding("SIGNATURE_NOT_VERIFIED", "arex-8",
                    "A signature is present but was not verified: the draft does not define the canonical form, the key source or the trust anchor.");
        }
        return new ArexClient.Finding("SIGNATURE_NOT_VERIFIED", "arex-5, arex-8", "No ARex Provenance with a signature targets the manifest.");
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

    private static JsonNode first(List<JsonNode> resources, String type, String profile) {
        List<JsonNode> found = all(resources, type, profile);
        return found.isEmpty() ? null : found.get(0);
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
