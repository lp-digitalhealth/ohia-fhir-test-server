package org.ohia.facade.patientaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.ohia.facade.common.BadDataException;

/**
 * Two rules the endpoint tests cannot reach: the fixtures have only single-day dates, and every way of removing the Patient
 * from the fixtures trips the dangling-reference rule first. These tests call the logic directly with small hand-made inputs.
 */
class PatientAccessRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void aSetWithNoPatientIsRefusedForThatReasonAlone() throws Exception {
        JsonNode org = json("{\"resourceType\":\"Organization\",\"id\":\"o1\"}");
        BadDataException e = assertThrows(BadDataException.class, () -> PatientAccessSeedService.checkSet(List.of(org)));
        assertTrue(e.getMessage().contains("no Patient"), e.getMessage());
        PatientAccessSeedService.checkSet(List.of(org, json("{\"resourceType\":\"Patient\",\"id\":\"p1\"}"))); // same set plus a Patient is fine
    }

    /** A claim billed over 1-31 July reaches beyond 15 July, so "after 15 July" must find it; one billed 1-10 July does not. */
    @Test
    void greaterThanLooksAtWhereTheTargetEndsNotWhereItStarts() throws Exception {
        JsonNode july = json("{\"resourceType\":\"ExplanationOfBenefit\",\"id\":\"long\",\"billablePeriod\":{\"start\":\"2026-07-01\",\"end\":\"2026-07-31\"}}");
        JsonNode early = json("{\"resourceType\":\"ExplanationOfBenefit\",\"id\":\"early\",\"billablePeriod\":{\"start\":\"2026-07-01\",\"end\":\"2026-07-10\"}}");
        List<JsonNode> both = List.of(july, early);
        assertEquals(List.of("long"), ids(PatientAccessSearch.search("ExplanationOfBenefit", both, Map.of("service-date", List.of("gt2026-07-15")))));
        assertEquals(List.of("long", "early"), ids(PatientAccessSearch.search("ExplanationOfBenefit", both, Map.of("service-date", List.of("lt2026-07-15")))));
    }

    private static List<String> ids(PatientAccessSearch.Result r) {
        return r.page().stream().map(n -> n.get("id").asText()).toList();
    }
}
