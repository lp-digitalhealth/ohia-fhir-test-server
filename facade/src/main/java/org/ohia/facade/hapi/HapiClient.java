package org.ohia.facade.hapi;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ohia.facade.common.UpstreamException;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Component;

/**
 * Talks to the HAPI server on the compose network. This is the facade's own backend, not a
 * tester endpoint, so it is deliberately separate from the outbound sender (which has SSRF
 * guards and must never be pointed at HAPI).
 */
@Component
public class HapiClient {

    private static final Pattern TYPE = Pattern.compile("[A-Za-z]{1,40}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;

    public HapiClient(FacadeProperties props) {
        String b = props.hapiBaseUrl();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** POSTs a transaction Bundle to the FHIR base. Returns the response body. */
    public String postTransaction(String bundleJson) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/fhir+json").header("Accept", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(bundleJson, StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() / 100 != 2) {
            throw new UpstreamException(resp.statusCode(),
                    "HAPI rejected the transaction: " + truncate(resp.body()));
        }
        return resp.body();
    }

    /** Reads one resource. Empty if HAPI does not have it. */
    public Optional<String> read(String type, String id) {
        if (!TYPE.matcher(type).matches() || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Unsafe resource reference: " + type + "/" + id);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/" + type + "/" + id))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/fhir+json").GET().build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 200) {
            return Optional.of(resp.body());
        }
        if (resp.statusCode() == 404 || resp.statusCode() == 410) {
            return Optional.empty();
        }
        throw new UpstreamException(resp.statusCode(), "HAPI error reading " + type + "/" + id);
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UpstreamException("HAPI is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Interrupted while calling HAPI", e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 400 ? s.substring(0, 400) + "..." : s);
    }
}
