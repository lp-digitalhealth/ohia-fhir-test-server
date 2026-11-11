package org.ohia.facade.patientaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ohia.facade.patientaccess.PatientAccessFixtures.FILES;
import static org.ohia.facade.patientaccess.PatientAccessFixtures.TOKEN;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Loading the payer-side resources: what is accepted, and that a bad set changes nothing. */
@SpringBootTest
@AutoConfigureMockMvc
class PatientAccessSeedTest {

    static final Path dataDir = newTempDir();
    static final String SEED = "/ode/admin/seed-patient-access";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PatientAccessFixtures.properties(r, dataDir);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    PatientAccessStore store;
    @Autowired
    FacadeProperties facadeProps;
    @Autowired
    ObjectMapper mapper;

    interface Damage {
        void apply() throws Exception;
    }

    @BeforeEach
    void reset() {
        PatientAccessFixtures.reset();
    }

    private void seedOk() throws Exception {
        mvc.perform(post(SEED).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
    }

    /** A good set is loaded first, then the data is damaged: a reload must be refused and leave that set in place. */
    private void expectRefusedAndSetUnchanged(int expectedStatus, Damage damage) throws Exception {
        PatientAccessFixtures.reset();
        seedOk();
        PatientAccessStore.Snapshot before = store.current().orElseThrow();
        damage.apply();
        mvc.perform(post(SEED).header("Authorization", "Bearer " + TOKEN)).andExpect(status().is(expectedStatus));
        PatientAccessStore.Snapshot after = store.current().orElseThrow();
        assertEquals(before.loadedAt(), after.loadedAt(), "a refused load must not replace the set");
        assertEquals(before.resources(), after.resources());
        assertEquals(9, after.resources().size());
    }

    private void edit(int fileIndex, Consumer<ObjectNode> change) throws IOException {
        String path = FILES.get(fileIndex);
        ObjectNode n = (ObjectNode) mapper.readTree(PatientAccessFixtures.fixture(path));
        change.accept(n);
        PatientAccessFixtures.overrides.put(path, n.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void loadsAllNineAndReportsThemByType() throws Exception {
        mvc.perform(post(SEED).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value(PatientAccessFixtures.REF)).andExpect(jsonPath("$.resources").value(9))
                .andExpect(jsonPath("$.countsByType.Patient").value(1)).andExpect(jsonPath("$.countsByType.Coverage").value(1))
                .andExpect(jsonPath("$.countsByType.Organization").value(3)).andExpect(jsonPath("$.countsByType.Practitioner").value(2))
                .andExpect(jsonPath("$.countsByType.ExplanationOfBenefit").value(2));
        assertTrue(Files.isRegularFile(dataDir.resolve("patient-access").resolve("resources.json")), "the set is saved to disk");
    }

    @Test
    void needsTheSharedToken() throws Exception {
        mvc.perform(post(SEED)).andExpect(status().isUnauthorized());
        mvc.perform(post(SEED).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void loadingTwiceIsHarmless() throws Exception {
        seedOk();
        seedOk();
        assertEquals(9, store.current().orElseThrow().resources().size());
    }

    @Test
    void theSetSurvivesARestart() throws Exception {
        seedOk();
        List<JsonNode> fresh = new PatientAccessStore(facadeProps, mapper).current().orElseThrow().resources();
        assertEquals(store.current().orElseThrow().resources(), fresh);
    }

    @Test
    void refusesAResourceWithoutAPinnedBlueButtonProfile() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> edit(7, n -> ((ObjectNode) n.get("meta")).putArray("profile").add("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-ExplanationOfBenefit-Oral")));
    }

    @Test
    void refusesAProfileOfTheWrongType() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> edit(0, n -> ((ObjectNode) n.get("meta")).putArray("profile").add("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Coverage|2.2.0")));
    }

    @Test
    void refusesATypeThisBaseDoesNotServe() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> edit(3, n -> n.put("resourceType", "Claim")));
    }

    @Test
    void refusesAnUnsafeId() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> edit(5, n -> n.put("id", "../escape")));
    }

    @Test
    void refusesAResourceWithoutLastUpdated() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> edit(2, n -> ((ObjectNode) n.get("meta")).remove("lastUpdated")));
    }

    @Test
    void refusesADanglingReference() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> edit(7, n -> ((ObjectNode) n.get("insurer")).put("reference", "Organization/nobody")));
    }

    @Test
    void refusesAnAbsoluteReference() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> edit(7, n -> ((ObjectNode) n.get("patient")).put("reference", "https://elsewhere.example/Patient/patient-joe-castle-payer")));
    }

    @Test
    void refusesTwoFilesWithTheSameResource() throws Exception {
        String firstId = mapper.readTree(PatientAccessFixtures.fixture(FILES.get(5))).get("id").asText();
        expectRefusedAndSetUnchanged(422, () -> edit(6, n -> n.put("id", firstId)));
    }

    @Test
    void refusesASetWithNoPatient() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> edit(0, n -> {
            n.put("resourceType", "Practitioner");
            ((ObjectNode) n.get("meta")).putArray("profile").add("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Practitioner|2.2.0");
            n.put("id", "pract-extra-payer");
        }));
    }

    @Test
    void refusesFileThatIsNotJson() throws Exception {
        expectRefusedAndSetUnchanged(422, () -> PatientAccessFixtures.overrides.put(FILES.get(4), "not json {".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aFileTheDataHostDoesNotHaveIsAnUpstreamFailureAndChangesNothing() throws Exception {
        expectRefusedAndSetUnchanged(502, () -> PatientAccessFixtures.missing.add(FILES.get(8)));
    }

    private static Path newTempDir() {
        try {
            return Files.createTempDirectory("patient-access-seed");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
