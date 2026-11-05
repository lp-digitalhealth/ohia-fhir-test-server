package org.ohia.facade.arex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.ohia.facade.send.ManifestRecord;
import org.ohia.facade.send.PerSendCopier;
import org.ohia.facade.seed.PackageStore;
import org.ohia.facade.seed.SeedService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ARex surface under /arex/**: SMART discovery, the token endpoint, and the content server
 * (manifest read, manifest search by transaction identifier, and Binary read with range support).
 * These paths are NOT behind the shared S1 token; they use the tokens issued here.
 *
 * <p>Status rules: no token, an unknown or expired token gets 401 with a Bearer challenge. A valid
 * token that is not entitled (wrong manifest, wrong organization, expired or retracted manifest)
 * gets 404, never 403 (ARex arex-60), so nothing is revealed about what exists.
 */
@RestController
public class ArexController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private final ArexTokenService tokenService;
    private final AccessTokenStore tokens;
    private final ArexAccess access;
    private final PackageStore store;
    private final ObjectMapper mapper;

    public ArexController(ArexTokenService tokenService, AccessTokenStore tokens, ArexAccess access,
            PackageStore store, ObjectMapper mapper) {
        this.tokenService = tokenService;
        this.tokens = tokens;
        this.access = access;
        this.store = store;
        this.mapper = mapper;
    }

    @GetMapping("/arex/.well-known/smart-configuration")
    public Map<String, Object> discovery() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", tokenService.issuer());
        m.put("token_endpoint", tokenService.tokenEndpoint());
        m.put("grant_types_supported", List.of("client_credentials"));
        m.put("token_endpoint_auth_methods_supported", List.of("private_key_jwt"));
        m.put("token_endpoint_auth_signing_alg_values_supported", List.of("RS384", "ES384"));
        m.put("scopes_supported", List.of("system/Binary.r", "system/List.rs"));
        m.put("authorization_details_types_supported", List.of(ArexTokenService.DETAILS_TYPE));
        return m;
    }

    @PostMapping(value = "/arex/auth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam MultiValueMap<String, String> form) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache")
                .body(tokenService.token(form));
    }

    @GetMapping("/arex/List/{manifestId}")
    public ResponseEntity<?> manifest(@PathVariable String manifestId, HttpServletRequest request) {
        AccessTokenStore.TokenInfo token = bearer(request).orElse(null);
        if (token == null) {
            return unauthorized();
        }
        Optional<ManifestRecord> m = entitledTo(token, manifestId);
        return m.isPresent() ? ResponseEntity.ok().contentType(FHIR_JSON).body(m.get().manifest()) : notFound();
    }

    /** Search by the ARex transaction identifier: the answer only ever contains the token's own manifest. */
    @GetMapping("/arex/List")
    public ResponseEntity<?> search(@RequestParam(name = "arex-manifest-transaction-identifier", required = false) String identifier,
            HttpServletRequest request) {
        AccessTokenStore.TokenInfo token = bearer(request).orElse(null);
        if (token == null) {
            return unauthorized();
        }
        if (identifier == null || identifier.isBlank()) {
            return ResponseEntity.badRequest().contentType(FHIR_JSON)
                    .body(outcome("required", "The search parameter arex-manifest-transaction-identifier is required."));
        }
        ObjectNode bundle = mapper.createObjectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "searchset");
        var entries = bundle.putArray("entry");
        Optional<ManifestRecord> m = entitledTo(token, token.manifestId());
        if (m.isPresent() && matchesBusinessIdentifier(m.get().manifest(), identifier)) {
            ObjectNode e = entries.addObject();
            e.put("fullUrl", tokenService.issuer() + "/List/" + m.get().manifestId());
            e.set("resource", m.get().manifest());
        }
        bundle.put("total", entries.size());
        return ResponseEntity.ok().contentType(FHIR_JSON).body(bundle);
    }

    @GetMapping("/arex/Binary/{binaryId}")
    public ResponseEntity<?> binary(@PathVariable String binaryId, HttpServletRequest request) {
        AccessTokenStore.TokenInfo token = bearer(request).orElse(null);
        if (token == null) {
            return unauthorized();
        }
        Optional<ManifestRecord> m = entitledTo(token, token.manifestId());
        if (m.isEmpty()) {
            return notFound();
        }
        PerSendCopier.Entry entry = m.get().entries().stream().filter(e -> e.binaryId().equals(binaryId)).findFirst().orElse(null);
        if (entry == null) {
            return notFound();
        }
        byte[] data = store.payload(entry.originalEntryId()).orElse(null);
        if (data == null || !SeedService.sha256(data).equals(entry.sha256())) {
            // The object we hold does not match the digest we published: never serve it.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(FHIR_JSON)
                    .body(outcome("exception", "The stored content object failed its integrity check."));
        }
        String accept = request.getHeader("Accept");
        if (accept != null && (accept.contains("fhir+json") || accept.contains("application/json"))) {
            ObjectNode binary = mapper.createObjectNode();
            binary.put("resourceType", "Binary");
            binary.put("id", binaryId);
            binary.put("contentType", entry.contentType());
            binary.put("data", Base64.getEncoder().encodeToString(data));
            return ResponseEntity.ok().contentType(FHIR_JSON).body(binary);
        }
        return octets(data, entry.contentType(), request.getHeader("Range"));
    }

    // ---- helpers ----

    private Optional<AccessTokenStore.TokenInfo> bearer(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h == null || h.length() < 8 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        return tokens.check(h.substring(7).trim());
    }

    /** The token only ever opens its own manifest; asking for another id looks exactly like a missing one. */
    private Optional<ManifestRecord> entitledTo(AccessTokenStore.TokenInfo token, String manifestId) {
        if (!token.manifestId().equals(manifestId)) {
            return Optional.empty();
        }
        return access.entitled(token.clientId(), manifestId);
    }

    private ResponseEntity<byte[]> octets(byte[] data, String contentType, String rangeHeader) {
        MediaType type = MediaType.parseMediaType(contentType);
        if (rangeHeader == null) {
            return ResponseEntity.ok().contentType(type).header(HttpHeaders.ACCEPT_RANGES, "bytes").body(data);
        }
        Matcher m = RANGE.matcher(rangeHeader.trim());
        long start;
        long end;
        if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
            return rangeNotSatisfiable(data.length);
        }
        if (m.group(1).isEmpty()) { // suffix range: the last N bytes
            long n = Long.parseLong(m.group(2));
            start = Math.max(0, data.length - n);
            end = data.length - 1L;
        } else {
            start = Long.parseLong(m.group(1));
            end = m.group(2).isEmpty() ? data.length - 1L : Math.min(Long.parseLong(m.group(2)), data.length - 1L);
        }
        if (start >= data.length || start > end) {
            return rangeNotSatisfiable(data.length);
        }
        byte[] part = java.util.Arrays.copyOfRange(data, (int) start, (int) end + 1);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).contentType(type)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + data.length).body(part);
    }

    private ResponseEntity<byte[]> rangeNotSatisfiable(int length) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + length).build();
    }

    private static boolean matchesBusinessIdentifier(JsonNode manifest, String wanted) {
        String system = null;
        String value = wanted;
        int bar = wanted.indexOf('|');
        if (bar >= 0) {
            system = wanted.substring(0, bar);
            value = wanted.substring(bar + 1);
        }
        for (JsonNode ext : manifest.path("extension")) {
            if (!ext.path("url").asText().endsWith("arex-transaction-context")) {
                continue;
            }
            for (JsonNode part : ext.path("extension")) {
                if ("businessIdentifier".equals(part.path("url").asText())) {
                    JsonNode id = part.path("valueIdentifier");
                    boolean valueOk = value.equals(id.path("value").asText());
                    boolean systemOk = system == null || system.isEmpty() || system.equals(id.path("system").asText());
                    if (valueOk && systemOk) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private ResponseEntity<ObjectNode> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(FHIR_JSON).body(outcome("not-found", "Not found."));
    }

    private ResponseEntity<ObjectNode> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(FHIR_JSON)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .body(outcome("login", "A valid access token is required."));
    }

    private ObjectNode outcome(String code, String text) {
        ObjectNode o = mapper.createObjectNode();
        o.put("resourceType", "OperationOutcome");
        ObjectNode issue = o.putArray("issue").addObject();
        issue.put("severity", "error");
        issue.put("code", code);
        issue.putObject("details").put("text", text);
        return o;
    }
}
