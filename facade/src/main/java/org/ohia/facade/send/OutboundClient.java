package org.ohia.facade.send;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * POSTs a transaction Bundle to a tester's FHIR base URL. The URL must already have passed
 * SsrfGuard. Redirects are never followed (a target must not be able to bounce the request to
 * an internal address), and the response is read with a size cap.
 */
@Component
public class OutboundClient {

    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private static final int EXCERPT_CHARS = 2000;

    public record Delivery(int status, String excerpt, long millis, String error) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public Delivery post(URI target, String bundleJson, String bearerToken) {
        long start = System.nanoTime();
        HttpRequest.Builder b = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/fhir+json").header("Accept", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(bundleJson, StandardCharsets.UTF_8));
        if (bearerToken != null && !bearerToken.isBlank()) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        try {
            HttpResponse<InputStream> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                byte[] body = in.readNBytes(MAX_RESPONSE_BYTES);
                String text = new String(body, StandardCharsets.UTF_8);
                String excerpt = text.length() > EXCERPT_CHARS ? text.substring(0, EXCERPT_CHARS) + "..." : text;
                return new Delivery(resp.statusCode(), excerpt, millisSince(start), null);
            }
        } catch (IOException e) {
            return new Delivery(-1, "", millisSince(start), e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Delivery(-1, "", millisSince(start), "Interrupted");
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
