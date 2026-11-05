package org.ohia.facade.send;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ohia.facade.UcStubs;
import org.ohia.facade.arex.ArexAccess;
import org.ohia.facade.seed.PackageStore;
import org.ohia.facade.seed.SeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Seeding, reading back and SENDING the UC04a referral, with our own copy of the two photos hosted by our ARex
 * content server. Stand-ins: data host and HAPI (UcStubs) and a tester's receiver (ReceiverStub).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SendUc04aTest {

    static final String TOKEN = "unit-test-token-123";
    static final String PRACTICE = "Organization/org-barton-springs-dental";
    static final Path dataDir = tempDir("facade-test-uc04a");
    static final Path clientsFile = dataDir.resolveSibling(dataDir.getFileName() + "-clients.json");
    static final ReceiverStub receiver = new ReceiverStub();

    static {
        try {
            // tester-a plays the UC07 specialist AND, for UC04a, the practice; tester-plain has no UC04a organization
            Files.writeString(clientsFile, "{\"clients\":["
                    + "{\"clientId\":\"tester-a\",\"requestingOrganization\":\"Organization/org-restorative-dental-tx\","
                    + "\"organizationsByUseCase\":{\"uc04a\":\"" + PRACTICE + "\"},"
                    + "\"allowedTargetPrefixes\":[\"" + receiver.baseUrl() + "\"],\"jwks\":{\"keys\":[]}},"
                    + "{\"clientId\":\"tester-plain\",\"requestingOrganization\":\"Organization/org-restorative-dental-tx\","
                    + "\"allowedTargetPrefixes\":[\"" + receiver.baseUrl() + "\"],\"jwks\":{\"keys\":[]}}]}", StandardCharsets.UTF_8);
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
        r.add("facade.use-cases.uc04a.data-ref", () -> UcStubs.UC04A_REF);
        r.add("facade.use-cases.uc04a.package-path", () -> UcStubs.UC04A_PKG);
        r.add("facade.send.allow-http", () -> "true");
        r.add("facade.send.allow-private-targets", () -> "true");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ManifestStore manifests;
    @Autowired
    ArexAccess access;
    @Autowired
    PackageStore packages;

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

    private static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions seed(String useCase) throws Exception {
        return mvc.perform(post("/ode/admin/seed" + (useCase == null ? "" : "?useCase=" + useCase)).header("Authorization", "Bearer " + TOKEN));
    }

    private ResultActions send(String clientId, String useCase) throws Exception {
        String body = "{\"clientId\":\"" + clientId + "\",\"targetBaseUrl\":\"" + receiver.baseUrl() + "\""
                + (useCase == null ? "" : ",\"useCase\":\"" + useCase + "\"") + "}";
        return mvc.perform(post("/ode/send").header("Authorization", "Bearer " + TOKEN).contentType("application/json").content(body));
    }

    // ------------------------------------------------------------------ seeding

    @Test
    void seedingUc04aLoadsTheAuthoredBundleAndKeepsTheTwoPhotos() throws Exception {
        seed("uc04a").andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value(UcStubs.UC04A_REF))
                .andExpect(jsonPath("$.manifestId").value("arex-manifest-intraoral-photos"))
                .andExpect(jsonPath("$.resources").value(30))
                .andExpect(jsonPath("$.payloadsVerified").value(2));
        assertEquals(1, UcStubs.posts.get());
        assertArrayEquals(UcStubs.uc04aBytes("intraoral-photo-1.jpg"), packages.payload("arex-entry-photo-1").orElseThrow());
        // UC07 was not seeded by this, and is reported as such
        mvc.perform(get("/ode/package?useCase=uc07").header("Authorization", "Bearer " + TOKEN)).andExpect(jsonPath("$.seeded").value(false));
        mvc.perform(get("/ode/package?useCase=uc04a").header("Authorization", "Bearer " + TOKEN)).andExpect(jsonPath("$.seeded").value(true));
    }

    /** Control: a photo whose bytes do not match its declared digest stops the seed; nothing is written. */
    @Test
    void aTamperedPhotoStopsTheSeedAndNothingIsWritten() throws Exception {
        UcStubs.tamperUc04a = true;
        seed("uc04a").andExpect(status().isUnprocessableEntity());
        assertEquals(0, UcStubs.posts.get(), "nothing may reach HAPI");
        assertTrue(packages.load(org.ohia.facade.common.UseCase.UC04A).isEmpty(), "no index may be written");
    }

    @Test
    void anUnknownUseCaseIsRefused() throws Exception {
        seed("uc99").andExpect(status().isUnprocessableEntity());
        assertEquals(0, UcStubs.posts.get());
    }

    // ------------------------------------------------------------------ sending

    @Test
    void sendsTheUc04aReferralWithOurPhotosHostedByOurArexServer() throws Exception {
        seed("uc04a").andExpect(status().isOk());
        String response = send("tester-a", "uc04a").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("delivered"))
                .andExpect(jsonPath("$.useCase").value("uc04a"))
                .andReturn().getResponse().getContentAsString();
        JsonNode record = UcStubs.M.readTree(response);

        ReceiverStub.Received got = receiver.received.get(0);
        JsonNode bundle = UcStubs.M.readTree(got.body());
        assertEquals("transaction", bundle.get("type").asText());
        assertEquals(30, bundle.get("entry").size());
        String referralId = record.get("referralId").asText();
        assertTrue(referralId.startsWith("REF-2026-UC04A-001-"), referralId);
        assertFalse(got.body().contains("\"REF-2026-UC04A-001\""), "a send is its own referral");
        assertFalse(got.body().contains("meridian-teledental.example.org/arex/Binary"), "the authored photo URLs must be replaced by ours");
        assertTrue(got.body().contains("https://facade.test/arex/Binary/"));
        assertTrue(got.body().contains(PRACTICE));

        // the manifest we will serve is entitled to this tester as the practice, and holds the real photos
        ManifestRecord m = manifests.load(record.get("manifestId").asText()).orElseThrow();
        assertEquals(PRACTICE, m.requestingOrganization());
        assertEquals(2, m.entries().size());
        for (PerSendCopier.Entry e : m.entries()) {
            assertEquals("image/jpeg", e.contentType());
            assertEquals(632, e.size());
            byte[] stored = packages.payload(e.originalEntryId()).orElseThrow();
            assertEquals(e.sha256(), SeedService.sha256(stored));
        }
        assertTrue(access.entitled("tester-a", m.manifestId()).isPresent(), "the tester, playing the practice, may retrieve it");
        assertTrue(access.entitled("someone-else", m.manifestId()).isEmpty(), "nobody else may");
        mvc.perform(get("/ode/sends").header("Authorization", "Bearer " + TOKEN)).andExpect(jsonPath("$[0].useCase").value("uc04a"));
    }

    /** Control: the old rule (one organization per tester) would have let a UC07-only tester retrieve UC04a photos. */
    @Test
    void aTesterRegisteredOnlyAsTheUc07SpecialistCannotBeSentTheUc04aReferral() throws Exception {
        seed("uc04a").andExpect(status().isOk());
        send("tester-plain", "uc04a").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists());
        assertEquals(0, receiver.received.size(), "nothing may be delivered");
    }

    // ------------------------------------------------------------------ viewing the images we send

    private ResultActions getImage(String path, boolean withToken) throws Exception {
        var req = get(path);
        return mvc.perform(withToken ? req.header("Authorization", "Bearer " + TOKEN) : req);
    }

    @Test
    void theSeededPhotosCanBeViewedInlineAsRealJpegs() throws Exception {
        seed("uc04a").andExpect(status().isOk());
        byte[] photo = UcStubs.uc04aBytes("intraoral-photo-1.jpg");
        byte[] got = getImage("/ode/package/images/uc04a/arex-entry-photo-1", true).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "image/jpeg"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", "inline"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(photo, got);
    }

    @Test
    void theImageEndpointsNeedTheTokenAndRefuseWhatIsNotInThePackageOrTheSend() throws Exception {
        seed("uc04a").andExpect(status().isOk());
        getImage("/ode/package/images/uc04a/arex-entry-photo-1", false).andExpect(status().isUnauthorized());
        getImage("/ode/package/images/uc04a/arex-entry-photo-9", true).andExpect(status().isNotFound());
        getImage("/ode/package/images/uc07/arex-entry-photo-1", true).andExpect(status().isNotFound());   // UC07 not seeded
        getImage("/ode/package/images/uc99/arex-entry-photo-1", true).andExpect(status().isUnprocessableEntity());
        getImage("/ode/package/images/uc04a/..%2F..%2Fpackage%2Fuc04a-i1.json", true).andExpect(status().is4xxClientError());
        getImage("/ode/sends/not-a-send/images/arex-entry-photo-1", true).andExpect(status().isNotFound());
        getImage("/ode/sends/00000000-0000-0000-0000-000000000000/images/arex-entry-photo-1", false).andExpect(status().isUnauthorized());
    }

    @Test
    void theImagesASendCarriedCanBeViewedAndTheManifestListsThem() throws Exception {
        seed("uc04a").andExpect(status().isOk());
        JsonNode record = UcStubs.M.readTree(send("tester-a", "uc04a").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String sendId = record.get("sendId").asText();
        byte[] got = getImage("/ode/sends/" + sendId + "/images/arex-entry-photo-2", true).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(UcStubs.uc04aBytes("intraoral-photo-2.jpg"), got);
        getImage("/ode/sends/" + sendId + "/images/arex-entry-photo-9", true).andExpect(status().isNotFound());
        mvc.perform(get("/ode/manifests/" + record.get("manifestId").asText()).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].entryId").value("arex-entry-photo-1"))
                .andExpect(jsonPath("$.entries[0].contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.entries[0].size").value(632));
    }

    /** Control: if the stored bytes are not really an image, they are offered only as a download, never inline. */
    @Test
    void bytesThatAreNotAnImageAreOnlyEverADownload() throws Exception {
        seed("uc04a").andExpect(status().isOk());
        Files.write(dataDir.resolve("arex-payloads").resolve("arex-entry-photo-1.bin"), "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        getImage("/ode/package/images/uc04a/arex-entry-photo-1", true).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "application/octet-stream"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void sendingBeforeSeedingIsAClearConflict() throws Exception {
        send("tester-a", "uc04a").andExpect(status().isConflict());
        assertEquals(0, receiver.received.size());
    }
}
