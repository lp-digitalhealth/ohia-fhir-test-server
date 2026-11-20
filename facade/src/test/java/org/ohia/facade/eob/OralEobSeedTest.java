package org.ohia.facade.eob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ohia.facade.eob.OralEobFixtures.FILES;
import static org.ohia.facade.eob.OralEobFixtures.TOKEN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Loading the oral EOBs: what is accepted, and that a bad set changes nothing. */
@SpringBootTest
@AutoConfigureMockMvc
class OralEobSeedTest {

    static final Path dataDir = newTempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        OralEobFixtures.properties(r, dataDir);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    OralEobStore store;
    @Autowired
    FacadeProperties facadeProps;
    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void reset() {
        OralEobFixtures.reset();
    }

    private void seedOk() throws Exception {
        mvc.perform(post("/ode/admin/seed-eob").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
    }

    /** Applies some damage to the data host. May throw, so it is not a Runnable. */
    interface Damage {
        void apply() throws Exception;
    }

    /** A good set is loaded first, then the data is damaged and a reload must be refused and leave that set in place. */
    private void expectRefusedAndSetUnchanged(int expectedStatus, Damage damage) throws Exception {
        OralEobFixtures.reset();
        seedOk();
        OralEobStore.Snapshot before = store.current().orElseThrow();
        damage.apply();
        mvc.perform(post("/ode/admin/seed-eob").header("Authorization", "Bearer " + TOKEN)).andExpect(status().is(expectedStatus));
        OralEobStore.Snapshot after = store.current().orElseThrow();
        assertEquals(before.loadedAt(), after.loadedAt(), "a refused load must not replace the set");
        assertEquals(before.resources(), after.resources());
        assertEquals(5, after.resources().size());
    }

    private static byte[] edited(String path, java.util.function.Consumer<ObjectNode> change) throws IOException {
        ObjectNode n = (ObjectNode) new ObjectMapper().readTree(OralEobFixtures.fixture(path));
        change.accept(n);
        return n.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void loadsAllFiveAndReportsThem() throws Exception {
        mvc.perform(post("/ode/admin/seed-eob").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value(OralEobFixtures.REF)).andExpect(jsonPath("$.resources").value(5))
                .andExpect(jsonPath("$.ids[0]").value("eob-d7210-claims-sharing"))
                .andExpect(jsonPath("$.ids[4]").value("eob-teledentistry-claims-sharing"));
        assertEquals(5, store.current().orElseThrow().resources().size());
        assertTrue(Files.isRegularFile(dataDir.resolve("eob").resolve("oral-eobs.json")), "the set is saved to disk");
    }

    @Test
    void needsTheSharedToken() throws Exception {
        mvc.perform(post("/ode/admin/seed-eob")).andExpect(status().isUnauthorized());
        mvc.perform(post("/ode/admin/seed-eob").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void loadingTwiceIsHarmless() throws Exception {
        seedOk();
        seedOk();
        assertEquals(5, store.current().orElseThrow().resources().size());
    }

    @Test
    void theSetSurvivesARestart() throws Exception {
        seedOk();
        List<JsonNode> fresh = new OralEobStore(facadeProps, mapper).current().orElseThrow().resources();
        assertEquals(5, fresh.size());
        assertEquals(store.current().orElseThrow().resources(), fresh);
    }

    @Test
    void refusesAFileThatDoesNotDeclareTheProfile() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> OralEobFixtures.overrides.put(FILES.get(2), edited(FILES.get(2), n -> ((ObjectNode) n.get("meta")).putArray("profile").add("http://example.org/other"))));
    }

    @Test
    void refusesAClaimThatIsNotOral() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> OralEobFixtures.overrides.put(FILES.get(0), edited(FILES.get(0), n -> n.putObject("type").putArray("coding").addObject()
                .put("system", "http://terminology.hl7.org/CodeSystem/claim-type").put("code", "professional"))));
    }

    @Test
    void refusesAFileThatIsNotAnExplanationOfBenefit() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> OralEobFixtures.overrides.put(FILES.get(1), edited(FILES.get(1), n -> n.put("resourceType", "Claim"))));
    }

    @Test
    void refusesAnUnsafeId() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> OralEobFixtures.overrides.put(FILES.get(3), edited(FILES.get(3), n -> n.put("id", "../escape"))));
    }

    @Test
    void refusesAMissingPatientReference() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> OralEobFixtures.overrides.put(FILES.get(4), edited(FILES.get(4), n -> n.remove("patient"))));
    }

    @Test
    void refusesTwoFilesWithTheSameId() throws Exception {
        String firstId = new ObjectMapper().readTree(OralEobFixtures.fixture(FILES.get(0))).get("id").asText();
        expectRefusedAndSetUnchanged(422, () -> OralEobFixtures.overrides.put(FILES.get(1), edited(FILES.get(1), n -> n.put("id", firstId))));
    }

    @Test
    void refusesFileThatIsNotJson() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> OralEobFixtures.overrides.put(FILES.get(2), "not json {".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aFileTheDataHostDoesNotHaveIsAnUpstreamFailureAndChangesNothing() throws Exception {
        expectRefusedAndSetUnchanged(502, () -> OralEobFixtures.missing.add(FILES.get(4)));
    }

    private static Path newTempDir() {
        try {
            return Files.createTempDirectory("oral-eob-seed");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
