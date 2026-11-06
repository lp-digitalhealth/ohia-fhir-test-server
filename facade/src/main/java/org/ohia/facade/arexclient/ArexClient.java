package org.ohia.facade.arexclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.ohia.facade.common.ForbiddenTargetException;
import org.ohia.facade.config.FacadeProperties;
import org.ohia.facade.keys.ClientAssertionSigner;
import org.ohia.facade.seed.SeedService;
import org.ohia.facade.send.SsrfGuard;
import org.springframework.stereotype.Component;

/**
 * The ARex content consumer: pulls a manifest's content objects from the SENDER's ARex server.
 *
 * <p>Steps, each recorded: find the sender's ARex base from the content URLs; discover the token
 * endpoint; sign a client assertion with our own key and ask for a token scoped to this one
 * manifest; re-read the published manifest and stop if it is not {@code current} (arex-39); then
 * download each object with a hard size cap, verify its SHA-256 and size, and hand over only the
 * objects that verified. Everything is report-first: a requirement that cannot be met becomes a
 * named finding and the pull goes on, except where continuing would be unsafe or pointless
 * (no token endpoint, token refused, manifest not current).
 *
 * <p>Every URL comes from a payload a stranger sent, so every URL passes {@link SsrfGuard} and must
 * be on the same origin as the content URLs. Redirects are never followed.
 */
@Component
public class ArexClient {

    /** One content object to fetch, as declared by the manifest's content entry. */
    public record ContentRef(String entryId, String url, String contentType, long size, String sha256) {
    }

    public record Step(String name, boolean ok, String detail) {
    }

    public record Finding(String code, String specRef, String message) {
    }

    public record ObjectResult(String entryId, String url, String status, long bytes, String sha256,
            String receivedContentType) {
    }

    public record Outcome(String stoppedBecause, String arexBase, String tokenEndpoint, String authorizationDetailsType,
            List<Step> steps, List<ObjectResult> objects, List<Finding> findings) {
    }

    /** Receives each object that passed its digest and size check. */
    public interface Sink {
        void accept(String entryId, byte[] bytes, String receivedContentType);
    }

    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    /** What we ask a sender's token to allow: the manifest, its content entries, their provenance, and the bytes. */
    public static final String DEFAULT_SCOPE = "system/List.r system/DocumentReference.r system/Provenance.r system/Binary.r";

    /** Overridable with facade.arex.scope (or FACADE_AREX_SCOPE) when a sender wants something else. */
    @org.springframework.beans.factory.annotation.Value("${facade.arex.scope:" + DEFAULT_SCOPE + "}")
    private String scope = DEFAULT_SCOPE;

    private final SsrfGuard guard;
    private final ClientAssertionSigner signer;
    private final ObjectMapper mapper;
    private final FacadeProperties.Client cfg;
    private final HttpClient http;

