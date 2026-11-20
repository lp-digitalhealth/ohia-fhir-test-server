package org.ohia.facade.patientaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.ohia.facade.patientaccess.PatientAccessFixtures.BASE;
import static org.ohia.facade.patientaccess.PatientAccessFixtures.ONLINE_ID;
import static org.ohia.facade.patientaccess.PatientAccessFixtures.TOKEN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The patient-app FHIR base: how a patient app finds the member and reads the payer's view of the claims. */
@SpringBootTest
@AutoConfigureMockMvc
class PatientAccessEndpointTest {

    static final Path dataDir = newTempDir();
    static final String PATIENT = "Patient/patient-joe-castle-payer";
    static final String AUTH = "Bearer " + TOKEN;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PatientAccessFixtures.properties(r, dataDir);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void load() throws Exception {
        PatientAccessFixtures.reset();
        mvc.perform(post("/ode/admin/seed-patient-access").header("Authorization", AUTH)).andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder search(String type, String... nameValuePairs) {
        MockHttpServletRequestBuilder b = get(BASE + "/" + type).header("Authorization", AUTH);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            b.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return b;
    }

    private JsonNode bundle(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<String> ids(MockHttpServletRequestBuilder request) throws Exception {
        List<String> out = new ArrayList<>();
        bundle(request).path("entry").forEach(e -> out.add(e.path("resource").path("id").asText()));
        return out;
    }

    private int total(MockHttpServletRequestBuilder request) throws Exception {
        return bundle(request).get("total").asInt();
    }

    // ------------------------------------------------------------------ the patient app's path

    @Test
    void aPatientAppFindsTheMemberByTheOnlineIdAndReadsTheClaims() throws Exception {
        // 1. find the member by the payer's online member ID
        JsonNode found = bundle(search("Patient", "identifier", ONLINE_ID));
        assertEquals(1, found.get("total").asInt());
        String patientRef = "Patient/" + found.at("/entry/0/resource/id").asText();
        assertEquals(PATIENT, patientRef);
        // 2. read the member's coverage and EOBs from that Patient
        assertEquals(List.of("coverage-joe-castle-dentaquest-payer"), ids(search("Coverage", "beneficiary", patientRef)));
        JsonNode eobs = bundle(search("ExplanationOfBenefit", "patient", patientRef));
        assertEquals(2, eobs.get("total").asInt());
        eobs.get("entry").forEach(e -> {
            assertEquals("ExplanationOfBenefit", e.at("/resource/resourceType").asText());
            assertEquals(patientRef, e.at("/resource/patient/reference").asText());
        });
    }

    /** The provider-side record number must not find the member: only the payer's own IDs do. */
    @Test
    void aProviderMedicalRecordNumberFindsNobody() throws Exception {
        assertEquals(0, total(search("Patient", "identifier", "https://pediatricdental.example.org/fhir/mrn|PEDDENT-TX-2026-0019")));
    }

    @Test
    void everyReferenceInEveryServedResourceCanBeReadBack() throws Exception {
        int checked = 0;
        for (String type : new String[] {"Patient", "Coverage", "Organization", "Practitioner", "ExplanationOfBenefit"}) {
            for (JsonNode e : bundle(search(type)).get("entry")) {
                List<String> refs = new ArrayList<>();
                collect(e.get("resource"), refs);
                for (String ref : refs) {
                    mvc.perform(get(BASE + "/" + ref).header("Authorization", AUTH)).andExpect(status().isOk());
                    checked++;
                }
            }
        }
        assertEquals(true, checked > 10, "the served resources do reference each other: " + checked);
    }

    private static void collect(JsonNode n, List<String> out) {
        if (n.isObject()) {
            n.fields().forEachRemaining(f -> {
                if (f.getKey().equals("reference") && f.getValue().isTextual()) {
                    out.add(f.getValue().asText());
                } else {
                    collect(f.getValue(), out);
                }
            });
        } else if (n.isArray()) {
            n.forEach(x -> collect(x, out));
        }
    }

    // ------------------------------------------------------------------ read

    @Test
    void readsEachTypeAsFhirJsonExactlyAsLoaded() throws Exception {
        for (String ref : new String[] {PATIENT, "Coverage/coverage-joe-castle-dentaquest-payer", "Organization/org-dentaquest-tx-payer",
                "Practitioner/pract-max-garcia-payer", "ExplanationOfBenefit/eob-restorative-treatment-c4bb"}) {
            String body = mvc.perform(get(BASE + "/" + ref).header("Authorization", AUTH)).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/fhir+json")).andReturn().getResponse().getContentAsString();
            JsonNode r = mapper.readTree(body);
            assertEquals(ref, r.get("resourceType").asText() + "/" + r.get("id").asText());
        }
    }

    @Test
    void anUnknownIdAndAnUnservedTypeAre404WithAnOperationOutcome() throws Exception {
        mvc.perform(get(BASE + "/Patient/nobody").header("Authorization", AUTH)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome")).andExpect(jsonPath("$.issue[0].code").value("not-found"));
        mvc.perform(get(BASE + "/Observation/x").header("Authorization", AUTH)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.issue[0].code").value("not-supported"));
        mvc.perform(get(BASE + "/Observation").header("Authorization", AUTH)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ search

    @Test
    void searchWithNoParametersReturnsEverythingOfTheTypeAsASearchset() throws Exception {
        assertEquals(1, total(search("Patient")));
        assertEquals(1, total(search("Coverage")));
        assertEquals(3, total(search("Organization")));
        assertEquals(2, total(search("Practitioner")));
        mvc.perform(search("ExplanationOfBenefit")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(jsonPath("$.type").value("searchset")).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.link[0].url").value("https://ohia.test/patient-access/fhir/ExplanationOfBenefit"))
                .andExpect(jsonPath("$.entry[0].fullUrl").value("https://ohia.test/patient-access/fhir/ExplanationOfBenefit/eob-pediatric-evaluation-c4bb"))
                .andExpect(jsonPath("$.entry[0].search.mode").value("match"));
    }

    @Test
    void patientAndBeneficiaryAcceptReferenceIdAndFullUrl() throws Exception {
        for (String v : new String[] {PATIENT, "patient-joe-castle-payer", "https://other.example/fhir/" + PATIENT}) {
            assertEquals(2, total(search("ExplanationOfBenefit", "patient", v)), v);
            assertEquals(1, total(search("Coverage", "beneficiary", v)), v);
            assertEquals(1, total(search("Coverage", "patient", v)), v);
        }
        assertEquals(0, total(search("ExplanationOfBenefit", "patient", "Patient/nobody")));
    }

    @Test
    void identifierTypeAndStatusSearches() throws Exception {
        assertEquals(List.of("eob-restorative-treatment-c4bb"), ids(search("ExplanationOfBenefit", "identifier", "https://dentaquest.example.org/fhir/eob-identifier|DQ-EOB-2026-UC07-002")));
        assertEquals(2, total(search("ExplanationOfBenefit", "identifier", "https://dentaquest.example.org/fhir/eob-identifier|")), "system only: any value");
        assertEquals(0, total(search("ExplanationOfBenefit", "identifier", "|DQ-EOB-2026-UC07-002")), "an empty system means an identifier with no system");
        assertEquals(1, total(search("Patient", "identifier", "http://texas.medicaid.gov/beneficiary|TX-MCD-0038211")));
        assertEquals(List.of("org-dentaquest-tx-payer"), ids(search("Organization", "identifier", "urn:ohia:payer-edi-id|CX014")));
        assertEquals(List.of("org-pediatric-dental-tx-payer"), ids(search("Organization", "identifier", "http://hl7.org/fhir/sid/us-npi|1847736208")));
        assertEquals(List.of("pract-mary-parker-payer"), ids(search("Practitioner", "identifier", "http://hl7.org/fhir/sid/us-npi|1720384753")));
        assertEquals(2, total(search("ExplanationOfBenefit", "type", "http://terminology.hl7.org/CodeSystem/claim-type|oral")));
        assertEquals(2, total(search("ExplanationOfBenefit", "type", "oral")));
        assertEquals(0, total(search("ExplanationOfBenefit", "type", "|oral")));
        assertEquals(0, total(search("ExplanationOfBenefit", "type", "professional")));
        assertEquals(2, total(search("ExplanationOfBenefit", "status", "active")));
        assertEquals(0, total(search("ExplanationOfBenefit", "status", "draft")));
        assertEquals(2, total(search("ExplanationOfBenefit", "status", "draft,active")), "a comma means OR");
        assertEquals(0, total(search("ExplanationOfBenefit", "_id", "eob-pediatric-evaluation-c4bb", "_id", "eob-restorative-treatment-c4bb")), "a repeat means AND");
    }

    /** The pediatric claim was served on 2026-07-08 and the restorative one on 2026-07-22. */
    @Test
    void serviceDateSearchFollowsTheFhirPrefixRules() throws Exception {
        String ped = "eob-pediatric-evaluation-c4bb", res = "eob-restorative-treatment-c4bb";
        assertEquals(List.of(res), ids(search("ExplanationOfBenefit", "service-date", "ge2026-07-22")));
        assertEquals(List.of(ped), ids(search("ExplanationOfBenefit", "service-date", "le2026-07-08")));
        assertEquals(List.of(res), ids(search("ExplanationOfBenefit", "service-date", "gt2026-07-08")));
        assertEquals(List.of(ped), ids(search("ExplanationOfBenefit", "service-date", "lt2026-07-22")));
        assertEquals(List.of(ped), ids(search("ExplanationOfBenefit", "service-date", "2026-07-08")));
        assertEquals(2, total(search("ExplanationOfBenefit", "service-date", "2026-07")), "a month search value covers the whole month");
        assertEquals(2, total(search("ExplanationOfBenefit", "service-date", "2026")));
        assertEquals(0, total(search("ExplanationOfBenefit", "service-date", "2026-08")));
        assertEquals(2, total(search("ExplanationOfBenefit", "service-date", "ge2026-07-01", "service-date", "le2026-07-31")), "two bounds are ANDed");
        assertEquals(List.of(res), ids(search("ExplanationOfBenefit", "service-start-date", "ge2026-07-22")));
        assertEquals(List.of(ped), ids(search("ExplanationOfBenefit", "billable-period-start", "2026-07-08")));
    }

    @Test
    void lastUpdatedSearchComparesInstants() throws Exception {
        String stamp = "2026-07-28T06:00:00-05:00";
        assertEquals(2, total(search("ExplanationOfBenefit", "_lastUpdated", "ge" + stamp)));
        assertEquals(0, total(search("ExplanationOfBenefit", "_lastUpdated", "gt" + stamp)));
        assertEquals(0, total(search("ExplanationOfBenefit", "_lastUpdated", "lt" + stamp)));
        assertEquals(2, total(search("ExplanationOfBenefit", "_lastUpdated", "le2026-07-29")));
        assertEquals(0, total(search("ExplanationOfBenefit", "_lastUpdated", "ge2026-07-29")));
        assertEquals(1, total(search("Patient", "_lastUpdated", "ge2026-07-28")));
    }

    @Test
    void countLimitsThePageButNotTheTotal() throws Exception {
        mvc.perform(search("ExplanationOfBenefit", "_count", "1")).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.entry.length()").value(1));
        mvc.perform(search("ExplanationOfBenefit", "_count", "0")).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.entry").isEmpty());
        for (String bad : new String[] {"101", "-1", "x", ""}) {
            mvc.perform(search("ExplanationOfBenefit", "_count", bad)).andExpect(status().isBadRequest());
        }
        mvc.perform(search("ExplanationOfBenefit", "_count", "1", "_count", "2")).andExpect(status().isBadRequest());
    }

    /** Control: a question we do not understand must never come back as "no results". */
    @Test
    void anUnsupportedParameterOrValueIs400() throws Exception {
        String[][] bad = {{"Patient", "name", "Castle"}, {"ExplanationOfBenefit", "_include", "ExplanationOfBenefit:patient"},
                {"Coverage", "service-date", "2026"}, {"ExplanationOfBenefit", "patient:exact", "x"}, {"Organization", "name", "x"},
                {"ExplanationOfBenefit", "_sort", "_id"}, {"ExplanationOfBenefit", "service-date", "ne2026-07-08"},
                {"ExplanationOfBenefit", "service-date", "banana"}, {"ExplanationOfBenefit", "_lastUpdated", "2026-07-28T06:00:00"},
                {"ExplanationOfBenefit", "_format", "xml"}, {"ExplanationOfBenefit", "status", ""}};
        for (String[] b : bad) {
            mvc.perform(search(b[0], b[1], b[2])).andExpect(status().isBadRequest()).andExpect(jsonPath("$.resourceType").value("OperationOutcome"));
        }
        mvc.perform(search("Patient", "name", "x")).andExpect(jsonPath("$.issue[0].code").value("not-supported"))
                .andExpect(jsonPath("$.issue[0].details.text").value(Matchers.containsString("'name'")));
        mvc.perform(search("ExplanationOfBenefit", "_format", "json")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ access

    /** Control: only the filter's rule for this base keeps these paths locked; the base is not under /ode. */
    @Test
    void everyRequestExceptTheCapabilityStatementNeedsTheSharedToken() throws Exception {
        for (String path : new String[] {BASE + "/Patient", BASE + "/Patient/patient-joe-castle-payer", BASE + "/ExplanationOfBenefit", BASE + "/Observation", BASE + "/Patient/nobody"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.resourceType").value("OperationOutcome"));
            mvc.perform(get(path).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "bearer " + TOKEN)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", AUTH + "X")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void theCapabilityStatementNeedsNoToken() throws Exception {
        mvc.perform(get(BASE + "/metadata")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(jsonPath("$.resourceType").value("CapabilityStatement"));
    }

    @Test
    void writesAreRefusedAndNothingIsCreated() throws Exception {
        mvc.perform(post(BASE + "/Patient").header("Authorization", AUTH).contentType("application/fhir+json").content("{\"resourceType\":\"Patient\"}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(put(BASE + "/Patient/patient-joe-castle-payer").header("Authorization", AUTH).contentType("application/fhir+json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(BASE + "/Patient/patient-joe-castle-payer").header("Authorization", AUTH)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(BASE + "/Patient")).andExpect(status().isUnauthorized()); // no token: the lock answers first
        assertEquals(1, total(search("Patient")));
    }

    @Test
    void aBrowserPatientAppsPreflightIsAnsweredWithoutAToken() throws Exception {
        mvc.perform(options(BASE + "/Patient").header("Origin", "https://app.example.org").header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")).andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.org"))
                .andExpect(header().string("Access-Control-Allow-Headers", Matchers.containsStringIgnoringCase("Authorization")));
    }

    private static Path newTempDir() {
        try {
            return Files.createTempDirectory("patient-access-endpoint");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
