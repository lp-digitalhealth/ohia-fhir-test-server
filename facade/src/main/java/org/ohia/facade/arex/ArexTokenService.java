package org.ohia.facade.arex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ohia.facade.clients.ClientEntry;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/**
 * The SMART Backend Services token endpoint for ARex retrieval: client_credentials with a
 * private_key_jwt client assertion, and a manifest-scoped grant requested through RFC 9396
 * {@code authorization_details}.
 *
 * <p>The authorization_details type {@code arex-manifest} is this server's own choice: the ARex
 * page that was read says manifest-scoped grants should use authorization_details carrying the
 * manifest identifier, but does not name a type value. Align it with the spec when that is known.
 */
@Service
public class ArexTokenService {

    public static final String DETAILS_TYPE = "arex-manifest";
    static final Set<String> SUPPORTED_SCOPES = Set.of("system/List.rs", "system/Binary.r");
    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private final ClientAssertionVerifier verifier;
    private final ArexAccess access;
    private final AccessTokenStore tokens;
    private final ObjectMapper mapper;
    private final String issuer;

    public ArexTokenService(ClientAssertionVerifier verifier, ArexAccess access, AccessTokenStore tokens,
            ObjectMapper mapper, FacadeProperties props) {
        this.verifier = verifier;
        this.access = access;
        this.tokens = tokens;
        this.mapper = mapper;
        String base = props.publicBaseUrl();
        this.issuer = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/arex";
    }

    public String issuer() {
        return issuer;
    }

    public String tokenEndpoint() {
        return issuer + "/auth/token";
    }

    public Map<String, Object> token(MultiValueMap<String, String> form) {
        if (!"client_credentials".equals(form.getFirst("grant_type"))) {
            throw new OAuthException(400, "unsupported_grant_type", "Only client_credentials is supported.");
        }
        String assertion = form.getFirst("client_assertion");
        if (!ASSERTION_TYPE.equals(form.getFirst("client_assertion_type")) || assertion == null || assertion.isBlank()) {
            throw new OAuthException(401, "invalid_client", "Client authentication failed.");
        }
        ClientEntry client = verifier.verify(assertion, tokenEndpoint());

        List<String> scopes = scopes(form.getFirst("scope"));
        JsonNode detail = singleManifestDetail(form.getFirst("authorization_details"));
        String manifestId = detail.get("manifest").asText();

        // Same answer whether the manifest does not exist, is someone else's, has expired or was retracted.
        access.entitled(client.clientId(), manifestId).orElseThrow(
                () -> new OAuthException(400, "invalid_grant", "The requested manifest is not available to this client."));

        String token = tokens.issue(client.clientId(), manifestId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", token);
        response.put("token_type", "bearer");
        response.put("expires_in", AccessTokenStore.LIFETIME_SECONDS);
        response.put("scope", String.join(" ", scopes));
        response.put("authorization_details", List.of(detail));
        return response;
    }

    private List<String> scopes(String requested) {
        if (requested == null || requested.isBlank()) {
            return new ArrayList<>(List.of("system/Binary.r", "system/List.rs"));
        }
        List<String> out = new ArrayList<>();
        for (String s : requested.trim().split("\\s+")) {
            if (!SUPPORTED_SCOPES.contains(s)) {
                throw new OAuthException(400, "invalid_scope", "Unsupported scope: " + s);
            }
            out.add(s);
        }
        return out;
    }

    private JsonNode singleManifestDetail(String raw) {
        String message = "authorization_details must be a JSON array with one {\"type\":\"" + DETAILS_TYPE
                + "\",\"manifest\":\"<manifest id>\"} object.";
        if (raw == null || raw.isBlank()) {
            throw new OAuthException(400, "invalid_request", message);
        }
        try {
            JsonNode n = mapper.readTree(raw);
            if (n.isArray() && n.size() == 1 && DETAILS_TYPE.equals(n.get(0).path("type").asText())
                    && n.get(0).path("manifest").isTextual() && !n.get(0).path("manifest").asText().isBlank()) {
                return n.get(0);
            }
        } catch (IOException e) {
            // falls through to the same error
        }
        throw new OAuthException(400, "invalid_request", message);
    }
}