    public ArexClient(FacadeProperties props, SsrfGuard guard, ClientAssertionSigner signer, ObjectMapper mapper) {
        this.guard = guard;
        this.signer = signer;
        this.mapper = mapper;
        this.cfg = props.client();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(cfg.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Outcome pull(String manifestId, JsonNode embeddedManifest, List<ContentRef> objects, Sink sink) {
        Run run = new Run();
        try {
            doPull(run, manifestId, embeddedManifest, objects, sink);
        } catch (Stop stop) {
            run.stoppedBecause = stop.getMessage();
        }
        return new Outcome(run.stoppedBecause, run.arexBase, run.tokenEndpoint, run.adType, run.steps, run.objects, run.findings);
    }

    // ------------------------------------------------------------------ the steps

    private void doPull(Run run, String manifestId, JsonNode embedded, List<ContentRef> objects, Sink sink) {
        if (objects.isEmpty()) {
            throw run.stop("NO_OBJECTS", null, "The manifest has no content entries to pull.");
        }
        run.arexBase = arexBase(run, objects.get(0).url());
        URI base = checkUrl(run, run.arexBase, "the sender's ARex base");
        run.step("sender-base", true, run.arexBase);

        String tokenEndpoint = discover(run, base);
        run.tokenEndpoint = tokenEndpoint;
        URI tokenUri;
        try {
            tokenUri = URI.create(tokenEndpoint);
        } catch (IllegalArgumentException e) {
            throw run.stop("UNSAFE_URL_REFUSED", "local safety rule", "The advertised token endpoint is not a valid URL: " + tokenEndpoint);
        }
        checkSameOrigin(run, base, tokenUri, "the token endpoint");
        String token = requestToken(run, tokenEndpoint, manifestId);

        readPublishedManifest(run, base, manifestId, embedded, token);
        pullObjects(run, base, manifestId, tokenEndpoint, token, objects, sink);
    }

    private String arexBase(Run run, String firstUrl) {
        int i = firstUrl.indexOf("/Binary/");
        if (i < 0) {
            throw run.stop("UNSAFE_URL_REFUSED", "ARex content URLs end in /Binary/<id>",
                    "The content URL does not look like an ARex Binary URL: " + firstUrl);
        }
        return firstUrl.substring(0, i);
    }

    private String discover(Run run, URI base) {
        String origin = base.getScheme() + "://" + base.getAuthority();
        List<String> candidates = List.of(run.arexBase + "/.well-known/smart-configuration", origin + "/.well-known/smart-configuration");
        for (String url : candidates) {
            Optional<JsonNode> doc = getJson(run, url, null);
            if (doc.isPresent() && doc.get().path("token_endpoint").isTextual()) {
                run.step("discovery", true, url);
                run.advertisedTypes = doc.get().path("authorization_details_types_supported");
                return doc.get().get("token_endpoint").asText();
            }
        }
        throw run.stop("TOKEN_ENDPOINT_NOT_DISCOVERED", "SMART discovery (assumed; ARex is silent)",
                "No SMART discovery document with a token_endpoint was found at " + candidates + ". Without one we cannot authenticate.");
    }

    private String requestToken(Run run, String tokenEndpoint, String manifestId) {
        String type = cfg.authorizationDetailsType();
        JsonNode advertised = run.advertisedTypes;
        if (advertised != null && advertised.isArray() && !advertised.isEmpty()) {
            boolean ours = false;
            for (JsonNode t : advertised) {
                ours |= type.equals(t.asText());
            }
            if (!ours) {
                String theirs = advertised.get(0).asText();
                run.finding("AUTHORIZATION_DETAILS_TYPE_ADJUSTED", "arex-24 (type value not named by the draft)",
                        "The sender advertises authorization_details type '" + theirs + "', not '" + type + "'; used theirs.");
                type = theirs;
            }
        }
        run.adType = type;
        URI uri = checkUrl(run, tokenEndpoint, "the token endpoint");
        // A transport error (for example a pooled connection the sender had already closed, which Java never
        // retries for a POST) is retried ONCE. Every attempt signs a FRESH assertion: re-sending the same one
        // would be refused as a replay if the first had actually arrived.
        Resp r = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String form = "grant_type=client_credentials"
                    + "&client_assertion_type=" + enc(ASSERTION_TYPE)
                    + "&client_assertion=" + enc(signer.sign(tokenEndpoint))
                    + "&scope=" + enc(scope)
                    + "&authorization_details=" + enc("[{\"type\":\"" + type + "\",\"manifest\":\"" + manifestId + "\"}]");
            r = send(run, HttpRequest.newBuilder(uri).timeout(readTimeout()).header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(form)).build(), 1_000_000);
            if (r.error == null) {
                if (attempt == 2) {
                    run.finding("TOKEN_REQUEST_RETRIED", "robustness", "The first token request was cut off by the network and was retried once with a fresh assertion.");
                }
                break;
            }
        }
        if (r.error != null) {
            throw run.stop("TOKEN_REFUSED", "arex-19", "The token request failed: " + r.error);
        }
        if (r.status != 200) {
            run.step("token", false, "HTTP " + r.status + " " + excerpt(r.body));
            throw run.stop("TOKEN_REFUSED", "arex-19", "The sender refused our token request (HTTP " + r.status + "): " + excerpt(r.body));
        }
        try {
            String token = mapper.readTree(r.body).path("access_token").asText("");
            if (token.isBlank()) {
                throw run.stop("TOKEN_REFUSED", "arex-19", "The token response had no access_token.");
            }
            run.step("token", true, "issued for manifest " + manifestId + " (type " + type + ")");
            return token;
        } catch (IOException e) {
            throw run.stop("TOKEN_REFUSED", "arex-19", "The token response was not JSON.");
        }
    }

