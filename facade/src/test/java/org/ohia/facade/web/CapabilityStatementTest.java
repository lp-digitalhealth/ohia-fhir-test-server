package org.ohia.facade.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The test server's CapabilityStatement: it must be valid, honest about what is and is not implemented, and open. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"facade.api-token=unit-test-token-123", "facade.public-base-url=https://ohia.test/"})
class CapabilityStatementTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    String body;
    JsonNode cs;

    @BeforeEach
    void load() throws Exception {
        body = mvc.perform(get("/fhir/metadata")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        cs = mapper.readTree(body);
    }

    @Test
    void isServedWithNoTokenAsFhirJson() throws Exception {
        String type = mvc.perform(get("/fhir/metadata")).andReturn().getResponse().getContentType();
        assertTrue(type.startsWith("application/fhir+json"), type);
        assertEquals("CapabilityStatement", cs.get("resourceType").asText());
        assertEquals("4.0.1", cs.get("fhirVersion").asText());
        assertEquals("server", cs.at("/rest/0/mode").asText());
    }

    @Test
    void hasTheElementsFhirRequiresAndIsMarkedAsADraftTestStatement() {
        for (String required : new String[] {"status", "date", "kind", "fhirVersion", "format"}) {
            assertTrue(cs.has(required), "missing " + required);
        }
        assertEquals("draft", cs.get("status").asText());
        assertTrue(cs.get("experimental").asBoolean(), "a test server's statement is experimental");
        assertTrue(cs.get("description").asText().toLowerCase().contains("test server"));
        String docs = cs.at("/rest/0/documentation").asText();
        assertTrue(docs.contains("IMPLEMENTED TODAY") && docs.contains("TARGET, NOT IMPLEMENTED YET"), "it must say which is which");
    }

    @Test
    void theDeploymentsPublicAddressIsFilledInWithNoPlaceholderLeft() {
        assertFalse(body.contains("{{"), "no unreplaced placeholder");
        assertEquals("https://ohia.test/fhir", cs.at("/implementation/url").asText().replace("https://ohia.test//", "https://ohia.test/"));
        assertTrue(cs.at("/rest/0/documentation").asText().contains("https://ohia.test/ode/receive/"));
        assertFalse(body.contains("https://ohia.test//"), "no doubled slash from a trailing slash in the setting");
    }

    @Test
    void advertisesTheTransactionAndTheArexProfiles() {
        boolean transaction = false;
        for (JsonNode i : cs.at("/rest/0/interaction")) {
            transaction |= "transaction".equals(i.get("code").asText());
        }
        assertTrue(transaction);
        String profiles = cs.at("/rest/0/resource").toString();
        for (String p : new String[] {"arex-manifest", "arex-content-entry", "arex-provenance"}) {
            assertTrue(profiles.contains("http://fhir.onyxhealth.io/us/arex/StructureDefinition/" + p), p);
        }
    }

    /** Control: if a resource type of the data we load or send is missing from the statement, a client would be told it is unsupported. */
    @Test
    void coversEveryResourceTypeInTheUc07AndUc04aPackages() throws Exception {
        Set<String> declared = new HashSet<>();
        cs.at("/rest/0/resource").forEach(r -> declared.add(r.get("type").asText()));
        for (String file : new String[] {"/uc07-i1/interaction-01-bundle.json", "/uc04a-i1/interaction-01-bundle.json"}) {
            try (InputStream in = CapabilityStatementTest.class.getResourceAsStream(file)) {
                for (JsonNode e : mapper.readTree(in).get("entry")) {
                    String t = e.get("resource").get("resourceType").asText();
                    assertTrue(declared.contains(t), t + " in " + file + " is not declared");
                }
            }
        }
    }

    /** The statement must not point clients at an OAuth address that cannot take FHIR calls. */
    @Test
    void declaresNoOAuthTokenAddressOrSecurityServiceForTheFhirBase() {
        assertFalse(body.contains("oauth-uris"), "no oauth-uris extension: nothing here issues FHIR tokens");
        assertFalse(cs.at("/rest/0/security").has("service"), "no security service claimed");
        assertTrue(cs.at("/rest/0/security/description").asText().contains("shared token"));
        assertTrue(cs.at("/rest/0/security/description").asText().contains("does not accept FHIR calls"));
    }

    /** The oral EOBs are read-only: the statement must not promise create or update, and must name the data's profile and its search parameters. */
    @Test
    void declaresTheOralEobAsReadAndSearchOnlyWithItsProfile() {
        JsonNode eob = null;
        for (JsonNode r : cs.at("/rest/0/resource")) {
            if ("ExplanationOfBenefit".equals(r.get("type").asText())) {
                eob = r;
            }
        }
        assertTrue(eob != null, "ExplanationOfBenefit is declared");
        Set<String> interactions = new HashSet<>();
        eob.get("interaction").forEach(i -> interactions.add(i.get("code").asText()));
        assertEquals(Set.of("read", "search-type"), interactions, "read and search only; create and update are not offered");
        assertEquals("https://oralhealthalliance.net/fhir/StructureDefinition/ode-dental-claim", eob.at("/supportedProfile/0").asText());
        Set<String> params = new HashSet<>();
        eob.get("searchParam").forEach(p -> params.add(p.get("name").asText()));
        assertEquals(Set.of("_id", "patient", "identifier", "status"), params);
        assertTrue(eob.get("documentation").asText().contains("no published implementation guide defines it"), "it says the profile is not published");
        assertTrue(cs.at("/rest/0/documentation").asText().contains("ExplanationOfBenefit: read and search only"),
                "the general sentence must not say every type has create and update");
    }
}
