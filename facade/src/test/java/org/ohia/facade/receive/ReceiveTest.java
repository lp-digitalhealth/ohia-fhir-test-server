package org.ohia.facade.receive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ohia.facade.Uc04aFixture;
import org.ohia.facade.UcStubs;
import org.ohia.facade.arexclient.ArexSenderStub;
import org.ohia.facade.keys.ServerKeyStore;
import org.ohia.facade.seed.SeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Receive a UC04a payload and pull its images, end to end inside one JVM: the real UC04a bundle goes in
 * through the endpoint, HAPI is a stand-in, and the sender is a stand-in ARex server that really verifies
 * our signed assertion. The test-only network flags are on so the sender can be on 127.0.0.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReceiveTest {

    static final String S1 = "unit-test-token-123";
    static final Path dataDir = tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("facade.api-token", () -> S1);
        r.add("facade.data-dir", dataDir::toString);
        r.add("facade.hapi-base-url", UcStubs::hapiBaseUrl);
        r.add("facade.public-base-url", () -> "https://facade.test");
        r.add("facade.clients-file", () -> dataDir.resolve("clients.json").toString());
        r.add("facade.send.allow-http", () -> "true");
        r.add("facade.send.allow-private-targets", () -> "true");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ServerKeyStore keys;

    ArexSenderStub sender;
    ObjectNode bundle;

    @BeforeEach
    void setUp() throws IOException {
        UcStubs.reset();
        Path received = dataDir.resolve("received");
        if (Files.exists(received)) {
            try (Stream<Path> s = Files.walk(received)) {
                s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
        bundle = Uc04aFixture.bundle();
        sender = new ArexSenderStub(keys.publicJwks(), "ohia-test-server", Uc04aFixture.manifest(bundle), Uc04aFixture.photos());
        Uc04aFixture.refs(bundle, sender.base()); // points the payload's content URLs at the stand-in sender
    }

    @AfterEach
    void tearDown() {
        sender.stop();
    }

    private ResultActions receive(String body) throws Exception {
        return mvc.perform(post("/ode/receive/uc04a").header("Authorization", "Bearer " + S1).contentType("application/fhir+json").content(body));
    }

    private JsonNode receiveOk(String body) throws Exception {
        MvcResult r = receive(body).andExpect(status().isOk()).andReturn();
        return UcStubs.M.readTree(r.getResponse().getContentAsString());
    }

    private static Set<String> codes(JsonNode receipt) {
        Set<String> out = new HashSet<>();
        receipt.get("findings").forEach(f -> out.add(f.get("code").asText()));
        return out;
    }

    private static List<String> statuses(JsonNode receipt) {
        return java.util.stream.StreamSupport.stream(receipt.get("objects").spliterator(), false).map(o -> o.get("status").asText()).toList();
    }

    // ------------------------------------------------------------------ the whole thing

    @Test
    void receivesThePayloadPullsBothImagesAndReportsExactlyTheExpectedFindings() throws Exception {
        String payload = bundle.toString();
        JsonNode receipt = receiveOk(payload);

        assertTrue(receipt.get("accepted").asBoolean());
        assertEquals(30, receipt.get("hapiEntries").asInt());
        assertEquals("REF-2026-UC04A-001", receipt.get("referralId").asText());
        assertEquals("arex-manifest-intraoral-photos", receipt.get("manifestId").asText());
        assertEquals(sender.base() + "/arex", receipt.get("sender").asText());
        assertEquals(List.of("saved", "saved"), statuses(receipt));
        assertTrue(receipt.get("objects").get(0).get("viewable").asBoolean());
        // the data's own gaps, named: a past expiry and a placeholder signature, and nothing else
        assertEquals(Set.of("MANIFEST_EXPIRED_PER_PAYLOAD", "SIGNATURE_PLACEHOLDER"), codes(receipt));

        // HAPI got the payload exactly as received
        assertEquals(1, UcStubs.posts.get());
        assertEquals(payload, UcStubs.lastPost.get());
        // the sender really verified our assertion with the key we publish
        assertEquals(1, sender.tokensIssued.get());
        assertEquals(0, sender.assertionsRejected.get());
    }

    @Test
    void thePulledImagesAreServedBackByteForByteAndInline() throws Exception {
        JsonNode receipt = receiveOk(bundle.toString());
        String id = receipt.get("id").asText();
        Map<String, byte[]> originals = Uc04aFixture.photos();
        for (JsonNode o : receipt.get("objects")) {
            MvcResult r = mvc.perform(get("/ode/receipts/" + id + "/images/" + o.get("fileKey").asText()).header("Authorization", "Bearer " + S1))
                    .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/jpeg"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Content-Disposition", "inline")).andReturn();
            assertArrayEquals(originals.get(o.get("entryId").asText()), r.getResponse().getContentAsByteArray());
        }
        mvc.perform(get("/ode/receipts").header("Authorization", "Bearer " + S1)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/ode/receipts/" + id).header("Authorization", "Bearer " + S1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.referralId").value("REF-2026-UC04A-001"));
    }

    // ------------------------------------------------------------------ integrity

    /** Control: a tampered image is reported and never retrievable; the other image is unaffected. */
    @Test
    void aTamperedImageIsNeverStoredOrServed() throws Exception {
        sender.tamper = true;
        JsonNode receipt = receiveOk(bundle.toString());
        assertEquals(List.of("digest-mismatch", "digest-mismatch"), statuses(receipt));
        assertTrue(codes(receipt).contains("DIGEST_MISMATCH"));
        assertFalse(receipt.get("objects").get(0).get("viewable").asBoolean());
        mvc.perform(get("/ode/receipts/" + receipt.get("id").asText() + "/images/" + receipt.get("objects").get(0).get("fileKey").asText())
                .header("Authorization", "Bearer " + S1)).andExpect(status().isNotFound());
        assertTrue(receipt.get("accepted").asBoolean(), "the payload itself was still accepted and stored");
    }

    /** Control: bytes that are not an image are stored but must never be shown as a page or an image. */
    @Test
    void aSenderThatServesHtmlAsAnImageIsStoredButOnlyEverOfferedAsADownload() throws Exception {
        byte[] html = "<html><script>alert(document.cookie)</script></html>".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> photos = new HashMap<>(Uc04aFixture.photos());
        photos.put("arex-entry-photo-1", html);
        sender.stop();
        sender = new ArexSenderStub(keys.publicJwks(), "ohia-test-server", Uc04aFixture.manifest(bundle), photos);
        Uc04aFixture.refs(bundle, sender.base());
        // the attacker also declares matching size and digest, so the pull accepts the object
        for (ObjectNode entry : Uc04aFixture.contentEntries(bundle)) {
            if (entry.get("id").asText().equals("arex-entry-photo-1")) {
                ObjectNode att = (ObjectNode) entry.get("content").get(0).get("attachment");
                att.put("size", html.length);
                for (JsonNode ext : att.get("extension")) {
                    for (JsonNode part : ext.get("extension")) {
                        if (part.get("url").asText().equals("value")) {
                            ((ObjectNode) part).put("valueString", SeedService.sha256(html));
                        }
                    }
                }
            }
        }
        JsonNode receipt = receiveOk(bundle.toString());
        JsonNode bad = receipt.get("objects").get(0);
        assertEquals("saved", bad.get("status").asText());
        assertFalse(bad.get("viewable").asBoolean(), "html must not be viewable");
        mvc.perform(get("/ode/receipts/" + receipt.get("id").asText() + "/images/" + bad.get("fileKey").asText()).header("Authorization", "Bearer " + S1))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        assertTrue(receipt.get("objects").get(1).get("viewable").asBoolean(), "control: the honest image is still viewable");
    }

    // ------------------------------------------------------------------ report-first

    @Test
    void aRefusedTokenIsReportedAndThePayloadIsStillAccepted() throws Exception {
        sender.refuseToken = true;
        JsonNode receipt = receiveOk(bundle.toString());
        assertTrue(receipt.get("accepted").asBoolean());
        assertEquals(1, UcStubs.posts.get());
        assertFalse(receipt.get("stoppedBecause").isNull());
        assertTrue(codes(receipt).contains("TOKEN_REFUSED"));
        assertEquals(List.of("not-pulled", "not-pulled"), statuses(receipt));
    }

    @Test
    void aRetractedPublishedManifestStopsThePull() throws Exception {
        sender.manifestMode = "retracted";
        JsonNode receipt = receiveOk(bundle.toString());
        assertTrue(codes(receipt).contains("MANIFEST_NOT_CURRENT"));
        assertEquals(0, sender.count("GET /arex/Binary/"));
        assertEquals(List.of("not-pulled", "not-pulled"), statuses(receipt));
    }

    @Test
    void aMissingPublishedManifestIsAFindingAndTheImagesAreStillPulled() throws Exception {
        sender.manifestMode = "missing";
        JsonNode receipt = receiveOk(bundle.toString());
        assertTrue(codes(receipt).contains("PUBLISHED_MANIFEST_UNAVAILABLE"));
        assertEquals(List.of("saved", "saved"), statuses(receipt));
    }

    @Test
    void aRequestingOrganizationThatIsNotOursIsAFindingAndThePullGoesOn() throws Exception {
        ObjectNode manifest = Uc04aFixture.manifest(bundle);
        for (JsonNode ext : manifest.get("extension")) {
            if (ext.get("url").asText().endsWith("arex-transaction-context")) {
                for (JsonNode part : ext.get("extension")) {
                    if (part.get("url").asText().equals("requestingOrganization")) {
                        ((ObjectNode) part.get("valueReference")).put("reference", "Organization/org-somebody-else");
                    }
                }
            }
        }
        JsonNode receipt = receiveOk(bundle.toString());
        assertTrue(codes(receipt).contains("REQUESTING_ORG_MISMATCH"));
        assertEquals(List.of("saved", "saved"), statuses(receipt));
    }

    @Test
    void aPayloadWithoutAManifestIsAcceptedAndNothingIsPulled() throws Exception {
        ArrayNode kept = UcStubs.M.createArrayNode();
        for (JsonNode e : bundle.get("entry")) {
            String type = e.get("resource").get("resourceType").asText();
            boolean arexResource = type.equals("List") || e.get("resource").path("meta").path("profile").toString().contains("arex-");
            if (!arexResource) {
                kept.add(e);
            }
        }
        bundle.set("entry", kept);
        JsonNode receipt = receiveOk(bundle.toString());
        assertTrue(receipt.get("accepted").asBoolean());
        assertEquals(Set.of("NO_AREX_MANIFEST"), codes(receipt));
        assertEquals(0, sender.requests.size(), "no sender may be contacted");
        assertEquals(0, receipt.get("objects").size());
    }

    // ------------------------------------------------------------------ refusals before anything is written

    @Test
    void whatIsNotAnInboundBundleIsRefusedAndNothingIsStored() throws Exception {
        for (String bad : new String[] {"not json at all", "{\"resourceType\":\"Patient\"}", "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{}]}",
                "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":[]}", "[1,2,3]"}) {
            receive(bad).andExpect(status().isUnprocessableEntity());
        }
        assertEquals(0, UcStubs.posts.get(), "nothing may reach HAPI");
        assertEquals(0, sender.requests.size());
        mvc.perform(get("/ode/receipts").header("Authorization", "Bearer " + S1)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anOversizedBodyIsRefusedWith413() throws Exception {
        receive("{\"resourceType\":\"Bundle\",\"x\":\"" + "a".repeat(ReceiveService.MAX_BODY_BYTES + 10) + "\"}").andExpect(status().isPayloadTooLarge());
        assertEquals(0, UcStubs.posts.get());
    }

    /** Control: if HAPI refuses the payload, we must not go and pull images for something we did not store. */
    @Test
    void whenHapiRefusesNothingIsPulledAndNoReceiptIsKept() throws Exception {
        UcStubs.postStatus = 400;
        receive(bundle.toString()).andExpect(status().isBadGateway());
        assertEquals(0, sender.requests.size(), "no pull for an unstored payload");
        mvc.perform(get("/ode/receipts").header("Authorization", "Bearer " + S1)).andExpect(jsonPath("$.length()").value(0));
    }

    /** Real HAPI refuses the data's Subscription (HAPI-0011). Report-first: store the rest, name the gap, still pull. */
    @Test
    void whenHapiRefusesOnlyTheSubscriptionTheRestIsStoredTheGapIsNamedAndTheImagesArePulled() throws Exception {
        UcStubs.refuseSubscriptions = true;
        JsonNode receipt = receiveOk(bundle.toString());
        assertTrue(receipt.get("accepted").asBoolean());
        assertEquals(29, receipt.get("hapiEntries").asInt());
        assertEquals(List.of("saved", "saved"), statuses(receipt));
        assertTrue(codes(receipt).contains("SUBSCRIPTION_NOT_STORED"), codes(receipt).toString());
        assertEquals(2, UcStubs.posts.get(), "first as received, then without the Subscription");
        assertFalse(UcStubs.lastPost.get().contains("\"resourceType\":\"Subscription\""));
        assertTrue(UcStubs.hapiStore.containsKey("ServiceRequest/servicerequest-referral"));
    }

    /** Control: a refusal that is not about Subscriptions stays a refusal (the old behaviour). */
    @Test
    void aRefusalWithNoSubscriptionToDropStaysARefusal() throws Exception {
        UcStubs.refuseSubscriptions = true;
        JsonNode b = UcStubs.M.readTree(bundle.toString());
        ((com.fasterxml.jackson.databind.node.ArrayNode) b.get("entry")).removeIf(e -> "Subscription".equals(e.get("resource").get("resourceType").asText()));
        UcStubs.postStatus = 422;
        receive(b.toString()).andExpect(status().isBadGateway());
        assertEquals(1, UcStubs.posts.get());
        assertEquals(0, sender.requests.size());
    }

    // ------------------------------------------------------------------ access

    @Test
    void everyReceiveEndpointNeedsTheSharedToken() throws Exception {
        JsonNode receipt = receiveOk(bundle.toString());
        String id = receipt.get("id").asText();
        String key = receipt.get("objects").get(0).get("fileKey").asText();
        mvc.perform(post("/ode/receive/uc04a").contentType("application/json").content(bundle.toString())).andExpect(status().isUnauthorized());
        mvc.perform(get("/ode/receipts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ode/receipts/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/ode/receipts/" + id + "/images/" + key)).andExpect(status().isUnauthorized());
        mvc.perform(get("/ode/identity")).andExpect(status().isUnauthorized());
        assertEquals(1, UcStubs.posts.get(), "the unauthenticated attempt must not have reached HAPI");
    }

    @Test
    void unknownAndUnsafeIdsAreJustNotFound() throws Exception {
        String id = receiveOk(bundle.toString()).get("id").asText();
        mvc.perform(get("/ode/receipts/00000000-0000-0000-0000-000000000000").header("Authorization", "Bearer " + S1)).andExpect(status().isNotFound());
        mvc.perform(get("/ode/receipts/not-an-id").header("Authorization", "Bearer " + S1)).andExpect(status().isNotFound());
        mvc.perform(get("/ode/receipts/" + id + "/images/nope").header("Authorization", "Bearer " + S1)).andExpect(status().isNotFound());
        mvc.perform(get("/ode/receipts/" + id + "/images/..%2F..%2Freceipt").header("Authorization", "Bearer " + S1)).andExpect(status().is4xxClientError());
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("facade-test-receive");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
