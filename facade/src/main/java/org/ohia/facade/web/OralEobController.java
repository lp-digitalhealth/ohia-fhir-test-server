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
import org.ohia.facade.eob.OralEobSearch;
import org.ohia.facade.eob.OralEobSeedService;
import org.ohia.facade.eob.OralEobStore;
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
 * Read and search of the oral claims-sharing ExplanationOfBenefit resources (the ODE oral profile, derived from CARIN
 * Blue Button), at the FHIR base: {@code GET /fhir/ExplanationOfBenefit[/{id}]}. Read-only by design: the resources are
 * test data loaded by an organizer with {@code POST /ode/admin/seed-eob}, never created by a caller.
 *
 * <p>Caddy sends these paths here instead of to HAPI, and the shared token is checked at both places (see
 * OdeTokenFilter). The statement at {@code /fhir/metadata} advertises read and search-type for this type.
 */
@RestController
public class OralEobController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");
    private static final String PATH = "/fhir/ExplanationOfBenefit";

    private final OralEobStore store;
    private final OralEobSeedService seedService;
    private final ObjectMapper mapper;
    private final String base;

    public OralEobController(OralEobStore store, OralEobSeedService seedService, ObjectMapper mapper, FacadeProperties props) {
        this.store = store;
        this.seedService = seedService;
        this.mapper = mapper;
        this.base = props.publicBaseUrl().replaceAll("/+$", "");
    }

    @GetMapping(PATH + "/{id}")
    public ResponseEntity<JsonNode> read(@PathVariable String id) {
        var snapshot = store.current();
        if (snapshot.isEmpty()) {
            return outcome(HttpStatus.NOT_FOUND, "not-found", "No oral EOBs are loaded yet. An organizer loads them with POST /ode/admin/seed-eob.");
        }
        return snapshot.get().resources().stream().filter(r -> id.equals(r.path("id").asText())).findFirst()
                .<ResponseEntity<JsonNode>>map(r -> ok(r))
                .orElseGet(() -> outcome(HttpStatus.NOT_FOUND, "not-found", "ExplanationOfBenefit/" + id + " is not one of the loaded oral EOBs."));
    }

    @GetMapping(PATH)
    public ResponseEntity<JsonNode> search(HttpServletRequest request) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, Arrays.asList(v)));

        ObjectNode bundle = mapper.createObjectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "searchset");
        var snapshot = store.current();
        OralEobSearch.Result result = OralEobSearch.search(snapshot.map(OralEobStore.Snapshot::resources).orElse(List.of()), params);
        bundle.put("total", result.total());
        String query = request.getQueryString();
        ObjectNode self = bundle.putArray("link").addObject();
        self.put("relation", "self");
        self.put("url", base + PATH + (query == null || query.isBlank() ? "" : "?" + query));
        ArrayNode entries = bundle.putArray("entry");
        for (JsonNode r : result.page()) {
            ObjectNode e = entries.addObject();
            e.put("fullUrl", base + PATH + "/" + r.path("id").asText());
            e.set("resource", r);
            e.putObject("search").put("mode", "match");
        }
        if (snapshot.isEmpty()) { // an empty answer here means "nothing loaded", not "no match": say so
            ObjectNode e = entries.addObject();
            e.set("resource", outcomeBody("information", "informational",
                    "No oral EOBs are loaded yet. An organizer loads them with POST /ode/admin/seed-eob."));
            e.putObject("search").put("mode", "outcome");
        }
        return ok(bundle);
    }

    /** Organizer only (shared token, under /ode/**): load or reload the oral EOBs from the pinned data commit. */
    @PostMapping("/ode/admin/seed-eob")
    public OralEobSeedService.SeedResult seed() {
        return seedService.seed();
    }

    @ExceptionHandler(OralEobSearch.BadSearch.class)
    ResponseEntity<JsonNode> badSearch(OralEobSearch.BadSearch e) {
        return outcome(HttpStatus.BAD_REQUEST, "not-supported", e.getMessage());
    }

    private ResponseEntity<JsonNode> ok(JsonNode body) {
        return ResponseEntity.ok().contentType(FHIR_JSON).cacheControl(CacheControl.noCache()).body(body);
    }

    private ResponseEntity<JsonNode> outcome(HttpStatus status, String code, String text) {
        return ResponseEntity.status(status).contentType(FHIR_JSON).body(outcomeBody("error", code, text));
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
