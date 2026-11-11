package org.ohia.facade.eob;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Search over the loaded oral EOBs, as FHIR defines it for the parameters we support:
 * {@code _id}, {@code patient}, {@code identifier}, {@code status} (each a token or reference; a comma means OR, a
 * repeated parameter means AND) and {@code _count} (0 to 100). Anything else is refused, as HAPI does by default, so a
 * client is never told "no results" when it asked a question we did not understand. Pure logic: no state, no I/O.
 */
public final class OralEobSearch {

    public static final int MAX_COUNT = 100;
    public static final int DEFAULT_COUNT = 20;
    static final Set<String> SUPPORTED = Set.of("_id", "patient", "identifier", "status", "_count", "_format");

    /** A search request the server cannot honour. Answered as 400 with an OperationOutcome. */
    public static final class BadSearch extends RuntimeException {
        public BadSearch(String message) {
            super(message);
        }
    }

    public record Result(List<JsonNode> page, int total) {
    }

    private OralEobSearch() {
    }

    public static Result search(List<JsonNode> all, Map<String, List<String>> params) {
        for (String name : params.keySet()) {
            if (!SUPPORTED.contains(name)) {
                throw new BadSearch("The search parameter '" + name + "' is not supported. Supported: _id, patient, identifier, status, _count.");
            }
        }
        int count = count(params.get("_count"));
        format(params.get("_format"));
        List<Predicate<JsonNode>> filters = new ArrayList<>();
        and(params.get("_id"), alt -> r -> alt.equals(r.path("id").asText()), filters);
        and(params.get("status"), alt -> r -> alt.equals(r.path("status").asText()), filters);
        and(params.get("patient"), alt -> {
            String wanted = patientRef(alt);
            return r -> wanted.equals(patientRef(r.path("patient").path("reference").asText("")));
        }, filters);
        and(params.get("identifier"), OralEobSearch::identifierMatch, filters);

        List<JsonNode> matches = all.stream().filter(r -> filters.stream().allMatch(f -> f.test(r))).toList();
        return new Result(matches.stream().limit(count).toList(), matches.size());
    }

    private static int count(List<String> values) {
        if (values == null) {
            return DEFAULT_COUNT;
        }
        if (values.size() != 1) {
            throw new BadSearch("_count may be given only once.");
        }
        try {
            int n = Integer.parseInt(values.get(0).trim());
            if (n < 0 || n > MAX_COUNT) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new BadSearch("_count must be a whole number from 0 to " + MAX_COUNT + ".");
        }
    }

    /** We only ever produce FHIR JSON; asking for anything else is refused rather than ignored. */
    private static void format(List<String> values) {
        if (values == null) {
            return;
        }
        for (String v : values) {
            if (!v.equals("json") && !v.equals("application/fhir+json") && !v.equals("application/json")) {
                throw new BadSearch("_format '" + v + "' is not supported; this server returns FHIR JSON only.");
            }
        }
    }

    /** Each repetition of a parameter must match (AND); within one, any comma-separated alternative may (OR). */
    private static void and(List<String> values, java.util.function.Function<String, Predicate<JsonNode>> alt,
            List<Predicate<JsonNode>> out) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            List<Predicate<JsonNode>> alternatives = Arrays.stream(value.split(",", -1)).map(String::trim)
                    .filter(a -> !a.isEmpty()).map(alt).toList();
            if (alternatives.isEmpty()) {
                throw new BadSearch("A search parameter was given with no value.");
            }
            out.add(r -> alternatives.stream().anyMatch(p -> p.test(r)));
        }
    }

    /** "Patient/x", "x" and "https://host/fhir/Patient/x" all mean the same patient. */
    static String patientRef(String value) {
        int i = value.lastIndexOf("Patient/");
        return i >= 0 ? value.substring(i) : "Patient/" + value;
    }

    /** "system|value", "|value" (no system), "system|" (any value in the system) or just "value". */
    private static Predicate<JsonNode> identifierMatch(String token) {
        int bar = token.indexOf('|');
        String system = bar < 0 ? null : token.substring(0, bar);
        String value = bar < 0 ? token : token.substring(bar + 1);
        return r -> {
            for (JsonNode id : r.path("identifier")) {
                boolean systemOk = system == null || (system.isEmpty() ? !id.has("system") : system.equals(id.path("system").asText()));
                boolean valueOk = value.isEmpty() || value.equals(id.path("value").asText());
                if (systemOk && valueOk) {
                    return true;
                }
            }
            return false;
        };
    }
}
