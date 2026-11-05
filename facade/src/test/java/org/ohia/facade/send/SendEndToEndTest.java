package org.ohia.facade.send;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ohia.facade.UcStubs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole send path against stand-ins: data host + HAPI (UcStubs) and a tester receiver.
 * The two test-only flags are on here so a receiver on 127.0.0.1 over http is reachable; the
 * refusal tests (SendRefusalsTest) run with the real defaults.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SendEndToEndTest {

    static final String TOKEN = "unit-test-token-123";
    static final String TESTER_TOKEN = "tester-secret-xyz";
    static final Path dataDir = tempDir("facade-test-e2e");
    static final Path clientsFile = dataDir.resolveSibling(dataDir.getFileName() + "-clients.json");
    static final ReceiverStub receiver = new ReceiverStub();

    static {
        try {
            Files.writeString(clientsFile, "{\"clients\":["
                    + "{\"clientId\":\"tester-a\",\"requestingOrganization\":\"Organization/org-restorative-dental-tx\","
                    + "\"allowedTargetPrefixes\":[\"" + receiver.baseUrl() + "\"],\"jwks\":{\"keys\":[]}},"
                    + "{\"clientId\":\"tester-down\",\"requestingOrganization\":\"Organization/org-restorative-dental-tx\","
                    + "\"allowedTargetPrefixes\":[\"http://127.0.0.1:1/fhir\"],\"jwks\":{\"keys\":[]}},"
                    + "{\"clientId\":\"tester-badorg\",\"requestingOrganization\":\"Organization/org-not-in-package\","
                    + "\"allowedTargetPrefixes\":[\"" + receiver.baseUrl() + "\"],\"jwks\":{\"keys\":[]}}]}",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("facade.api-token", () -> TOKEN);
        r.add("facade.data-dir", dataDir::toString);
        r.add("facade.clients-file", clientsFile::toString);
        r.add("facade.public-base-url", () -> "https://facade.test");
        r.add("facade.hapi-base-url", UcStubs::hapiBaseUrl);
        r.add("facade.seed.data-base-url", UcStubs::dataBaseUrl);
        r.add("facade.seed.data-ref", () -> UcStubs.REF);
        r.add("facade.seed.package-path", () -> UcStubs.PKG);
        r.add("facade.send.allow-http", () -> "true");
        r.add("facade.send.allow-private-targets", () -> "true");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ManifestStore manifests;

    @BeforeEach
    void reset() throws IOException {
        UcStubs.reset();
        receiver.reset();
        for (String sub : new String[] {"package", "arex-payloads", "manifests", "log"}) {
            Path p = dataDir.resolve(sub);
            if (Files.exists(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                }
            }
        }
    }

    private void seed() throws Exception {
        mvc.perform(post("/ode/admin/seed").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
    }

    private String sendJson(String clientId, String target, String token) {
        return "{\"clientId\":\"" + clientId + "\",\"targetBaseUrl\":\"" + target + "\""
                + (token == null ? "" : ",\"targetBearerToken\":\"" + token + "\"") + "}";
    }

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        return mvc.perform(post("/ode/send").header("Authorization", "Bearer " + TOKEN)
                .contentType("application/json").content(body));
    }

    @Test
    void deliversOneTransactionBundleToTheTesterAndLogsIt() throws Exception {
        seed();
        String response = send(sendJson("tester-a", receiver.baseUrl(), TESTER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("delivered"))
                .andExpect(jsonPath("$.targetStatus").value(200))
                .andExpect(jsonPath("$.clientId").value("tester-a"))
                .andReturn().getResponse().getContentAsString();
        JsonNode record = UcStubs.M.readTree(response);

        assertEquals(1, receiver.received.size());
        ReceiverStub.Received got = receiver.received.get(0);
        assertEquals("POST", got.method());
        assertEquals("/fhir", got.path());
        assertEquals("Bearer " + TESTER_TOKEN, got.authorization());
        assertTrue(got.contentType().startsWith("application/fhir+json"));
        JsonNode bundle = UcStubs.M.readTree(got.body());
        assertEquals("transaction", bundle.get("type").asText());
        assertEquals(37, bundle.get("entry").size());

        String referralId = record.get("referralId").asText();
        assertTrue(referralId.startsWith("REF-2026-UC07-001-"), referralId);
        assertTrue(got.body().contains(referralId));
        assertFalse(got.body().contains("\"REF-2026-UC07-001\""));
        assertFalse(got.body().contains("pediatricdental.example.org/arex/Binary"));
        assertTrue(got.body().contains("https://facade.test/arex/Binary/"));
        assertTrue(got.body().contains("Organization/org-restorative-dental-tx"));

        // the manifest record the content server will use exists and matches what was sent
        ManifestRecord m = manifests.load(record.get("manifestId").asText()).orElseThrow();
        assertEquals("tester-a", m.clientId());
        assertEquals("Organization/org-restorative-dental-tx", m.requestingOrganization());
        assertFalse(m.retracted());
        assertEquals(2, m.entries().size());
        Duration ttl = Duration.between(Instant.now(), m.expiresAt());
        assertTrue(ttl.toDays() >= 29 && ttl.toDays() <= 30, "manifest should live about 30 days, got " + ttl);
        for (PerSendCopier.Entry e : m.entries()) {
            assertTrue(got.body().contains("/arex/Binary/" + e.binaryId()));
        }

        // the delivery log shows it, and the tester's token is nowhere on disk
        mvc.perform(get("/ode/sends").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sendId").value(record.get("sendId").asText()))
                .andExpect(jsonPath("$[0].outcome").value("delivered"));
        mvc.perform(get("/ode/sends/" + record.get("sendId").asText()).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
        try (Stream<Path> files = Files.walk(dataDir)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                assertFalse(Files.readString(f, StandardCharsets.ISO_8859_1).contains(TESTER_TOKEN),
                        "the tester's token must not be stored: " + f);
            }
        }
    }

    @Test
    void everySendIsASeparateReferral() throws Exception {
        seed();
        JsonNode a = UcStubs.M.readTree(send(sendJson("tester-a", receiver.baseUrl(), null)).andReturn().getResponse().getContentAsString());
        JsonNode b = UcStubs.M.readTree(send(sendJson("tester-a", receiver.baseUrl(), null)).andReturn().getResponse().getContentAsString());
        assertNotEquals(a.get("referralId").asText(), b.get("referralId").asText());
        assertNotEquals(a.get("manifestId").asText(), b.get("manifestId").asText());
        assertNotEquals(a.get("sendId").asText(), b.get("sendId").asText());
        assertEquals(2, receiver.received.size());
        assertEquals(null, receiver.received.get(0).authorization(), "no token configured means no Authorization header");
    }

    /** Control: what is sent must come from HAPI, not from files. */
    @Test
    void sendsWhatHapiHoldsNotWhatWasSeeded() throws Exception {
        seed();
        ObjectNode sr = (ObjectNode) UcStubs.hapiStore.get("ServiceRequest/servicerequest-referral");
        sr.put("patientInstruction", "MARKER-FROM-HAPI-4711");
        send(sendJson("tester-a", receiver.baseUrl(), null)).andExpect(status().isOk());
        assertTrue(receiver.received.get(0).body().contains("MARKER-FROM-HAPI-4711"));
    }

    @Test
    void aTargetThatRejectsIsAResultNotAnError() throws Exception {
        seed();
        receiver.status = 400;
        receiver.responseBody = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\"}]}";
        send(sendJson("tester-a", receiver.baseUrl(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("rejected-by-target"))
                .andExpect(jsonPath("$.targetStatus").value(400))
                .andExpect(jsonPath("$.targetResponseExcerpt").value(org.hamcrest.Matchers.containsString("OperationOutcome")));
    }

    @Test
    void anUnreachableTargetIsReportedAsUnreachable() throws Exception {
        seed();
        send(sendJson("tester-down", "http://127.0.0.1:1/fhir", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("unreachable"))
                .andExpect(jsonPath("$.targetStatus").value(-1));
    }

    /** Control: a registered organization that is not in the package is refused before anything is written or sent. */
    @Test
    void aRegisteredOrganizationOutsideThePackageIsRefusedAndNothingIsWritten() throws Exception {
        seed();
        send(sendJson("tester-badorg", receiver.baseUrl(), null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("org-not-in-package")));
        assertEquals(0, receiver.received.size());
        assertFalse(Files.exists(dataDir.resolve("manifests")) && Files.list(dataDir.resolve("manifests")).findAny().isPresent(),
                "no manifest record may be written");
        mvc.perform(get("/ode/sends").header("Authorization", "Bearer " + TOKEN)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anUnknownClientIsRefusedBeforeAnythingHappens() throws Exception {
        seed();
        send(sendJson("nobody", receiver.baseUrl(), null)).andExpect(status().isUnprocessableEntity());
        assertEquals(0, receiver.received.size());
    }

    @Test
    void aTargetOutsideTheClientsAllowListIsRefused() throws Exception {
        seed();
        send(sendJson("tester-a", "http://127.0.0.1:" + (Integer.parseInt(receiver.baseUrl().split(":")[2].split("/")[0]) + 1) + "/fhir", null))
                .andExpect(status().isForbidden());
        send(sendJson("tester-a", receiver.baseUrl() + ".evil.example/fhir", null)).andExpect(status().isForbidden());
        assertEquals(0, receiver.received.size());
    }

    @Test
    void sendingBeforeSeedingIs409AndTheReceiverHearsNothing() throws Exception {
        send(sendJson("tester-a", receiver.baseUrl(), null)).andExpect(status().isConflict());
        assertEquals(0, receiver.received.size());
    }

    @Test
    void sendingNeedsTheSharedToken() throws Exception {
        seed();
        mvc.perform(post("/ode/send").contentType("application/json").content(sendJson("tester-a", receiver.baseUrl(), null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/ode/sends")).andExpect(status().isUnauthorized());
        assertEquals(0, receiver.received.size());
    }

    @Test
    void missingFieldsAreRejected() throws Exception {
        seed();
        send("{\"clientId\":\"tester-a\"}").andExpect(status().isUnprocessableEntity());
        send("{\"targetBaseUrl\":\"" + receiver.baseUrl() + "\"}").andExpect(status().isUnprocessableEntity());
    }

    private static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
