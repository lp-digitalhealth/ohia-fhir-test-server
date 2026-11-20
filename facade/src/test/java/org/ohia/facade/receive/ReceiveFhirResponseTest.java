package org.ohia.facade.receive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ohia.facade.Uc04aFixture;
import org.ohia.facade.UcStubs;
import org.ohia.facade.arexclient.ArexClient;
import org.ohia.facade.arexclient.ArexSenderStub;
import org.ohia.facade.keys.ServerKeyStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What a sender is told when it posts a referral. FHIR says a transaction answers with a transaction-response Bundle (or an
 * OperationOutcome if it fails), so that is what comes back, at once; the callback to the sender runs afterwards and its
 * result is in the receipt. The old wait-for-the-receipt answer stays available with {@code ?wait=true}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReceiveFhirResponseTest {

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
    @Autowired
    ReceiptStore receipts;
    @Autowired
    ReceiveService service;
    @Autowired
    ObjectMapper mapper;
    @MockitoSpyBean
    ArexClient arex;

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
        Uc04aFixture.refs(bundle, sender.base());
    }

    @AfterEach
    void tearDown() {
        sender.stop();
    }

    private ResultActions send(String query, String body) throws Exception {
        return mvc.perform(post("/ode/receive/uc04a" + query).header("Authorization", "Bearer " + S1).contentType("application/fhir+json").content(body));
    }

    private JsonNode receipt(String id) throws Exception {
        return mapper.readTree(mvc.perform(get("/ode/receipts/" + id).header("Authorization", "Bearer " + S1)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** Waits (up to 30 s) for the receipt to leave {@code pending}; fails if it never does. */
    private JsonNode untilDone(String id) throws Exception {
        for (int i = 0; i < 300; i++) {
            JsonNode r = receipt(id);
            if (!"pending".equals(r.get("state").asText())) {
                return r;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the receipt was still pending after 30 seconds");
    }

    // ------------------------------------------------------------------ the answer

    @Test
    void answersWithATransactionResponseAndPointsAtTheReceipt() throws Exception {
        MvcResult r = send("", bundle.toString()).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(jsonPath("$.resourceType").value("Bundle")).andExpect(jsonPath("$.type").value("transaction-response")).andReturn();
        String id = r.getResponse().getHeader("X-Receipt-Id");
        assertTrue(ReceiptStore.validReceiptId(id), "the receipt id is in a header: " + id);
        assertEquals("<https://facade.test/ode/receipts/" + id + ">; rel=\"receipt\"", r.getResponse().getHeader("Link"));
        JsonNode done = untilDone(id);
        assertEquals("complete", done.get("state").asText());
        assertEquals(List.of("saved", "saved"), objectStatuses(done));
    }

    /** Control: with a slow sender the answer must come back long before the callback finishes. */
    @Test
    void theAnswerDoesNotWaitForTheSenderAndTheReceiptSaysPendingUntilThePullEnds() throws Exception {
        sender.delayMillis = 1500; // at least five calls to the sender (discovery, token, manifest, two images): 7.5 s or more
        long started = System.nanoTime();
        MvcResult r = send("", bundle.toString()).andExpect(status().isOk()).andReturn();
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertTrue(ms < 3000, "answered in " + ms + " ms; it waited for the sender");
        String id = r.getResponse().getHeader("X-Receipt-Id");
        JsonNode early = receipt(id);
        assertEquals("pending", early.get("state").asText());
        assertEquals(0, early.get("objects").size(), "no images yet");
        assertTrue(early.get("accepted").asBoolean(), "but the referral is already stored");
        JsonNode done = untilDone(id);
        assertEquals("complete", done.get("state").asText());
        assertEquals(List.of("saved", "saved"), objectStatuses(done));
    }

    @Test
    void waitTrueKeepsTheEarlierAnswerWhichIsTheFinishedReceipt() throws Exception {
        MvcResult r = send("?wait=true", bundle.toString()).andExpect(status().isOk()).andReturn();
        JsonNode receipt = mapper.readTree(r.getResponse().getContentAsString());
        assertEquals("complete", receipt.get("state").asText());
        assertEquals(List.of("saved", "saved"), objectStatuses(receipt));
        assertEquals(null, r.getResponse().getHeader("X-Receipt-Id"));
    }

    @Test
    void anAnswerFromHapiThatIsNotJsonIsReplacedByAValidTransactionResponse() throws Exception {
        UcStubs.hapiBodyOverride = "OK";
        send("", bundle.toString()).andExpect(status().isOk()).andExpect(jsonPath("$.resourceType").value("Bundle"))
                .andExpect(jsonPath("$.type").value("transaction-response")).andExpect(jsonPath("$.entry.length()").value(30))
                .andExpect(jsonPath("$.entry[0].response.status").value("200 OK"));
    }

    /** Control: valid JSON that is not a transaction-response (here an OperationOutcome) must not be passed on as the answer. */
    @Test
    void anAnswerFromHapiThatIsJsonButNotATransactionResponseIsReplacedToo() throws Exception {
        UcStubs.hapiBodyOverride = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"information\",\"code\":\"informational\"}]}";
        send("", bundle.toString()).andExpect(status().isOk()).andExpect(jsonPath("$.resourceType").value("Bundle"))
                .andExpect(jsonPath("$.type").value("transaction-response")).andExpect(jsonPath("$.entry.length()").value(30));
    }

    // ------------------------------------------------------------------ failures are OperationOutcomes

    @Test
    void aBodyThatIsNotATransactionIsAnOperationOutcomeAndStoresNothing() throws Exception {
        send("", "{\"resourceType\":\"Patient\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/fhir+json")).andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].severity").value("error")).andExpect(jsonPath("$.issue[0].code").value("invalid"));
        assertEquals(0, UcStubs.posts.get());
        assertEquals(0, receipts.recent(10).size());
    }

    @Test
    void aPayloadOverTheLimitIsAnOperationOutcome() throws Exception {
        send("", "{\"resourceType\":\"Bundle\",\"x\":\"" + "a".repeat(ReceiveService.MAX_BODY_BYTES + 10) + "\"}").andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome")).andExpect(jsonPath("$.issue[0].code").value("too-long"));
    }

    @Test
    void whenHapiRefusesTheAnswerIsAnOperationOutcomeAndNothingIsPulledOrKept() throws Exception {
        UcStubs.postStatus = 400;
        send("", bundle.toString()).andExpect(status().isBadGateway()).andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].diagnostics").value(org.hamcrest.Matchers.containsString("HAPI rejected the transaction")));
        assertEquals(0, sender.requests.size());
        assertEquals(0, receipts.recent(10).size());
    }

    // ------------------------------------------------------------------ a pull that does not finish normally

    @Test
    void aPullThatCrashesIsRecordedAsFailedNotLeftPending() throws Exception {
        doThrow(new IllegalStateException("boom")).when(arex).pull(any(), any(), any(), any());
        JsonNode receipt = mapper.readTree(send("?wait=true", bundle.toString()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("failed", receipt.get("state").asText());
        assertTrue(receipt.get("accepted").asBoolean(), "the referral was stored");
        assertTrue(codes(receipt).contains("PULL_FAILED"));
        assertFalse(receipt.get("stoppedBecause").isNull());
    }

    @Test
    void aReceiptLeftPendingByARestartIsMarkedInterrupted() throws Exception {
        Receipt pending = new Receipt("11111111-2222-3333-4444-555555555555", Instant.parse("2026-11-20T10:00:00Z"), "REF-1", "m1", true, 30, null, null,
                null, null, List.of(), List.of(), List.of(), "uc04a", Receipt.PENDING);
        receipts.saveReceipt(pending);
        service.markInterrupted();
        JsonNode r = receipt(pending.id());
        assertEquals("interrupted", r.get("state").asText());
        assertTrue(codes(r).contains("PULL_INTERRUPTED"));
    }

    @Test
    void aReceiptSavedBeforeStateExistedReadsAsComplete() throws Exception {
        MvcResult r = send("?wait=true", bundle.toString()).andExpect(status().isOk()).andReturn();
        String id = mapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
        Path file = dataDir.resolve("received").resolve(id).resolve("receipt.json");
        ObjectNode old = (ObjectNode) mapper.readTree(file.toFile());
        old.remove("state");
        Files.writeString(file, old.toString());
        assertEquals("complete", receipts.load(id).orElseThrow().state());
    }

    private static List<String> objectStatuses(JsonNode receipt) {
        return java.util.stream.StreamSupport.stream(receipt.get("objects").spliterator(), false).map(o -> o.get("status").asText()).toList();
    }

    private static Set<String> codes(JsonNode receipt) {
        Set<String> out = new java.util.HashSet<>();
        receipt.get("findings").forEach(f -> out.add(f.get("code").asText()));
        return out;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("facade-test-fhir-response");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
