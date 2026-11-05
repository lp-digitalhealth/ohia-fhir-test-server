package org.ohia.facade.seed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ohia.facade.UcStubs.M;
import static org.ohia.facade.UcStubs.fixtureBytes;
import static org.ohia.facade.UcStubs.fixtureText;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.web.servlet.MvcResult;

/** Seed and read-back against the shared stand-ins (see UcStubs). */
@SpringBootTest
@AutoConfigureMockMvc
class SeedAndReadBackTest {

    static final String TOKEN = "unit-test-token-123";
    static final Path dataDir = newTempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("facade.api-token", () -> TOKEN);
        r.add("facade.data-dir", dataDir::toString);
        r.add("facade.hapi-base-url", UcStubs::hapiBaseUrl);
        r.add("facade.seed.data-base-url", UcStubs::dataBaseUrl);
        r.add("facade.seed.data-ref", () -> UcStubs.REF);
        r.add("facade.seed.package-path", () -> UcStubs.PKG);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    PackageStore store;

    @BeforeEach
    void reset() throws IOException {
        UcStubs.reset();
        for (String sub : new String[] {"package", "arex-payloads"}) {
            Path p = dataDir.resolve(sub);
            if (Files.exists(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                }
            }
        }
    }

    @Test
    void seedRequiresTheSharedToken() throws Exception {
        mvc.perform(post("/ode/admin/seed")).andExpect(status().isUnauthorized());
        assertEquals(0, UcStubs.posts.get());
    }

    @Test
    void seedSendsTheAuthoredBundleUnmodifiedAndStoresThePayloads() throws Exception {
        mvc.perform(post("/ode/admin/seed").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value(UcStubs.REF))
                .andExpect(jsonPath("$.manifestId").value("arex-manifest-radiographs"))
                .andExpect(jsonPath("$.resources").value(37))
                .andExpect(jsonPath("$.payloadsVerified").value(2))
                .andExpect(jsonPath("$.countsByType.ServiceRequest").value(1))
                .andExpect(jsonPath("$.countsByType.Task").value(1));

        assertEquals(1, UcStubs.posts.get());
        assertEquals(fixtureText("interaction-01-bundle.json"), UcStubs.lastPost.get(),
                "HAPI must get the authored bundle byte for byte");
        assertArrayEquals(fixtureBytes("radiograph-periapical.png"), store.payload("arex-entry-periapical").orElseThrow());
        assertArrayEquals(fixtureBytes("radiograph-bitewing.png"), store.payload("arex-entry-bitewing").orElseThrow());
        assertEquals(37, store.load().orElseThrow().resources().size());
    }

    /** Control: a payload that no longer matches its declared digest must stop the seed before any write. */
    @Test
    void seedRefusesATamperedPayloadAndChangesNothing() throws Exception {
        UcStubs.tamper = true;
        mvc.perform(post("/ode/admin/seed").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Digest mismatch for arex-entry-periapical")));
        assertEquals(0, UcStubs.posts.get(), "nothing may reach HAPI");
        assertTrue(store.load().isEmpty(), "no index may be written");
        assertTrue(store.payload("arex-entry-periapical").isEmpty(), "no payload may be written");
    }

    @Test
    void readBackReturnsTheAuthoredResourcesWithoutServerAddedMeta() throws Exception {
        mvc.perform(post("/ode/admin/seed").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());

        MvcResult res = mvc.perform(get("/ode/package/bundle").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andReturn();
        JsonNode back = M.readTree(res.getResponse().getContentAsString());
        JsonNode original = M.readTree(fixtureText("interaction-01-bundle.json"));

        assertEquals("transaction", back.get("type").asText());
        assertEquals(original.get("entry").size(), back.get("entry").size());
        for (int i = 0; i < original.get("entry").size(); i++) {
            JsonNode o = original.get("entry").get(i);
            JsonNode b = back.get("entry").get(i);
            assertEquals(o.get("resource"), b.get("resource"), "resource " + i + " must round-trip unchanged");
            assertFalse(b.get("resource").path("meta").has("versionId"));
            assertFalse(b.get("resource").path("meta").has("lastUpdated"));
            assertEquals("PUT", b.get("request").get("method").asText());
            assertEquals(o.get("request").get("url").asText(), b.get("request").get("url").asText());
            assertEquals(o.path("fullUrl").asText(), b.path("fullUrl").asText());
        }
    }

    @Test
    void readBackBeforeSeedingIs409() throws Exception {
        mvc.perform(get("/ode/package/bundle").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isConflict());
    }

    /** Control: the read-back must really come from HAPI, so a resource HAPI lost makes it fail. */
    @Test
    void readBackFailsLoudlyWhenHapiLostAResource() throws Exception {
        mvc.perform(post("/ode/admin/seed").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
        assertNotNull(UcStubs.hapiStore.remove("ServiceRequest/servicerequest-referral"));
        mvc.perform(get("/ode/package/bundle").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ServiceRequest/servicerequest-referral")));
    }

    @Test
    void packageSummaryReflectsSeedState() throws Exception {
        mvc.perform(get("/ode/package").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seeded").value(false));
        mvc.perform(post("/ode/admin/seed").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
        mvc.perform(get("/ode/package").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seeded").value(true))
                .andExpect(jsonPath("$.resources").value(37)).andExpect(jsonPath("$.payloads.length()").value(2));
    }

    private static Path newTempDir() {
        try {
            return Files.createTempDirectory("facade-test-seed");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
