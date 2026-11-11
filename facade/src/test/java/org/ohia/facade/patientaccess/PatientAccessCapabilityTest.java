package org.ohia.facade.patientaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The patient-app base's CapabilityStatement: valid, honest, matching the code, and carrying what the Blue Button IG requires. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"facade.api-token=unit-test-token-123", "facade.public-base-url=https://ohia.test/"})
class PatientAccessCapabilityTest {

    /** The search parameters the CARIN Blue Button 2.2.0 CapabilityStatement requires, per type. */
    static final Map<String, Set<String>> IG_REQUIRED = Map.of(
            "ExplanationOfBenefit", Set.of("_id", "patient", "_lastUpdated", "type", "identifier", "service-date", "service-start-date", "billable-period-start"),
            "Coverage", Set.of("_id", "_lastUpdated"), "Organization", Set.of("_id", "_lastUpdated"),
            "Patient", Set.of("_id", "_lastUpdated"), "Practitioner", Set.of("_id", "_lastUpdated"));

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    String body;
    JsonNode cs;

    @BeforeEach
    void load() throws Exception {
        body = mvc.perform(get("/patient-access/fhir/metadata")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        cs = mapper.readTree(body);
    }

    private JsonNode resource(String type) {
        for (JsonNode r : cs.at("/rest/0/resource")) {
            if (type.equals(r.get("type").asText())) {
                return r;
            }
        }
        throw new AssertionError(type + " is not declared");
    }

    @Test
    void isAValidDraftTestStatementWithThePublicAddressFilledIn() {
        assertEquals("CapabilityStatement", cs.get("resourceType").asText());
        assertEquals("4.0.1", cs.get("fhirVersion").asText());
        for (String required : new String[] {"status", "date", "kind", "fhirVersion", "format"}) {
            assertTrue(cs.has(required), "missing " + required);
        }
        assertEquals("draft", cs.get("status").asText());
        assertTrue(cs.get("experimental").asBoolean());
        assertFalse(body.contains("{{"), "no unreplaced placeholder");
        assertEquals("https://ohia.test/patient-access/fhir", cs.at("/implementation/url").asText());
        assertEquals("http://hl7.org/fhir/us/carin-bb/ImplementationGuide/hl7.fhir.us.carin-bb|2.2.0", cs.at("/implementationGuide/0").asText());
    }

    @Test
    void servesExactlyTheFiveTypesAsReadAndSearchOnlyWithPinnedProfiles() {
        assertEquals(IG_REQUIRED.keySet(), new HashSet<>(cs.at("/rest/0/resource").findValuesAsText("type").stream().filter(IG_REQUIRED::containsKey).toList()));
        assertEquals(5, cs.at("/rest/0/resource").size());
        for (String type : IG_REQUIRED.keySet()) {
            Set<String> interactions = new HashSet<>();
            resource(type).get("interaction").forEach(i -> interactions.add(i.get("code").asText()));
            assertEquals(Set.of("read", "search-type"), interactions, type + ": no create, update, delete or vread");
            assertTrue(resource(type).at("/supportedProfile/0").asText().startsWith("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-"));
            assertTrue(resource(type).at("/supportedProfile/0").asText().endsWith("|2.2.0"));
        }
    }

    /** The IG requires these search parameters; a server that declared fewer would not be a Blue Button server. */
    @Test
    void declaresEverySearchParameterTheBlueButtonIgRequires() {
        for (Map.Entry<String, Set<String>> e : IG_REQUIRED.entrySet()) {
            Set<String> declared = new HashSet<>();
            resource(e.getKey()).get("searchParam").forEach(p -> declared.add(p.get("name").asText()));
            assertTrue(declared.containsAll(e.getValue()), e.getKey() + " lacks " + e.getValue().stream().filter(x -> !declared.contains(x)).toList());
        }
    }

    /** Control: the statement must say exactly what the code accepts, no more and no less. */
    @Test
    void theDeclaredSearchParametersAreExactlyWhatTheSearchCodeSupports() {
        for (String type : IG_REQUIRED.keySet()) {
            Set<String> declared = new HashSet<>();
            resource(type).get("searchParam").forEach(p -> declared.add(p.get("name").asText()));
            Set<String> coded = new HashSet<>(PatientAccessSearch.supported(type));
            coded.removeAll(Set.of("_count", "_format")); // paging and format controls are not search parameters
            assertEquals(coded, declared, type);
        }
    }

    @Test
    void saysWhatIsAndIsNotImplementedAndClaimsNoSecurityService() {
        String docs = cs.at("/rest/0/documentation").asText();
        assertTrue(docs.contains("IMPLEMENTED TODAY") && docs.contains("NOT IMPLEMENTED"), "it must say which is which");
        for (String missing : new String[] {"SMART", "vread", "_include", "guardian"}) {
            assertTrue(docs.contains(missing), "the statement must name what is missing: " + missing);
        }
        assertFalse(body.contains("oauth-uris"), "no OAuth address: nothing here issues FHIR tokens");
        assertFalse(cs.at("/rest/0/security").has("service"), "no security service claimed");
        assertTrue(cs.at("/rest/0/security/description").asText().contains("shared token"));
    }
}
