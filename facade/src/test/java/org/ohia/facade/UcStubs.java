package org.ohia.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-JVM stand-ins shared by the integration tests: a data host that serves the real UC07 I1
 * files (copied into src/test/resources/uc07-i1 from ohia-test-data commit 445d4ce) and a HAPI
 * that records what it is sent and serves it back with server-added meta.
 */
public final class UcStubs {

    public static final String REF = "testref";
    public static final String PKG = "2026-CMS-Connectathon/fhir-resources/purpose-built/uc07-pediatric-rootcanal/interactions/interaction-01";
    public static final ObjectMapper M = new ObjectMapper();

    public static final AtomicInteger posts = new AtomicInteger();
    public static final AtomicReference<String> lastPost = new AtomicReference<>();
    public static final Map<String, JsonNode> hapiStore = new ConcurrentHashMap<>();
    public static volatile boolean tamper;

    private static final HttpServer dataHost;
    private static final HttpServer hapi;

    static {
        try {
            dataHost = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            dataHost.createContext("/", UcStubs::serveData);
            dataHost.start();
            hapi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            hapi.createContext("/fhir", UcStubs::serveHapi);
            hapi.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private UcStubs() {
    }

    public static String dataBaseUrl() {
        return "http://127.0.0.1:" + dataHost.getAddress().getPort();
    }

    public static String hapiBaseUrl() {
        return "http://127.0.0.1:" + hapi.getAddress().getPort() + "/fhir";
    }

    public static void reset() {
        hapiStore.clear();
        posts.set(0);
        lastPost.set(null);
        tamper = false;
    }

    public static byte[] fixtureBytes(String name) {
        try (var in = UcStubs.class.getResourceAsStream("/uc07-i1/" + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String fixtureText(String name) {
        return new String(fixtureBytes(name), StandardCharsets.UTF_8);
    }

    private static void serveData(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String prefix = "/" + REF + "/" + PKG + "/";
        byte[] body = null;
        if (path.startsWith(prefix)) {
            String rest = path.substring(prefix.length());
            if (rest.equals("interaction-01-bundle.json")) {
                body = fixtureBytes("interaction-01-bundle.json");
            } else if (rest.startsWith("arex-payloads/radiograph-")) {
                body = fixtureBytes(rest.substring("arex-payloads/".length()));
                if (body != null && tamper && rest.contains("periapical")) {
                    body = body.clone();
                    body[body.length - 1] ^= 0x01;
                }
            }
        }
        respond(ex, body == null ? 404 : 200, body == null ? new byte[0] : body);
    }

    private static void serveHapi(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (ex.getRequestMethod().equals("POST") && path.equals("/fhir")) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            posts.incrementAndGet();
            lastPost.set(body);
            for (JsonNode e : M.readTree(body).get("entry")) {
                JsonNode r = e.get("resource");
                hapiStore.put(r.get("resourceType").asText() + "/" + r.get("id").asText(), r);
            }
            respond(ex, 200, "{\"resourceType\":\"Bundle\",\"type\":\"transaction-response\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (ex.getRequestMethod().equals("GET") && path.startsWith("/fhir/")) {
            JsonNode stored = hapiStore.get(path.substring("/fhir/".length()));
            if (stored == null) {
                respond(ex, 404, "{\"resourceType\":\"OperationOutcome\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            ObjectNode copy = stored.deepCopy();
            ObjectNode meta = copy.has("meta") ? (ObjectNode) copy.get("meta") : copy.putObject("meta");
            meta.put("versionId", "1");
            meta.put("lastUpdated", "2026-11-05T00:00:00.000+00:00");
            respond(ex, 200, M.writeValueAsBytes(copy));
            return;
        }
        respond(ex, 404, new byte[0]);
    }

    static void respond(HttpExchange ex, int code, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            ex.getResponseBody().write(body);
        }
        ex.close();
    }
}
