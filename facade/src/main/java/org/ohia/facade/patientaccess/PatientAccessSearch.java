package org.ohia.facade.patientaccess;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Search over the loaded payer-side resources, as FHIR defines it for the parameters supported per type. The set is
 * the one the CARIN Blue Button 2.2.0 CapabilityStatement requires (ExplanationOfBenefit: _id, patient, _lastUpdated,
 * type, identifier, service-date, service-start-date, billable-period-start; the others: _id, _lastUpdated), plus the
 * Patient identifier (a patient app finds the member by the payer's online member ID) and a few ordinary ones
 * (Coverage beneficiary, patient and status; identifier on Organization, Practitioner and Coverage; EOB status).
 *
 * <p>A comma means OR, a repeated parameter means AND. Anything else is refused with a clear message, as HAPI does by
 * default, so a client is never told "no results" for a question we did not understand. Pure logic: no state, no I/O.
 */
public final class PatientAccessSearch {

    public static final int MAX_COUNT = 100;
    public static final int DEFAULT_COUNT = 20;
    public static final Set<String> TYPES = Set.of("Patient", "Coverage", "Organization", "Practitioner", "ExplanationOfBenefit");

    private static final Set<String> COMMON = Set.of("_id", "_lastUpdated", "_count", "_format");
    private static final Map<String, Set<String>> OWN = Map.of(
            "Patient", Set.of("identifier"),
            "Coverage", Set.of("beneficiary", "patient", "status", "identifier"),
            "Organization", Set.of("identifier"),
            "Practitioner", Set.of("identifier"),
            "ExplanationOfBenefit", Set.of("patient", "type", "identifier", "status", "service-date", "service-start-date", "billable-period-start"));

    /** A search request the server cannot honour. Answered as 400 with an OperationOutcome. */
    public static final class BadSearch extends RuntimeException {
        public BadSearch(String message) {
            super(message);
        }
    }

    public record Result(List<JsonNode> page, int total) {
    }

    private PatientAccessSearch() {
    }

    public static Set<String> supported(String type) {
        Set<String> all = new java.util.TreeSet<>(COMMON);
        all.addAll(OWN.get(type));
        return all;
    }

    public static Result search(String type, List<JsonNode> ofType, Map<String, List<String>> params) {
        Set<String> allowed = supported(type);
        for (String name : params.keySet()) {
            if (!allowed.contains(name)) {
                throw new BadSearch("The search parameter '" + name + "' is not supported on " + type + ". Supported: " + String.join(", ", allowed) + ".");
            }
        }
        int count = count(params.get("_count"));
        format(params.get("_format"));
        List<Predicate<JsonNode>> filters = new ArrayList<>();
        for (Map.Entry<String, List<String>> p : params.entrySet()) {
            Function<String, Predicate<JsonNode>> maker = maker(type, p.getKey());
            if (maker != null) {
                and(p.getKey(), p.getValue(), maker, filters);
            }
        }
        List<JsonNode> matches = ofType.stream().filter(r -> filters.stream().allMatch(f -> f.test(r))).toList();
        return new Result(matches.stream().limit(count).toList(), matches.size());
    }

    private static Function<String, Predicate<JsonNode>> maker(String type, String name) {
        return switch (name) {
            case "_id" -> alt -> r -> alt.equals(r.path("id").asText());
            case "_lastUpdated" -> alt -> dateMatch(alt, r -> ranges(r.path("meta").path("lastUpdated")));
            case "status" -> alt -> r -> alt.equals(r.path("status").asText());
            case "identifier" -> PatientAccessSearch::identifierMatch;
            case "patient", "beneficiary" -> alt -> {
                String wanted = patientRef(alt);
                String field = type.equals("Coverage") ? "beneficiary" : "patient";
                return r -> wanted.equals(patientRef(r.path(field).path("reference").asText("")));
            };
            case "type" -> PatientAccessSearch::typeMatch;
            case "service-date" -> alt -> dateMatch(alt, r -> {
                List<Range> out = new ArrayList<>(ranges(r.path("billablePeriod")));
                r.path("item").forEach(i -> {
                    out.addAll(ranges(i.path("servicedDate")));
                    out.addAll(ranges(i.path("servicedPeriod")));
                });
                return out;
            });
            case "service-start-date" -> alt -> dateMatch(alt, r -> {
                List<Range> out = new ArrayList<>(ranges(r.path("billablePeriod").path("start")));
                r.path("item").forEach(i -> {
                    out.addAll(ranges(i.path("servicedDate")));
                    out.addAll(ranges(i.path("servicedPeriod").path("start")));
                });
                return out;
            });
            case "billable-period-start" -> alt -> dateMatch(alt, r -> ranges(r.path("billablePeriod").path("start")));
            default -> null; // _count and _format are handled separately
        };
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
    private static void and(String name, List<String> values, Function<String, Predicate<JsonNode>> alt, List<Predicate<JsonNode>> out) {
        for (String value : values) {
            List<Predicate<JsonNode>> alternatives = Arrays.stream(value.split(",", -1)).map(String::trim).filter(a -> !a.isEmpty()).map(alt).toList();
            if (alternatives.isEmpty()) {
                throw new BadSearch("The search parameter '" + name + "' was given with no value.");
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

    /** A token on a CodeableConcept ("system|code", "|code", "system|" or "code") matched against type.coding. */
    private static Predicate<JsonNode> typeMatch(String token) {
        int bar = token.indexOf('|');
        String system = bar < 0 ? null : token.substring(0, bar);
        String code = bar < 0 ? token : token.substring(bar + 1);
        return r -> {
            for (JsonNode c : r.path("type").path("coding")) {
                boolean systemOk = system == null || (system.isEmpty() ? !c.has("system") : system.equals(c.path("system").asText()));
                boolean codeOk = code.isEmpty() || code.equals(c.path("code").asText());
                if (systemOk && codeOk) {
                    return true;
                }
            }
            return false;
        };
    }

    // ------------------------------------------------------------------ dates

    /** A span of time, start inclusive and end exclusive. An open end is Instant.MAX. */
    record Range(Instant start, Instant end) {
    }

    private static Predicate<JsonNode> dateMatch(String token, Function<JsonNode, List<Range>> targets) {
        String prefix = "eq";
        String value = token;
        if (token.length() > 2 && Character.isLetter(token.charAt(0)) && Character.isLetter(token.charAt(1))) {
            prefix = token.substring(0, 2);
            value = token.substring(2);
        }
        if (!Set.of("eq", "gt", "ge", "lt", "le").contains(prefix)) {
            throw new BadSearch("The date prefix '" + prefix + "' is not supported. Supported: eq, gt, ge, lt, le.");
        }
        Range p = parseDate(value);
        String op = prefix;
        return r -> targets.apply(r).stream().anyMatch(t -> {
            boolean eq = !t.start().isBefore(p.start()) && !t.end().isAfter(p.end());
            boolean gt = t.end().isAfter(p.end()); // the target reaches beyond the search value
            boolean lt = t.start().isBefore(p.start());
            return switch (op) {
                case "eq" -> eq;
                case "gt" -> gt;
                case "ge" -> gt || eq;
                case "lt" -> lt;
                default -> lt || eq; // le
            };
        });
    }

    /** The span a search value stands for: "2026" is the whole year, "2026-07" the month, "2026-07-22" the day, a dateTime the second. */
    static Range parseDate(String v) {
        try {
            if (v.matches("\\d{4}")) {
                LocalDate d = LocalDate.of(Integer.parseInt(v), 1, 1);
                return new Range(day(d), day(d.plusYears(1)));
            }
            if (v.matches("\\d{4}-\\d{2}")) {
                YearMonth m = YearMonth.parse(v);
                return new Range(day(m.atDay(1)), day(m.plusMonths(1).atDay(1)));
            }
            if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate d = LocalDate.parse(v);
                return new Range(day(d), day(d.plusDays(1)));
            }
            Instant t = OffsetDateTime.parse(v).toInstant();
            return new Range(t, t.plusSeconds(1));
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new BadSearch("'" + v + "' is not a valid date. Use yyyy, yyyy-MM, yyyy-MM-dd, or a dateTime with a time zone.");
        }
    }

    private static Instant day(LocalDate d) {
        return d.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** The spans a resource value stands for: a date, a dateTime or instant, or a Period (open at a missing end). */
    private static List<Range> ranges(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) {
            return List.of();
        }
        if (n.isObject()) {
            if (!n.has("start") && !n.has("end")) {
                return List.of();
            }
            Instant s = n.has("start") ? parseDate(n.get("start").asText()).start() : Instant.MIN;
            Instant e = n.has("end") ? parseDate(n.get("end").asText()).end() : Instant.MAX;
            return List.of(new Range(s, e));
        }
        return List.of(parseDate(n.asText()));
    }
}
