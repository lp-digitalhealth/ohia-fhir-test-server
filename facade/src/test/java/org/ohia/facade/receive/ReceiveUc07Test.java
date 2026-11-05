package org.ohia.facade.receive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ohia.facade.Uc04aFixture;
import org.ohia.facade.UcStubs;
import org.ohia.facade.arexclient.ArexSenderStub;
import org.ohia.facade.keys.ServerKeyStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Receiving a UC07 referral too (a tester sends US the referral and its radiograph manifest), the use-case tag on
 * every receipt and its filter, and the list of registered testers that the page's send form uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReceiveUc07Test {

    static final String S1 = "unit-test-token-123";
    static final Path dataDir = tempDir();
    static final Path clients = dataDir.resolveSibling(dataDir.getFileName() + "-clients.json");

    static {
        try {
            Files.writeString(clients, "{\"clients\":[{\"clientId\":\"tester-a\",\"requestingOrganization\":\"Organization/org-restorative-dental-tx\","
                    + "\"organizationsByUseCase\":{\"uc04a\":\"Organization/org-barton-springs-dental\"},"
                    + "\"allowedTargetPrefixes\":[\"https://tester.example.org/fhir\"],\"jwks\":{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"SECRET-KEY-MATERIAL\"}]}}]}",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("facade.api-token", () -> S1);
        r.add("facade.data-dir", dataDir::toString);
        r.add("facade.hapi-base-url", UcStubs::hapiBaseUrl);
        r.add("facade.public-base-url", () -> "https://facade.test");
        r.add("facade.clients-file", clients::toString);
        r.add("facade.send.allow-http", () -> "true");
        r.add("facade.send.allow-private-targets", () -> "true");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ServerKeyStore keys;

    ArexSenderStub sender;
    ObjectNode uc07;

    @BeforeEach
    void setUp() throws IOException {
        UcStubs.reset();
        Path received = dataDir.resolve("received");
        if (Files.exists(received)) {
            try (Stream<Path> s = Files.walk(received)) {
                s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
        uc07 = (ObjectNode) UcStubs.M.readTree(UcStubs.fixtureText("interaction-01-bundle.json"));
        Map<String, byte[]> images = new LinkedHashMap<>();
        for (ObjectNode e : Uc04aFixture.contentEntries(uc07)) {
            String id = e.get("id").asText();
            images.put(id, UcStubs.fixtureBytes("radiograph-" + id.substring("arex-entry-".length()) + ".png"));
        }
        sender = new ArexSenderStub(keys.publicJwks(), "ohia-test-server", Uc04aFixture.manifest(uc07), images);
        Uc04aFixture.refs(uc07, sender.base());
    }

    @AfterEach
    void tearDown() {
        sender.stop();
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("facade-test-uc07-receive");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions receive(String useCase, String body) throws Exception {
        return mvc.perform(post("/ode/receive/" + useCase).header("Authorization", "Bearer " + S1).contentType("application/fhir+json").content(body));
    }

    private JsonNode ok(String useCase, String body) throws Exception {
        return UcStubs.M.readTree(receive(useCase, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void aUc07ReferralIsStoredItsRadiographsArePulledAndItIsTaggedUc07() throws Exception {
        JsonNode receipt = ok("uc07", uc07.toString());
        assertEquals("uc07", receipt.get("useCase").asText());
        assertTrue(receipt.get("accepted").asBoolean());
        assertEquals(37, receipt.get("hapiEntries").asInt());
        assertTrue(receipt.get("referralId").asText().startsWith("REF-2026-UC07-001"), receipt.get("referralId").asText());
        assertEquals(2, receipt.get("objects").size());
        for (JsonNode o : receipt.get("objects")) {
            assertEquals("saved", o.get("status").asText());
            assertEquals("image/png", o.get("sniffedType").asText());
            assertTrue(o.get("viewable").asBoolean());
        }
        // we played the receiving specialist, which is who the data's manifest names: no mismatch finding
        for (JsonNode f : receipt.get("findings")) {
            assertFalse("REQUESTING_ORG_MISMATCH".equals(f.get("code").asText()), f.toString());
        }
        assertEquals(1, sender.tokensIssued.get());
    }

    /** Control: the same bundle received as UC04a is judged against the practice, so the mismatch is reported. */
    @Test
    void theSameBundleReceivedAsUc04aReportsTheOrganizationMismatch() throws Exception {
        JsonNode receipt = ok("uc04a", uc07.toString());
        assertEquals("uc04a", receipt.get("useCase").asText());
        boolean mismatch = false;
        for (JsonNode f : receipt.get("findings")) {
            mismatch |= "REQUESTING_ORG_MISMATCH".equals(f.get("code").asText());
        }
        assertTrue(mismatch, receipt.get("findings").toString());
    }

    @Test
    void anUnknownUseCaseInTheUrlIsRefusedAndNothingIsStored() throws Exception {
        receive("uc99", uc07.toString()).andExpect(status().isUnprocessableEntity());
        assertEquals(0, UcStubs.posts.get());
    }

    @Test
    void receiptsCanBeFilteredByUseCase() throws Exception {
        ok("uc07", uc07.toString());
        ObjectNode uc04a = Uc04aFixture.bundle();
        ArexSenderStub other = new ArexSenderStub(keys.publicJwks(), "ohia-test-server", Uc04aFixture.manifest(uc04a), Uc04aFixture.photos());
        try {
            Uc04aFixture.refs(uc04a, other.base());
            ok("uc04a", uc04a.toString());
        } finally {
            other.stop();
        }
        mvc.perform(get("/ode/receipts").header("Authorization", "Bearer " + S1)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/ode/receipts?useCase=uc07").header("Authorization", "Bearer " + S1))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].useCase").value("uc07"));
        mvc.perform(get("/ode/receipts?useCase=uc04a").header("Authorization", "Bearer " + S1))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].useCase").value("uc04a"));
        mvc.perform(get("/ode/receipts?useCase=nope").header("Authorization", "Bearer " + S1)).andExpect(status().isUnprocessableEntity());
    }

    /** Receipts stored before UC07 could be received have no use case; they are UC04a and must still load. */
    @Test
    void anOldReceiptWithNoUseCaseIsReadAsUc04a() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        Path dir = dataDir.resolve("received").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("receipt.json"), "{\"id\":\"" + id + "\",\"receivedAt\":\"2026-11-05T12:00:00Z\",\"referralId\":\"R\",\"manifestId\":\"m\","
                + "\"accepted\":true,\"hapiEntries\":1,\"sender\":null,\"tokenEndpoint\":null,\"authorizationDetailsType\":null,\"stoppedBecause\":null,"
                + "\"steps\":[],\"objects\":[],\"findings\":[]}", StandardCharsets.UTF_8);
        mvc.perform(get("/ode/receipts/" + id).header("Authorization", "Bearer " + S1)).andExpect(status().isOk()).andExpect(jsonPath("$.useCase").value("uc04a"));
    }

    // ------------------------------------------------------------------ the testers list

    @Test
    void theTestersListNeedsTheTokenAndNeverShowsKeys() throws Exception {
        mvc.perform(get("/ode/clients")).andExpect(status().isUnauthorized());
        String body = mvc.perform(get("/ode/clients").header("Authorization", "Bearer " + S1)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientId").value("tester-a"))
                .andExpect(jsonPath("$[0].organizations.uc07").value("Organization/org-restorative-dental-tx"))
                .andExpect(jsonPath("$[0].organizations.uc04a").value("Organization/org-barton-springs-dental"))
                .andExpect(jsonPath("$[0].allowedTargetPrefixes[0]").value("https://tester.example.org/fhir"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("SECRET-KEY-MATERIAL") || body.contains("jwks"), body);
    }
}