    private void readPublishedManifest(Run run, URI base, String manifestId, JsonNode embedded, String token) {
        String url = run.arexBase + "/List/" + manifestId;
        checkUrl(run, url, "the published manifest URL");
        Optional<JsonNode> published = getJson(run, url, token);
        if (published.isEmpty()) {
            run.step("published-manifest", false, "not available at " + url);
            run.finding("PUBLISHED_MANIFEST_UNAVAILABLE", "arex-36, arex-39",
                    "The published manifest could not be read at " + url + "; continued with the embedded copy.");
            return;
        }
        ManifestFacts facts = ManifestFacts.of(published.get());
        if (!"current".equals(facts.status())) {
            run.step("published-manifest", false, "status " + facts.status());
            run.finding("MANIFEST_NOT_CURRENT", "arex-39", "The published manifest's status is '" + facts.status() + "', not 'current'.");
            throw run.stop("MANIFEST_NOT_CURRENT", "arex-39", "The published manifest is '" + facts.status() + "'; nothing was pulled.");
        }
        run.step("published-manifest", true, "status current");
        if (embedded != null && !facts.sameAs(ManifestFacts.of(embedded))) {
            run.finding("PUBLISHED_MANIFEST_DIFFERS", "arex-39, arex-40",
                    "The published manifest differs from the embedded copy (entries, expiry or requesting organization); the published one is authoritative.");
        }
    }

    private void pullObjects(Run run, URI base, String manifestId, String tokenEndpoint, String token0,
            List<ContentRef> objects, Sink sink) {
        String token = token0;
        long total = 0;
        int count = 0;
        for (ContentRef ref : objects) {
            if (++count > cfg.maxObjects()) {
                run.finding("OBJECT_LIMIT", "local limit", "More than " + cfg.maxObjects() + " objects; the rest were skipped.");
                break;
            }
            ObjectResult early = refuseUnsafe(run, base, ref);
            if (early != null) {
                run.objects.add(early);
                continue;
            }
            if (ref.size() > cfg.maxObjectBytes() || total + ref.size() > cfg.maxTotalBytes()) {
                run.finding("OBJECT_TOO_LARGE", "local limit", ref.entryId() + " declares " + ref.size() + " bytes, over our limit.");
                run.objects.add(new ObjectResult(ref.entryId(), ref.url(), "too-large", 0, null, null));
                continue;
            }
            Resp r = fetchObject(run, ref, token);
            if (r.status == 401) { // the short-lived token may have run out: get one fresh token, once
                token = requestToken(run, tokenEndpoint, manifestId);
                r = fetchObject(run, ref, token);
            }
            ObjectResult result = judge(run, ref, r);
            run.objects.add(result);
            if ("saved".equals(result.status())) {
                total += result.bytes();
                sink.accept(ref.entryId(), r.bytes, r.contentType);
            }
        }
    }

    private ObjectResult refuseUnsafe(Run run, URI base, ContentRef ref) {
        try {
            URI u = guard.validate(ref.url());
            if (!ref.url().startsWith(run.arexBase + "/Binary/")) {
                throw new ForbiddenTargetException("The object URL is not under the sender's ARex base.");
            }
            if (!origin(base).equals(origin(u))) {
                throw new ForbiddenTargetException("The object URL is not on the sender's origin " + origin(base) + ".");
            }
            return null;
        } catch (ForbiddenTargetException e) {
            run.finding(refusalCode(e), "local safety rule", ref.entryId() + ": " + e.getMessage());
            return new ObjectResult(ref.entryId(), ref.url(), "refused", 0, null, null);
        }
    }

    private Resp fetchObject(Run run, ContentRef ref, String token) {
        long limit = Math.min(cfg.maxObjectBytes(), ref.size() + 1); // one byte over the declared size is enough to prove a mismatch
        return send(run, HttpRequest.newBuilder(URI.create(ref.url())).timeout(readTimeout())
                .header("Authorization", "Bearer " + token).header("Accept", ref.contentType() == null ? "*/*" : ref.contentType()).GET().build(), limit);
    }

