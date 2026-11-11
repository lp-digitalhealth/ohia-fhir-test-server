package org.ohia.facade.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ohia.facade.config.FacadeProperties;
import org.ohia.facade.patientaccess.PatientAccessSearch;
import org.ohia.facade.patientaccess.PatientAccessSeedService;
import org.ohia.facade.patientaccess.PatientAccessStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The FHIR base for patient apps: {@code /patient-access/fhir}. It serves the payer's own view of a member (CARIN Blue
 * Button 2.2.0 profiled Patient, Coverage, Organization, Practitioner and ExplanationOfBenefit), read-only, apart from the
 * providers' resources at {@code /fhir}. A patient app finds the member by the payer's online member ID
 * ({@code Patient?identifier=<system>|<value>}) and reads Coverage and ExplanationOfBenefit from there.
 *
 * <p>The shared token protects every path except the capability statement (see PatientAccessTokenFilter), until a SMART
 * authorization server exists. The data is test data loaded by an organizer with {@code POST /ode/admin/seed-patient-access}.
 */
@RestController
public class PatientAccessController {

    static final String BASE = "/patient-access/fhir";
    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");

    private final PatientAccessStore store;
    private final PatientAccessSeedService seedService;
    private final ObjectMapper mapper;
    private final String publicBase;

    public PatientAccessController(PatientAccessStore store, PatientAccessSeedService seedService, ObjectMapper mapper, FacadeProperties props) {
        this.store = store;
        this.seedService = seedService;
        this.mapper = mapper;
        this.publicBase = props.publicBaseUrl().replaceAll("/+$", "") + BASE;
    }

    @GetMapping(BASE + "/{type}/{id}")
    public ResponseEntity<JsonNode> read(@PathVariable String type, @PathVariable String id) {
        if (!PatientAccessSearch.TYPES.contains(type)) {
            return unsupportedType(type);
        }
        var snapshot = store.current();
        if (snapshot.isEmpty()) {
            return outcome(HttpStatus.NOT_FOUND, "not-found", "error", NOT_LOADED);
        }
        return snapshot.get().find(type, id).<ResponseEntity<JsonNode>>map(this::ok)
                .orElseGet(() -> outcome(HttpStatus.NOT_FOUND, "not-found", "error", type + "/" + id + " is not available here."));
    }

    @GetMapping(BASE + "/{type}")
    public ResponseEntity<JsonNode> search(@PathVariable String type, HttpServletRequest request) {
        if (!PatientAccessSearch.TYPES.contains(type)) {
            return unsupportedType(type);
        }
        Map<String, List<String>> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, Arrays.asList(v)));
        var snapshot = store.current();
        PatientAccessSearch.Result result = PatientAccessSearch.search(type, snapshot.map(s -> s.ofType(type)).orElse(List.of()), params);

        ObjectNode bundle = mapper.createObjectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "searchset");
        bundle.put("total", result.total());
        String query = request.getQueryString();
        bundle.putArray("link").addObject().put("relation", "self").put("url", publicBase + "/" + type + (query == null || query.isBlank() ? "" : "?" + query));
        ArrayNode entries = bundle.putArray("entry");
        for (JsonNode r : result.page()) {
            ObjectNode e = entries.addObject();
            e.put("fullUrl", publicBase + "/" + type + "/" + r.path("id").asText());
            e.set("resource", r);
            e.putObject("search").put("mode", "match");
        }
        if (snapshot.isEmpty()) { // an empty answer here means "nothing loaded", not "no match": say so
            ObjectNode e = entries.addObject();
            e.set("resource", outcomeBody("information", "informational", NOT_LOADED));
            e.putObject("search").put("mode", "outcome");
        }
        return ok(bundle);
    }

    /** Organizer only (shared token, under /ode/**): load or reload the payer-side resources from the pinned data commit. */
    @PostMapping("/ode/admin/seed-patient-access")
    public PatientAccessSeedService.SeedResult seed() {
        return seedService.seed();
    }

    @ExceptionHandler(PatientAccessSearch.BadSearch.class)
    ResponseEntity<JsonNode> badSearch(PatientAccessSearch.BadSearch e) {
        return outcome(HttpStatus.BAD_REQUEST, "not-supported", "error", e.getMessage());
    }

    private static final String NOT_LOADED = "The payer-side data is not loaded yet. An organizer loads it with POST /ode/admin/seed-patient-access.";

    private ResponseEntity<JsonNode> unsupportedType(String type) {
        return outcome(HttpStatus.NOT_FOUND, "not-supported", "error", "This base serves only Patient, Coverage, Organization, Practitioner and ExplanationOfBenefit, not " + type + ".");
    }

    private ResponseEntity<JsonNode> ok(JsonNode body) {
        return ResponseEntity.ok().contentType(FHIR_JSON).cacheControl(CacheControl.noCache()).body(body);
    }

    private ResponseEntity<JsonNode> outcome(HttpStatus status, String code, String severity, String text) {
        return ResponseEntity.status(status).contentType(FHIR_JSON).body(outcomeBody(severity, code, text));
    }

    private ObjectNode outcomeBody(String severity, String code, String text) {
        ObjectNode o = mapper.createObjectNode();
        o.put("resourceType", "OperationOutcome");
        ObjectNode issue = o.putArray("issue").addObject();
        issue.put("severity", severity);
        issue.put("code", code);
        issue.putObject("details").put("text", text);
        return o;
    }
}
