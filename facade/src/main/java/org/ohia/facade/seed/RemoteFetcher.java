package org.ohia.facade.seed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.ohia.facade.common.UpstreamException;
import org.springframework.stereotype.Component;

/**
 * Fetches files from the data repository host. The URL is built from configuration only
 * (never from a request), so this is not an open fetcher.
 */
@Component
public class RemoteFetcher {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public byte[] fetch(String url, int maxBytes) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) {
                    throw new UpstreamException(resp.statusCode(), "Data host returned " + resp.statusCode() + " for " + url);
                }
                byte[] data = in.readNBytes(maxBytes + 1);
                if (data.length > maxBytes) {
                    throw new UpstreamException(502, "File is larger than " + maxBytes + " bytes: " + url);
                }
                return data;
            }
        } catch (IOException e) {
            throw new UpstreamException("Cannot fetch " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Interrupted while fetching " + url, e);
        }
    }
}
