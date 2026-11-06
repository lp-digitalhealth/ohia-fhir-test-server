package org.ohia.facade.receive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ohia.facade.Uc04aFixture;
import org.ohia.facade.UcStubs;
import org.ohia.facade.arexclient.ArexClient;
import org.ohia.facade.arexclient.ArexSenderStub;
import org.ohia.facade.keys.ServerKeyStore;
import org.ohia.facade.seed.SeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What a real tester's referral showed us: the token must ask for the scope the sender expects; organization and
 * Provenance references may be absolute; and a payload may carry TWO manifests (one for images, one for a PDF), each
 * needing its own token.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReceiveManifestsTest {

    static final String S1 = "unit-test-token-123";
    static final String THEIR_FHIR = "https://their-app.example.org/fhir";
    static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
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

    ObjectNode bundle;
    ArexSenderStub images;     // serves manifest A (photos)
    ArexSenderStub documents;  // serves manifest B (a PDF), on its own origin

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
    }

    @AfterEach
    void tearDown() {
        if (images != null) {
            images.stop();
        }
        if (documents != null) {
            documents.stop();
        }
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("facade-test-manifests");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode receive(String body) throws Exception {
        return UcStubs.M.readTree(mvc.perform(post("/ode/receive/uc04a").header("Authorization", "Bearer " + S1)
                .contentType("application/fhir+json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static boolean has(JsonNode receipt, String code) {
        for (JsonNode f : receipt.get("findings")) {
            if (code.equals(f.get("code").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String findingText(JsonNode receipt) {
        return receipt.get("findings").toString();
    }

    /** One manifest, our default stand-in sender. */
    private void single() {
        images = new ArexSenderStub(keys.publicJwks(), "ohia-test-server", Uc04aFixture.manifest(bundle), Uc04aFixture.photos());
        Uc04aFixture.refs(bundle, images.base());
    }

    // ------------------------------------------------------------------ scope

    @Test
    void theTokenRequestAsksForTheScopeTheSenderExpects() throws Exception {
        single();
        receive(bundle.toString());
        assertEquals(List.of("system/List.r system/DocumentReference.r system/Provenance.r system/Binary.r"), images.scopesRequested);
    }

    // ------------------------------------------------------------------ absolute references

    @Test
    void anAbsoluteRequestingOrganizationIsTheSameOrganizationAsARelativeOne() throws Exception {
        single();
        setOrganization(Uc04aFixture.manifest(bundle), THEIR_FHIR + "/Organization/org-barton-springs-dental");
        assertFalse(has(receive(bundle.toString()), "REQUESTING_ORG_MISMATCH"), "same organization, only written differently");
    }

    /** Control: a genuinely different organization is still reported, however it is written. */
    @Test
    void aDifferentOrganizationIsStillReportedWhetherAbsoluteOrRelative() throws Exception {
        single();
        setOrganization(Uc04aFixture.manifest(bundle), THEIR_FHIR + "/Organization/org-someone-else");
        assertTrue(has(receive(bundle.toString()), "REQUESTING_ORG_MISMATCH"));
    }

    @Test
    void aProvenanceWhoseTargetIsAnAbsoluteReferenceStillTargetsTheManifest() throws Exception {
        single();
        for (JsonNode e : bundle.get("entry")) {
            JsonNode r = e.get("resource");
            if ("Provenance".equals(r.get("resourceType").asText()) && r.get("id").asText().startsWith("arex-provenance")) {
                for (JsonNode t : r.get("target")) {
                    ((ObjectNode) t).put("reference", THEIR_FHIR + "/" + t.get("reference").asText());
                }
            }
        }
        JsonNode receipt = receive(bundle.toString());
        assertTrue(has(receipt, "SIGNATURE_PLACEHOLDER"), "the Provenance was found and its placeholder signature named: " + findingText(receipt));
        assertFalse(findingText(receipt).contains("No ARex Provenance"), findingText(receipt));
    }

    private static void setOrganization(ObjectNode manifest, String reference) {
        for (JsonNode ext : manifest.get("extension")) {
            if (ext.get("url").asText().endsWith("arex-transaction-context")) {
                for (JsonNode part : ext.get("extension")) {
                    if ("requestingOrganization".equals(part.get("url").asText())) {
                        ((ObjectNode) part.get("valueReference")).put("reference", reference);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ two manifests

    /**
     * Manifest A lists the two photos; manifest B lists one PDF (its item reference written ABSOLUTE). They are served by
     * two separate stand-in senders, each with its own token endpoint, so a token for A cannot fetch B.
     */
    private void two() {
        ObjectNode manifestA = Uc04aFixture.manifest(bundle);
        ArrayNode entries = (ArrayNode) bundle.get("entry");
        ObjectNode photoEntry = null;
        ObjectNode manifestWrapper = null;
        for (JsonNode e : entries) {
            JsonNode r = e.get("resource");
            if (r == manifestA) {
                manifestWrapper = (ObjectNode) e;
            }
            if ("DocumentReference".equals(r.get("resourceType").asText()) && "arex-entry-photo-1".equals(r.get("id").asText())) {
                photoEntry = (ObjectNode) e;
            }
        }
        // the PDF content entry: a copy of photo 1 with its own id, type, size and digest
        ObjectNode docWrapper = photoEntry.deepCopy();
        ObjectNode doc = (ObjectNode) docWrapper.get("resource");
        doc.put("id", "arex-entry-doc-1");
        docWrapper.with("request").put("url", "DocumentReference/arex-entry-doc-1");
        docWrapper.put("fullUrl", "urn:uuid:11111111-1111-1111-1111-111111111111");
        ObjectNode att = (ObjectNode) doc.get("content").get(0).get("attachment");
        att.put("contentType", "application/pdf");
        att.put("size", PDF.length);
        for (JsonNode ext : att.get("extension")) {
            for (JsonNode part : ext.get("extension")) {
                if ("value".equals(part.get("url").asText())) {
                    ((ObjectNode) part).put("valueString", SeedService.sha256(PDF));
                }
            }
        }
        entries.add(docWrapper);
        // manifest B: a copy of A, its own id, listing only the PDF (absolute item reference)
        ObjectNode manifestBWrapper = manifestWrapper.deepCopy();
        ObjectNode manifestB = (ObjectNode) manifestBWrapper.get("resource");
        manifestB.put("id", "arex-manifest-documents");
        manifestBWrapper.with("request").put("url", "List/arex-manifest-documents");
        manifestBWrapper.put("fullUrl", "urn:uuid:22222222-2222-2222-2222-222222222222");
        ArrayNode items = manifestB.putArray("entry");
        items.addObject().with("item").put("reference", THEIR_FHIR + "/DocumentReference/arex-entry-doc-1");
        entries.add(manifestBWrapper);
        // manifest A must list only the photos
        ArrayNode itemsA = manifestA.putArray("entry");
        itemsA.addObject().with("item").put("reference", "DocumentReference/arex-entry-photo-1");
        itemsA.addObject().with("item").put("reference", "DocumentReference/arex-entry-photo-2");

        images = new ArexSenderStub(keys.publicJwks(), "ohia-test-server", manifestA, Uc04aFixture.photos());
        Map<String, byte[]> docs = new LinkedHashMap<>();
        docs.put("arex-entry-doc-1", PDF);
        documents = new ArexSenderStub(keys.publicJwks(), "ohia-test-server", manifestB, docs);
        Uc04aFixture.refs(bundle, images.base());                     // points every content entry at the photo sender...
        ((ObjectNode) doc.get("content").get(0).get("attachment")).put("url", documents.base() + "/arex/Binary/arex-entry-doc-1"); // ...except the PDF
    }

    @Test
    void twoManifestsAreEachPulledWithTheirOwnTokenAndBothStayLinkedToTheirObjects() throws Exception {
        two();
        JsonNode receipt = receive(bundle.toString());
        assertEquals("arex-manifest-intraoral-photos, arex-manifest-documents", receipt.get("manifestId").asText());
        assertEquals(1, images.tokensIssued.get(), "one token for the photos' manifest");
        assertEquals(1, documents.tokensIssued.get(), "its own token for the PDF's manifest");
        assertTrue(receipt.get("stoppedBecause").isNull(), String.valueOf(receipt.get("stoppedBecause")));
        assertEquals(3, receipt.get("objects").size());
        for (JsonNode o : receipt.get("objects")) {
            assertEquals("saved", o.get("status").asText(), o.toString());
            boolean pdf = "arex-entry-doc-1".equals(o.get("entryId").asText());
            assertEquals(pdf ? "arex-manifest-documents" : "arex-manifest-intraoral-photos", o.get("manifestId").asText());
            assertEquals(!pdf, o.get("viewable").asBoolean(), "a PDF is saved but never shown inline");
        }
        String steps = receipt.get("steps").toString();
        assertTrue(steps.contains("[arex-manifest-intraoral-photos]") && steps.contains("[arex-manifest-documents]"), "steps say which manifest they belong to");
        assertEquals(List.of("system/List.r system/DocumentReference.r system/Provenance.r system/Binary.r"), documents.scopesRequested);
    }

    @Test
    void thePdfIsServedOnlyAsADownloadAndTheBytesAreIntact() throws Exception {
        two();
        JsonNode receipt = receive(bundle.toString());
        for (JsonNode o : receipt.get("objects")) {
            if ("arex-entry-doc-1".equals(o.get("entryId").asText())) {
                byte[] got = mvc.perform(get("/ode/receipts/" + receipt.get("id").asText() + "/images/" + o.get("fileKey").asText())
                                .header("Authorization", "Bearer " + S1)).andExpect(status().isOk())
                        .andExpect(header().string("Content-Type", "application/octet-stream"))
                        .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                        .andReturn().getResponse().getContentAsByteArray();
                assertEquals(new String(PDF, StandardCharsets.UTF_8), new String(got, StandardCharsets.UTF_8));
                return;
            }
        }
        throw new AssertionError("no PDF object in the receipt");
    }

    /** Control: with the old single-token behaviour the PDF would be fetched with the photos' token and refused. */
    @Test
    void ifOneManifestIsRetractedOnlyThatOnesObjectsAreNotPulled() throws Exception {
        two();
        documents.manifestMode = "retracted";
        JsonNode receipt = receive(bundle.toString());
        assertTrue(receipt.get("stoppedBecause").asText().contains("arex-manifest-documents"), receipt.get("stoppedBecause").toString());
        for (JsonNode o : receipt.get("objects")) {
            boolean pdf = "arex-entry-doc-1".equals(o.get("entryId").asText());
            assertEquals(pdf ? "not-pulled" : "saved", o.get("status").asText(), o.toString());
        }
        assertTrue(has(receipt, "MANIFEST_NOT_CURRENT"));
    }

    @Test
    void aContentEntryNoManifestListsIsReportedAndNotPulled() throws Exception {
        two();
        ArrayNode itemsB = (ArrayNode) bundle.get("entry").get(bundle.get("entry").size() - 1).get("resource").get("entry");
        itemsB.removeAll(); // manifest B now lists nothing: the PDF is an orphan
        JsonNode receipt = receive(bundle.toString());
        assertTrue(has(receipt, "ENTRY_NOT_IN_A_MANIFEST"), findingText(receipt));
        assertEquals(0, documents.tokensIssued.get(), "no token is requested for a manifest that lists nothing");
        for (JsonNode o : receipt.get("objects")) {
            if ("arex-entry-doc-1".equals(o.get("entryId").asText())) {
                assertEquals("not-pulled", o.get("status").asText());
            }
        }
    }
}
