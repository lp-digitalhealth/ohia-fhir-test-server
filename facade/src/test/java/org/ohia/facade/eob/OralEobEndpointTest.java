package org.ohia.facade.eob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ohia.facade.eob.OralEobFixtures.FILES;
import static org.ohia.facade.eob.OralEobFixtures.TOKEN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Read and search of the loaded oral EOBs at /fhir/ExplanationOfBenefit. */
@SpringBootTest
@AutoConfigureMockMvc
class OralEobEndpointTest {

    static final Path dataDir = newTempDir();
    static final String EOB = "/fhir/ExplanationOfBenefit";
    static final String SARAH = "Patient/patient-sarah-okonkwo";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        OralEobFixtures.properties(r, dataDir);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void load() throws Exception {
        OralEobFixtures.reset();
        mvc.perform(post("/ode/admin/seed-eob").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder search(String... nameValuePairs) {
        MockHttpServletRequestBuilder b = get(EOB).header("Authorization", "Bearer " + TOKEN);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            b.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return b;
    }

    private List<String> ids(MockHttpServletRequestBuilder request) throws Exception {
        JsonNode bundle = mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> out = new ArrayList<>();
        bundle.path("entry").forEach(e -> out.add(e.path("resource").path("id").asText()));
        return out;
    }

    private int total(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("total").asInt();
    }

    // ------------------------------------------------------------------ read

    @Test
    void readsOneOralEobExactlyAsLoaded() throws Exception {
        String body = mvc.perform(get(EOB + "/eob-inoffice-claims-sharing").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(mapper.readTree(OralEobFixtures.fixture(FILES.get(3))), mapper.readTree(body), "served unchanged");
    }

    @Test
    void anUnknownIdIs404WithAnOperationOutcome() throws Exception {
        mvc.perform(get(EOB + "/nope").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome")).andExpect(jsonPath("$.issue[0].code").value("not-found"));
    }

    // ------------------------------------------------------------------ search

    @Test
    void searchWithNoParametersReturnsAllFiveAsASearchset() throws Exception {
        mvc.perform(search()).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(jsonPath("$.resourceType").value("Bundle")).andExpect(jsonPath("$.type").value("searchset"))
                .andExpect(jsonPath("$.total").value(5)).andExpect(jsonPath("$.entry.length()").value(5))
                .andExpect(jsonPath("$.link[0].relation").value("self")).andExpect(jsonPath("$.link[0].url").value("https://ohia.test" + EOB))
                .andExpect(jsonPath("$.entry[0].fullUrl").value("https://ohia.test" + EOB + "/eob-d7210-claims-sharing"))
                .andExpect(jsonPath("$.entry[0].search.mode").value("match"));
    }

    @Test
    void thePatientParameterAcceptsReferenceIdAndFullUrl() throws Exception {
        List<String> expected = List.of("eob-inoffice-claims-sharing", "eob-teledentistry-claims-sharing");
        assertEquals(expected, ids(search("patient", SARAH)).stream().sorted().toList());
        assertEquals(expected, ids(search("patient", "patient-sarah-okonkwo")).stream().sorted().toList());
        assertEquals(expected, ids(search("patient", "https://other.example/fhir/" + SARAH)).stream().sorted().toList());
        assertEquals(2, total(search("patient", "Patient/patient-frank-castle")));
        assertEquals(List.of("eob-timothy-dental-claims-sharing"), ids(search("patient", "Patient/patient-timothy-jones")));
        assertEquals(0, total(search("patient", "Patient/nobody")));
    }

    @Test
    void theIdentifierParameterAcceptsEveryTokenForm() throws Exception {
        String sys = "urn:ohia:claims-sharing-id";
        assertEquals(List.of("eob-timothy-dental-claims-sharing"), ids(search("identifier", sys + "|CS-2026-UC03-001")));
        assertEquals(List.of("eob-timothy-dental-claims-sharing"), ids(search("identifier", "CS-2026-UC03-001")));
        assertEquals(5, total(search("identifier", sys + "|")), "system only: any value");
        assertEquals(0, total(search("identifier", "|CS-2026-UC03-001")), "empty system means an identifier with no system");
        assertEquals(0, total(search("identifier", "urn:other|CS-2026-UC03-001")));
    }

    @Test
    void statusAndIdSupportOrAndRepeatedParametersAreAnded() throws Exception {
        assertEquals(5, total(search("status", "draft")));
        assertEquals(0, total(search("status", "active")));
        assertEquals(5, total(search("status", "draft,active")), "a comma means OR");
        assertEquals(2, total(search("_id", "eob-d7210-claims-sharing,eob-timothy-dental-claims-sharing")));
        assertEquals(0, total(search("_id", "eob-d7210-claims-sharing", "_id", "eob-timothy-dental-claims-sharing")), "a repeat means AND");
    }

    @Test
    void parametersCombine() throws Exception {
        assertEquals(List.of("eob-inoffice-claims-sharing"), ids(search("patient", SARAH, "identifier", "CS-2026-UC04A-INOFFICE")));
        assertEquals(0, total(search("patient", SARAH, "identifier", "CS-2026-UC03-001")));
    }

    @Test
    void countLimitsThePageButNotTheTotal() throws Exception {
        mvc.perform(search("_count", "2")).andExpect(jsonPath("$.total").value(5)).andExpect(jsonPath("$.entry.length()").value(2));
        mvc.perform(search("_count", "0")).andExpect(jsonPath("$.total").value(5)).andExpect(jsonPath("$.entry").isEmpty());
    }

    @Test
    void badCountIsRefusedNotIgnored() throws Exception {
        for (String bad : new String[] {"101", "-1", "x", ""}) {
            mvc.perform(search("_count", bad)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.resourceType").value("OperationOutcome"));
        }
        mvc.perform(search("_count", "1", "_count", "2")).andExpect(status().isBadRequest());
    }

    /** Control: a question we do not understand must never come back as "no results". */
    @Test
    void anUnsupportedParameterIs400() throws Exception {
        for (String name : new String[] {"name", "_sort", "patient:missing", "status:not", "provider"}) {
            mvc.perform(search(name, "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.issue[0].code").value("not-supported"))
                    .andExpect(jsonPath("$.issue[0].details.text").value(org.hamcrest.Matchers.containsString("'" + name + "'")));
        }
    }

    @Test
    void formatJsonIsAcceptedAndOtherFormatsAreRefused() throws Exception {
        mvc.perform(search("_format", "json")).andExpect(status().isOk());
        mvc.perform(search("_format", "application/fhir+json")).andExpect(status().isOk());
        mvc.perform(search("_format", "xml")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ access

    /** Control: the path is under /fhir, not /ode, so only the filter's EOB rule keeps it locked. */
    @Test
    void everyRequestNeedsTheSharedToken() throws Exception {
        for (String path : new String[] {EOB, EOB + "/eob-inoffice-claims-sharing", EOB + "/nope"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
            mvc.perform(get(path).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "bearer " + TOKEN)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void writesAreRefusedAndNothingIsCreated() throws Exception {
        String auth = "Bearer " + TOKEN;
        mvc.perform(post(EOB).header("Authorization", auth).contentType("application/fhir+json").content("{\"resourceType\":\"ExplanationOfBenefit\"}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(put(EOB + "/eob-inoffice-claims-sharing").header("Authorization", auth).contentType("application/fhir+json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(EOB + "/eob-inoffice-claims-sharing").header("Authorization", auth)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(EOB)).andExpect(status().isUnauthorized()); // no token: the lock answers first
        assertEquals(5, total(search()));
    }

    @Test
    void theOtherFhirPathsAreNotTouched() throws Exception {
        // Not served by the facade: nothing here answers /fhir/Patient (Caddy sends that to HAPI).
        mvc.perform(get("/fhir/Patient").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isNotFound());
        mvc.perform(get("/fhir/Patient")).andExpect(status().isNotFound());
    }

    private static Path newTempDir() {
        try {
            return Files.createTempDirectory("oral-eob-endpoint");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theServedSetIsExactlyTheFiveFixtureFiles() {
        assertTrue(FILES.stream().allMatch(f -> OralEobFixtures.fixture(f) != null), "every configured file has a test copy");
    }
}
