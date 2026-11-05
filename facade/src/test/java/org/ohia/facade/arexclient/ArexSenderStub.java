package org.ohia.facade.arexclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.ohia.facade.UcStubs;

/**
 * A stand-in for a SENDER's ARex server (what a tester would run). It implements discovery, a token
 * endpoint that really verifies our signed client assertion against our PUBLISHED key, the
 * published manifest, and Binary retrieval. Each failure a sender could show can be switched on.
 */
public final class ArexSenderStub {

    public final List<String> requests = new CopyOnWriteArrayList<>();
    public final AtomicInteger tokensIssued = new AtomicInteger();
    public final AtomicInteger assertionsRejected = new AtomicInteger();

    // switches
    public volatile String discoveryMode = "arex";            // arex | root | none
    public volatile List<String> advertisedTypes = null;      // authorization_details_types_supported, if any
    public volatile String expectedType = "arex-manifest";
    public volatile boolean refuseToken;
    public volatile String manifestMode = "current";          // current | retracted | missing | differs
    public volatile boolean tamper;
    public volatile boolean truncate;
    public volatile boolean extraBytes;
    public volatile String contentTypeOverride;
    public volatile String redirectBinaryTo;
    public final AtomicBoolean unauthorizedOnce = new AtomicBoolean();
    public final AtomicInteger dropTokenRequests = new AtomicInteger(); // how many token requests to cut off with no response
    public volatile String tokenEndpointOverride;             // lets a test advertise a token endpoint on another origin

    private final HttpServer server;
    private final JWKSet clientKeys;
    private final String clientId;
    private final ObjectNode manifest;
    private final Map<String, byte[]> photos;
    private final Set<String> tokens = new HashSet<>();
    private final Set<String> seenJti = new HashSet<>();

