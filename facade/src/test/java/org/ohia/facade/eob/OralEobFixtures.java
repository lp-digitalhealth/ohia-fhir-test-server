package org.ohia.facade.eob;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A stand-in for the data host that serves the five real oral EOB files (copied into src/test/resources/oral-eob from
 * ohia-test-data commit 6b84bb2). A test can replace or hide a file to prove each refusal rule.
 */
final class OralEobFixtures {

    static final String REF = "eobref";
    static final String TOKEN = "unit-test-token-123";
    private static final String B = "2026-CMS-Connectathon/fhir-resources/purpose-built/";
    static final List<String> FILES = List.of(
            B + "uc02a-surgical-extraction/interactions/interaction-05/eob-d7210-claims-sharing.json",
            B + "uc02b-commercial-implant/interactions/interaction-05/eob-extraction-implant-claims-sharing.json",
            B + "uc03-pediatric-referral/interactions/interaction-05/eob-timothy-dental-claims-sharing.json",
            B + "uc04a-teledentistry-commercial/interactions/interaction-05/eob-inoffice-claims-sharing.json",
            B + "uc04a-teledentistry-commercial/interactions/interaction-05/eob-teledentistry-claims-sharing.json");

    /** Replaces the served bytes of a file (keyed by its path inside the data repository). */
    static final Map<String, byte[]> overrides = new ConcurrentHashMap<>();
    /** Paths answered 404, as if the data host had no such file. */
    static final Set<String> missing = ConcurrentHashMap.newKeySet();
    private static final HttpServer HOST;

    static {
        try {
            HOST = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            HOST.createContext("/", OralEobFixtures::serve);
            HOST.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private OralEobFixtures() {
    }

    static String baseUrl() {
        return "http://127.0.0.1:" + HOST.getAddress().getPort();
    }

    static void reset() {
        overrides.clear();
        missing.clear();
    }

    static byte[] fixture(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        try (var in = OralEobFixtures.class.getResourceAsStream("/oral-eob/" + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void properties(DynamicPropertyRegistry r, Path dataDir) {
        r.add("facade.api-token", () -> TOKEN);
        r.add("facade.public-base-url", () -> "https://ohia.test");
        r.add("facade.data-dir", dataDir::toString);
        r.add("facade.eob.data-base-url", OralEobFixtures::baseUrl);
        r.add("facade.eob.data-ref", () -> REF);
        for (int i = 0; i < FILES.size(); i++) {
            String path = FILES.get(i);
            r.add("facade.eob.files[" + i + "]", () -> path);
        }
    }

    private static void serve(HttpExchange ex) throws IOException {
        String prefix = "/" + REF + "/";
        String uri = ex.getRequestURI().getPath();
        byte[] body = null;
        if (uri.startsWith(prefix)) {
            String path = uri.substring(prefix.length());
            if (!missing.contains(path)) {
                body = overrides.containsKey(path) ? overrides.get(path) : fixture(path);
            }
        }
        byte[] out = body == null ? new byte[0] : body;
        ex.sendResponseHeaders(body == null ? 404 : 200, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }
}
