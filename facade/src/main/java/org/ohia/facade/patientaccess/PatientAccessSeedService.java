package org.ohia.facade.patientaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.ohia.facade.common.BadDataException;
import org.ohia.facade.config.PatientAccessProperties;
import org.ohia.facade.seed.RemoteFetcher;
import org.springframework.stereotype.Service;

/**
 * Loads the payer-side resources (CARIN Blue Button 2.2.0 profiled Patient, Coverage, Organization, Practitioner and
 * oral ExplanationOfBenefit) from the data repository at a pinned commit.
 *
 * <p>Everything is fetched and checked first and only then stored, so a missing file, an unpinned profile, a dangling
 * reference or a duplicate id changes nothing and the previous set stays in place. Loading twice is harmless.
 *
 * <p>These are consistency checks on the data, not FHIR validation: the HL7 validator is the check for conformance.
 */
@Service
public class PatientAccessSeedService {

    static final String C4BB = "http://hl7.org/fhir/us/carin-bb/StructureDefinition/";
    /** The profile each served type must declare (the EOB profile is matched by prefix: Oral, Oral-Basis and so on). */
    static final Map<String, String> PROFILE = Map.of("Patient", "C4BB-Patient", "Coverage", "C4BB-Coverage", "Organization", "C4BB-Organization",
            "Practitioner", "C4BB-Practitioner", "ExplanationOfBenefit", "C4BB-ExplanationOfBenefit");
    private static final int MAX_BYTES = 1_000_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");
    private static final Pattern RELATIVE_REF = Pattern.compile("([A-Za-z]{1,40})/([A-Za-z0-9\\-.]{1,64})");

    public record SeedResult(String ref, int resources, Map<String, Integer> countsByType) {
    }

    private final PatientAccessProperties props;
    private final RemoteFetcher fetcher;
    private final PatientAccessStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PatientAccessSeedService(PatientAccessProperties props, RemoteFetcher fetcher, PatientAccessStore store, ObjectMapper mapper, Clock clock) {
        this.props = props;
        this.fetcher = fetcher;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    public SeedResult seed() {
        if (props.files().isEmpty()) {
            throw new BadDataException("No patient-access files are configured (facade.patient-access.files).");
        }
        List<JsonNode> resources = new ArrayList<>();
        for (String path : props.files()) {
            String url = strip(props.dataBaseUrl()) + "/" + props.dataRef() + "/" + strip(path);
            JsonNode r = parse(fetcher.fetch(url, MAX_BYTES), path);
            validate(r, path);
            resources.add(r);
        }
        checkSet(resources);
        store.replace(props.dataRef(), resources, clock.instant());
        Map<String, Integer> counts = new TreeMap<>();
        resources.forEach(r -> counts.merge(r.get("resourceType").asText(), 1, Integer::sum));
        return new SeedResult(props.dataRef(), resources.size(), counts);
    }

    private JsonNode parse(byte[] bytes, String path) {
        try {
            return mapper.readTree(bytes);
        } catch (IOException e) {
            throw new BadDataException(path + " is not valid JSON.");
        }
    }

    static void validate(JsonNode r, String where) {
        String type = r.path("resourceType").asText();
        if (!PROFILE.containsKey(type)) {
            throw new BadDataException(where + " is a " + type + ", which this base does not serve (Patient, Coverage, Organization, Practitioner, ExplanationOfBenefit).");
        }
        if (!ID.matcher(r.path("id").asText("")).matches()) {
            throw new BadDataException(where + " has no usable id (letters, digits, '-' and '.', at most 64).");
        }
        boolean pinned = false;
        for (JsonNode p : r.path("meta").path("profile")) {
            String v = p.asText();
            pinned |= v.startsWith(C4BB + PROFILE.get(type)) && v.endsWith("|2.2.0");
        }
        if (!pinned) {
            throw new BadDataException(where + " must declare a CARIN Blue Button " + PROFILE.get(type) + " profile pinned to |2.2.0.");
        }
        if (r.path("meta").path("lastUpdated").asText("").isBlank()) {
            throw new BadDataException(where + " needs meta.lastUpdated (the base serves and searches it).");
        }
    }

    /** Set-level rules: no duplicate Type/id, at least one Patient, and every reference is a relative Type/id inside the set. */
    static void checkSet(List<JsonNode> resources) {
        Set<String> keys = new HashSet<>();
        for (JsonNode r : resources) {
            String key = r.get("resourceType").asText() + "/" + r.get("id").asText();
            if (!keys.add(key)) {
                throw new BadDataException("Two files carry the same resource " + key + ".");
            }
        }
        if (resources.stream().noneMatch(r -> "Patient".equals(r.get("resourceType").asText()))) {
            throw new BadDataException("The set holds no Patient: a patient app has nobody to find.");
        }
        for (JsonNode r : resources) {
            List<String> refs = new ArrayList<>();
            collectReferences(r, refs);
            for (String ref : refs) {
                if (!RELATIVE_REF.matcher(ref).matches() || !keys.contains(ref)) {
                    throw new BadDataException(r.get("resourceType").asText() + "/" + r.get("id").asText() + " refers to '" + ref
                            + "', which is not a Type/id inside the loaded set.");
                }
            }
        }
    }

    private static void collectReferences(JsonNode n, List<String> out) {
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> {
                if (e.getKey().equals("reference") && e.getValue().isTextual()) {
                    out.add(e.getValue().asText());
                } else {
                    collectReferences(e.getValue(), out);
                }
            });
        } else if (n.isArray()) {
            n.forEach(x -> collectReferences(x, out));
        }
    }

    private static String strip(String s) {
        String t = s;
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