    public ArexSenderStub(JWKSet clientKeys, String clientId, ObjectNode manifest, Map<String, byte[]> photos) {
        this.clientKeys = clientKeys;
        this.clientId = clientId;
        this.manifest = manifest;
        this.photos = photos;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String tokenEndpoint() {
        return base() + "/arex/auth/token";
    }

    public void stop() {
        server.stop(0);
    }

    public long count(String prefix) {
        return requests.stream().filter(r -> r.startsWith(prefix)).count();
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        requests.add(method + " " + path);
        try {
            if (method.equals("GET") && path.equals("/arex/.well-known/smart-configuration") && discoveryMode.equals("arex")) {
                discovery(ex);
            } else if (method.equals("GET") && path.equals("/.well-known/smart-configuration") && discoveryMode.equals("root")) {
                discovery(ex);
            } else if (method.equals("POST") && path.equals("/arex/auth/token")) {
                token(ex);
            } else if (method.equals("GET") && path.startsWith("/arex/List/")) {
                manifest(ex, path.substring("/arex/List/".length()));
            } else if (method.equals("GET") && path.startsWith("/arex/Binary/")) {
                binary(ex, path.substring("/arex/Binary/".length()));
            } else {
                send(ex, 404, "text/plain", new byte[0]);
            }
        } catch (RuntimeException e) {
            send(ex, 500, "text/plain", e.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void discovery(HttpExchange ex) throws IOException {
        ObjectNode doc = UcStubs.M.createObjectNode();
        doc.put("token_endpoint", tokenEndpointOverride != null ? tokenEndpointOverride : tokenEndpoint());
        if (advertisedTypes != null) {
            var arr = doc.putArray("authorization_details_types_supported");
            advertisedTypes.forEach(arr::add);
        }
        send(ex, 200, "application/json", UcStubs.M.writeValueAsBytes(doc));
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> form = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (dropTokenRequests.get() > 0 && dropTokenRequests.decrementAndGet() >= 0) {
            ex.close(); // no response at all, like a connection the server had already closed
            return;
        }
        if (refuseToken || !"client_credentials".equals(form.get("grant_type"))) {
            assertionsRejected.incrementAndGet();
            send(ex, 401, "application/json", "{\"error\":\"invalid_client\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String problem = checkAssertion(form.get("client_assertion"));
        if (problem != null) {
            assertionsRejected.incrementAndGet();
            send(ex, 401, "application/json", ("{\"error\":\"invalid_client\",\"error_description\":\"" + problem + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        JsonNode details = UcStubs.M.readTree(form.getOrDefault("authorization_details", "[]"));
        if (!details.isArray() || details.size() != 1 || !expectedType.equals(details.get(0).path("type").asText())) {
            send(ex, 400, "application/json", "{\"error\":\"invalid_request\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!manifest.get("id").asText().equals(details.get(0).path("manifest").asText())) {
            send(ex, 400, "application/json", "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String token = UUID.randomUUID().toString();
        synchronized (tokens) {
            tokens.add(token);
        }
        tokensIssued.incrementAndGet();
        send(ex, 200, "application/json", ("{\"access_token\":\"" + token + "\",\"token_type\":\"bearer\",\"expires_in\":300}").getBytes(StandardCharsets.UTF_8));
    }

    /** Verifies the assertion the way the spec asks; returns a problem description, or null if it is fine. */
    private String checkAssertion(String assertion) {
        try {
            SignedJWT jwt = SignedJWT.parse(assertion);
            if (!JWSAlgorithm.RS384.equals(jwt.getHeader().getAlgorithm())) {
                return "alg";
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            if (!clientId.equals(c.getIssuer()) || !clientId.equals(c.getSubject())) {
                return "iss/sub";
            }
            if (c.getAudience() == null || !c.getAudience().contains(tokenEndpoint())) {
                return "aud";
            }
            Instant exp = c.getExpirationTime().toInstant();
            if (!exp.isAfter(Instant.now()) || exp.isAfter(Instant.now().plusSeconds(330))) {
                return "exp";
            }
            JWK key = clientKeys.getKeyByKeyId(jwt.getHeader().getKeyID());
            if (!(key instanceof RSAKey rsa) || !jwt.verify(new RSASSAVerifier(rsa.toRSAPublicKey()))) {
                return "signature";
            }
            synchronized (seenJti) {
                if (c.getJWTID() == null || !seenJti.add(c.getJWTID())) {
                    return "jti";
                }
            }
            return null;
        } catch (Exception e) {
            return "unreadable";
        }
    }

    private boolean authorized(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) {
            return false;
        }
        synchronized (tokens) {
            return tokens.contains(h.substring(7));
        }
    }

    private void manifest(HttpExchange ex, String id) throws IOException {
        if (!authorized(ex)) {
            send(ex, 401, "application/fhir+json", new byte[0]);
            return;
        }
        if (manifestMode.equals("missing") || !manifest.get("id").asText().equals(id)) {
            send(ex, 404, "application/fhir+json", new byte[0]);
            return;
        }
        ObjectNode copy = manifest.deepCopy();
        if (manifestMode.equals("retracted")) {
            copy.put("status", "retracted");
        } else if (manifestMode.equals("differs")) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) copy.get("entry")).remove(1);
        }
        send(ex, 200, "application/fhir+json", UcStubs.M.writeValueAsBytes(copy));
    }

    private void binary(HttpExchange ex, String id) throws IOException {
        if (unauthorizedOnce.compareAndSet(true, false)) {
            send(ex, 401, "text/plain", new byte[0]);
            return;
        }
        if (!authorized(ex)) {
            send(ex, 401, "text/plain", new byte[0]);
            return;
        }
        if (redirectBinaryTo != null) {
            ex.getResponseHeaders().add("Location", redirectBinaryTo);
            send(ex, 302, "text/plain", new byte[0]);
            return;
        }
        byte[] data = photos.get(id);
        if (data == null) {
            send(ex, 404, "text/plain", new byte[0]);
            return;
        }
        data = data.clone();
        if (tamper) {
            data[data.length - 1] ^= 0x01;
        }
        if (truncate) {
            data = java.util.Arrays.copyOf(data, data.length / 2);
        }
        if (extraBytes) {
            byte[] more = new byte[data.length + 5000];
            System.arraycopy(data, 0, more, 0, data.length);
            data = more;
        }
        send(ex, 200, contentTypeOverride != null ? contentTypeOverride : "image/jpeg", data);
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            ex.getResponseBody().write(body);
        }
        ex.close();
    }
}