    private ObjectResult judge(Run run, ContentRef ref, Resp r) {
        if (r.error != null) {
            run.finding("TRANSFER_ERROR", "arex-47", ref.entryId() + ": " + r.error);
            return new ObjectResult(ref.entryId(), ref.url(), "error", 0, null, null);
        }
        if (r.status != 200) {
            run.step("object " + ref.entryId(), false, "HTTP " + r.status);
            return new ObjectResult(ref.entryId(), ref.url(), "http-" + r.status, 0, null, r.contentType);
        }
        String actual = SeedService.sha256(r.bytes);
        if (r.bytes.length != ref.size()) {
            run.finding("SIZE_MISMATCH", "arex-3", ref.entryId() + ": declared " + ref.size() + " bytes, received " + r.bytes.length + (r.truncated ? " or more" : "") + ".");
            return new ObjectResult(ref.entryId(), ref.url(), "size-mismatch", r.bytes.length, actual, r.contentType);
        }
        if (!actual.equalsIgnoreCase(ref.sha256())) {
            run.finding("DIGEST_MISMATCH", "arex-9", ref.entryId() + ": declared " + ref.sha256() + ", computed " + actual + ". Not saved.");
            return new ObjectResult(ref.entryId(), ref.url(), "digest-mismatch", r.bytes.length, actual, r.contentType);
        }
        if (ref.contentType() == null || !ref.contentType().startsWith("image/")) {
            run.finding("CONTENT_TYPE_NOT_IMAGE", "local safety rule", ref.entryId() + " declares '" + ref.contentType() + "', not an image; stored and offered as a download, never shown inline.");
        }
        run.step("object " + ref.entryId(), true, r.bytes.length + " bytes, sha-256 verified");
        return new ObjectResult(ref.entryId(), ref.url(), "saved", r.bytes.length, actual, r.contentType);
    }

    // ------------------------------------------------------------------ safety and HTTP helpers

    private URI checkUrl(Run run, String url, String what) {
        try {
            return guard.validate(url);
        } catch (ForbiddenTargetException e) {
            throw run.stop(refusalCode(e), "local safety rule", "Refused " + what + " (" + url + "): " + e.getMessage());
        }
    }

    /** A name that does not exist is reported as such; everything else the guard refuses is "unsafe". */
    private static String refusalCode(ForbiddenTargetException e) {
        return e instanceof org.ohia.facade.common.UnresolvableTargetException ? "HOST_NOT_FOUND" : "UNSAFE_URL_REFUSED";
    }

    private void checkSameOrigin(Run run, URI base, URI other, String what) {
        if (!origin(base).equals(origin(other))) {
            throw run.stop("UNSAFE_URL_REFUSED", "local safety rule", "Refused " + what + " (" + other + "): it is not on the sender's origin " + origin(base) + ".");
        }
    }

    private static String origin(URI u) {
        int port = u.getPort() >= 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
        return u.getScheme().toLowerCase() + "://" + u.getHost().toLowerCase() + ":" + port;
    }

    private Optional<JsonNode> getJson(Run run, String url, String token) {
        URI uri = checkUrl(run, url, "a URL to read");
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(readTimeout()).header("Accept", "application/fhir+json, application/json").GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        Resp r = send(run, b.build(), 2_000_000);
        if (r.error != null || r.status != 200) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readTree(r.body));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static final class Resp {
        int status;
        String error;
        byte[] bytes = new byte[0];
        String body = "";
        String contentType;
        boolean truncated;
    }

    /** Sends one request, reading at most {@code limit} body bytes. Never follows redirects. */
    private Resp send(Run run, HttpRequest req, long limit) {
        Resp out = new Resp();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            out.status = resp.statusCode();
            out.contentType = resp.headers().firstValue("Content-Type").orElse(null);
            try (InputStream in = resp.body()) {
                byte[] data = in.readNBytes((int) Math.min(limit, Integer.MAX_VALUE - 8));
                out.truncated = in.read() >= 0;
                out.bytes = data;
                out.body = new String(data, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            out.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.error = "Interrupted";
        }
        return out;
    }

    private Duration readTimeout() {
        return Duration.ofSeconds(cfg.readTimeoutSeconds());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String excerpt(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "..." : s);
    }

    // ------------------------------------------------------------------ per-pull state

    private static final class Stop extends RuntimeException {
        Stop(String message) {
            super(message, null, false, false);
        }
    }

    private static final class Run {
        final List<Step> steps = new ArrayList<>();
        final List<ObjectResult> objects = new ArrayList<>();
        final List<Finding> findings = new ArrayList<>();
        String stoppedBecause;
        String arexBase;
        String tokenEndpoint;
        String adType;
        JsonNode advertisedTypes;

        void step(String name, boolean ok, String detail) {
            steps.add(new Step(name, ok, detail));
        }

        void finding(String code, String specRef, String message) {
            if (findings.stream().noneMatch(f -> f.code().equals(code) && f.message().equals(message))) {
                findings.add(new Finding(code, specRef, message));
            }
        }

        /** Records a finding and a failed step, and returns the exception that ends the pull. */
        Stop stop(String code, String specRef, String message) {
            if (findings.stream().noneMatch(f -> f.code().equals(code))) {
                finding(code, specRef, message);
            }
            step("stopped", false, message);
            return new Stop(message);
        }
    }
}
