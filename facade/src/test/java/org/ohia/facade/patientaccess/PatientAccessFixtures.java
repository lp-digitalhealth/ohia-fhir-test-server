package org.ohia.facade.patientaccess;

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
 * A stand-in for the data host that serves the nine real payer-side files (copied into src/test/resources/patient-access
 * from ohia-test-data commit 1bb07a8, UC07 interaction 6). A test can replace or hide a file to prove each refusal rule.
 */
final class PatientAccessFixtures {

    static final String REF = "paref";
    static final String TOKEN = "unit-test-token-123";
    private static final String B = "2026-CMS-Connectathon/fhir-resources/purpose-built/uc07-pediatric-rootcanal/interactions/interaction-06/";
    static final List<String> FILES = List.of(
            B + "patient-joe-castle-payer.json", B + "coverage-joe-castle-dentaquest-payer.json", B + "org-dentaquest-tx-payer.json",
            B + "org-pediatric-dental-tx-payer.json", B + "org-restorative-dental-tx-payer.json", B + "pract-mary-parker-payer.json",
            B + "pract-max-garcia-payer.json", B + "eob-pediatric-evaluation-c4bb-oral.json", B + "eob-restorative-treatment-c4bb-oral.json");

    static final String BASE = "/patient-access/fhir";
    static final String ONLINE_ID = "https://dentaquest.example.org/fhir/online-id|DQ-ONLINE-TX-0038211";

    static final Map<String, byte[]> overrides = new ConcurrentHashMap<>();
    static final Set<String> missing = ConcurrentHashMap.newKeySet();
    private static final HttpServer HOST;

    static {
        try {
            HOST = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            HOST.createContext("/", PatientAccessFixtures::serve);
            HOST.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private PatientAccessFixtures() {
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
        try (var in = PatientAccessFixtures.class.getResourceAsStream("/patient-access/" + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void properties(DynamicPropertyRegistry r, Path dataDir) {
        r.add("facade.api-token", () -> TOKEN);
        r.add("facade.public-base-url", () -> "https://ohia.test");
        r.add("facade.data-dir", dataDir::toString);
        r.add("facade.patient-access.data-base-url", PatientAccessFixtures::baseUrl);
        r.add("facade.patient-access.data-ref", () -> REF);
        for (int i = 0; i < FILES.size(); i++) {
            String path = FILES.get(i);
            r.add("facade.patient-access.files[" + i + "]", () -> path);
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
