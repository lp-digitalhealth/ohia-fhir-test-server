package org.ohia.facade.eob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.ohia.facade.common.BadDataException;
import org.ohia.facade.config.OralEobProperties;
import org.ohia.facade.seed.RemoteFetcher;
import org.springframework.stereotype.Service;

/**
 * Loads the oral claims-sharing EOBs from the data repository at a pinned commit.
 *
 * <p>Everything is fetched and checked first and only then stored, so a missing file, a wrong profile or a duplicate id
 * changes nothing: the previous set stays in place. Loading twice is harmless (the set is replaced as a whole).
 *
 * <p>What is checked is what the data itself promises, not a published profile: no published implementation guide
 * defines {@link #PROFILE} (the ODE IG removed its EOB profile), so this is a consistency check, not profile validation.
 */
@Service
public class OralEobSeedService {

    /** The profile URL the fixtures declare. Nothing publishes a StructureDefinition at this address. */
    public static final String PROFILE = "https://oralhealthalliance.net/fhir/StructureDefinition/ode-dental-claim";
    static final String CLAIM_TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/claim-type";
    private static final int MAX_BYTES = 1_000_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");
    private static final Pattern PATIENT_REF = Pattern.compile("Patient/[A-Za-z0-9\\-.]{1,64}");

    public record SeedResult(String ref, int resources, List<String> ids) {
    }

    private final OralEobProperties props;
    private final RemoteFetcher fetcher;
    private final OralEobStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OralEobSeedService(OralEobProperties props, RemoteFetcher fetcher, OralEobStore store, ObjectMapper mapper,
            Clock clock) {
        this.props = props;
        this.fetcher = fetcher;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    public SeedResult seed() {
        if (props.files().isEmpty()) {
            throw new BadDataException("No oral EOB files are configured (facade.eob.files).");
        }
        List<JsonNode> resources = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String path : props.files()) {
            String url = strip(props.dataBaseUrl()) + "/" + props.dataRef() + "/" + strip(path);
            JsonNode resource = parse(fetcher.fetch(url, MAX_BYTES), path);
            validate(resource, path);
            if (!ids.add(resource.get("id").asText())) {
                throw new BadDataException("Two files carry the same EOB id '" + resource.get("id").asText() + "'; " + path);
            }
            resources.add(resource);
        }
        store.replace(props.dataRef(), resources, clock.instant());
        return new SeedResult(props.dataRef(), resources.size(), resources.stream().map(r -> r.get("id").asText()).toList());
    }

    private JsonNode parse(byte[] bytes, String path) {
        try {
            return mapper.readTree(bytes);
        } catch (IOException e) {
            throw new BadDataException(path + " is not valid JSON.");
        }
    }

    static void validate(JsonNode r, String where) {
        if (!"ExplanationOfBenefit".equals(r.path("resourceType").asText())) {
            throw new BadDataException(where + " is not an ExplanationOfBenefit.");
        }
        if (!ID.matcher(r.path("id").asText("")).matches()) {
            throw new BadDataException(where + " has no usable id (letters, digits, '-' and '.', at most 64).");
        }
        boolean declared = false;
        for (JsonNode p : r.path("meta").path("profile")) {
            declared |= PROFILE.equals(p.asText());
        }
        if (!declared) {
            throw new BadDataException(where + " does not declare the profile " + PROFILE + ".");
        }
        boolean oral = false;
        for (JsonNode c : r.path("type").path("coding")) {
            oral |= CLAIM_TYPE_SYSTEM.equals(c.path("system").asText()) && "oral".equals(c.path("code").asText());
        }
        if (!oral) {
            throw new BadDataException(where + " is not an oral claim (type must be " + CLAIM_TYPE_SYSTEM + "#oral).");
        }
        if (!PATIENT_REF.matcher(r.path("patient").path("reference").asText("")).matches()) {
            throw new BadDataException(where + " needs patient.reference like Patient/<id>.");
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
