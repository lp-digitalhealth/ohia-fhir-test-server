package org.ohia.facade.arexclient;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ohia.facade.Uc04aFixture;
import org.ohia.facade.config.FacadeProperties;
import org.ohia.facade.keys.ClientAssertionSigner;
import org.ohia.facade.keys.ServerKeyStore;
import org.ohia.facade.send.SsrfGuard;

/**
 * The ARex client against a stand-in sender that really verifies our signed assertion with our
 * published key. Built by hand (no Spring) so limits and switches can differ per test. The two
 * test-only network flags are on so the stand-in on 127.0.0.1 over http is reachable; the
 * refusal rules for real addresses are tested in ArexClientRefusalsTest with the defaults.
 */
class ArexClientTest {

    @TempDir
    Path dir;

    ArexSenderStub sender;
    ArexClient client;
    ObjectNode bundle;
    List<ArexClient.ContentRef> refs;
    final Map<String, byte[]> saved = new HashMap<>();
    final ArexClient.Sink sink = (id, bytes, type) -> saved.put(id, bytes);

    static FacadeProperties props(Path dir, int maxObjects, long maxObject, long maxTotal) {
        return new FacadeProperties("http://hapi:8080/fhir", "https://facade.test", "t", dir.toString(),
                dir.resolve("clients.json").toString(), new FacadeProperties.Seed("u", "r", "p"),
                new FacadeProperties.Send(true, true, 30),
                new FacadeProperties.Client("ohia-test-server", dir.resolve("key.json").toString(),
                        "Organization/org-barton-springs-dental", "arex-manifest", maxObjects, maxObject, maxTotal, 5, 10));
    }

    ArexClient newClient(FacadeProperties p, ServerKeyStore keys) {
        return new ArexClient(p, new SsrfGuard(p), new ClientAssertionSigner(keys, Clock.systemUTC(), p), new ObjectMapper());
    }

    @BeforeEach
    void setUp() {
        FacadeProperties p = props(dir, 20, 10_000_000L, 50_000_000L);
        ServerKeyStore keys = new ServerKeyStore(p);
        client = newClient(p, keys);
        bundle = Uc04aFixture.bundle();
        sender = new ArexSenderStub(keys.publicJwks(), "ohia-test-server", Uc04aFixture.manifest(bundle), Uc04aFixture.photos());
        refs = Uc04aFixture.refs(bundle, sender.base());
    }

    @AfterEach
    void tearDown() {
        sender.stop();
    }

    private ArexClient.Outcome pull() {
        return client.pull(Uc04aFixture.MANIFEST_ID, Uc04aFixture.manifest(bundle), refs, sink);
    }

    private static List<String> codes(ArexClient.Outcome o) {
        return o.findings().stream().map(ArexClient.Finding::code).toList();
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void pullsBothImagesAndVerifiesThem() {
        ArexClient.Outcome o = pull();
        assertNull(o.stoppedBecause());
        assertEquals(sender.base() + "/arex", o.arexBase());
        assertEquals(sender.tokenEndpoint(), o.tokenEndpoint());
        assertEquals(List.of("saved", "saved"), o.objects().stream().map(ArexClient.ObjectResult::status).toList());
        assertEquals(List.of(), o.findings(), "a clean pull has no findings");
        assertArrayEquals(Uc04aFixture.resource("intraoral-photo-1.jpg"), saved.get("arex-entry-photo-1"));
        assertArrayEquals(Uc04aFixture.resource("intraoral-photo-2.jpg"), saved.get("arex-entry-photo-2"));
        // the sender really verified our signed assertion with the key we publish
        assertEquals(1, sender.tokensIssued.get());
        assertEquals(0, sender.assertionsRejected.get());
        assertEquals(1, sender.count("GET /arex/List/arex-manifest-intraoral-photos"), "the published manifest is re-read once");
        assertTrue(o.steps().stream().allMatch(ArexClient.Step::ok), o.steps().toString());
    }

    // ------------------------------------------------------------------ integrity (arex-9, arex-47)

    /** Control: a tampered image must never reach the sink, and the other image must still be pulled. */
    @Test
    void aTamperedImageIsNotSavedButTheOtherOneIs() {
        sender.tamper = true;
        ArexClient.Outcome o = pull();
        assertEquals(List.of("digest-mismatch", "digest-mismatch"), o.objects().stream().map(ArexClient.ObjectResult::status).toList());
        assertTrue(saved.isEmpty(), "nothing tampered may be handed over");
        assertTrue(codes(o).contains("DIGEST_MISMATCH"));
    }

    @Test
    void aTruncatedImageIsASizeMismatch() {
        sender.truncate = true;
        ArexClient.Outcome o = pull();
        assertEquals("size-mismatch", o.objects().get(0).status());
        assertTrue(saved.isEmpty());
        assertTrue(codes(o).contains("SIZE_MISMATCH"));
    }

    @Test
    void aSenderThatSendsMoreThanDeclaredIsCutOffAndRefused() {
        sender.extraBytes = true;
        ArexClient.Outcome o = pull();
        assertEquals("size-mismatch", o.objects().get(0).status());
        assertEquals(refs.get(0).size() + 1, o.objects().get(0).bytes(), "reading stops one byte past the declared size");
        assertTrue(saved.isEmpty());
    }

    @Test
    void anObjectThatDeclaresMoreThanOurLimitIsNeverRequested() {
        FacadeProperties p = props(dir, 20, 100L, 50_000_000L);
        ArexClient strict = newClient(p, new ServerKeyStore(p));
        ArexClient.Outcome o = strict.pull(Uc04aFixture.MANIFEST_ID, Uc04aFixture.manifest(bundle), refs, sink);
        assertEquals(List.of("too-large", "too-large"), o.objects().stream().map(ArexClient.ObjectResult::status).toList());
        assertEquals(0, sender.count("GET /arex/Binary/"), "no download may start for an over-limit object");
        assertTrue(codes(o).contains("OBJECT_TOO_LARGE"));
    }

    @Test
    void theObjectCountIsLimited() {
        FacadeProperties p = props(dir, 1, 10_000_000L, 50_000_000L);
        ArexClient one = newClient(p, new ServerKeyStore(p));
        ArexClient.Outcome o = one.pull(Uc04aFixture.MANIFEST_ID, Uc04aFixture.manifest(bundle), refs, sink);
        assertEquals(1, o.objects().size());
        assertTrue(codes(o).contains("OBJECT_LIMIT"));
        assertEquals(1, saved.size());
    }

    // ------------------------------------------------------------------ authentication

    @Test
    void aRefusedTokenStopsThePullBeforeAnythingIsRead() {
        sender.refuseToken = true;
        ArexClient.Outcome o = pull();
        assertNotNull(o.stoppedBecause());
        assertTrue(codes(o).contains("TOKEN_REFUSED"));
        assertEquals(0, sender.count("GET /arex/List/"));
        assertEquals(0, sender.count("GET /arex/Binary/"));
        assertTrue(saved.isEmpty());
    }

    @Test
    void whenTheSenderUsesAnotherAuthorizationDetailsTypeWeUseTheirsAndSayWhy() {
        sender.advertisedTypes = List.of("ihe-arex");
        sender.expectedType = "ihe-arex";
        ArexClient.Outcome o = pull();
        assertNull(o.stoppedBecause());
        assertEquals("ihe-arex", o.authorizationDetailsType());
        assertTrue(codes(o).contains("AUTHORIZATION_DETAILS_TYPE_ADJUSTED"));
        assertEquals(2, saved.size());
    }

    @Test
    void withoutAdvertisedTypesAWrongTypeIsRefusedNotGuessed() {
        sender.expectedType = "something-else"; // not advertised, so we send our default and the sender refuses it
        ArexClient.Outcome o = pull();
        assertNotNull(o.stoppedBecause());
        assertTrue(codes(o).contains("TOKEN_REFUSED"));
    }

    @Test
    void anExpiredTokenMidwayIsReplacedOnce() {
        sender.unauthorizedOnce.set(true); // the first object request answers 401, as if the token had run out
        ArexClient.Outcome o = pull();
        assertEquals(List.of("saved", "saved"), o.objects().stream().map(ArexClient.ObjectResult::status).toList());
        assertEquals(2, sender.tokensIssued.get(), "exactly one fresh token");
    }

    /**
     * Found by the end-to-end run: a sender that closes the connection after each response makes a pooled
     * POST fail with no bytes read. A transport error is retried once, with a FRESH assertion (the same
     * one would be refused as a replay if the first had arrived).
     */
    @Test
    void aTokenRequestCutOffByTheNetworkIsRetriedOnceWithAFreshAssertion() {
        sender.dropTokenRequests.set(1);
        ArexClient.Outcome o = pull();
        assertNull(o.stoppedBecause(), o.steps().toString());
        assertEquals(2, sender.count("POST /arex/auth/token"), "one cut off, one answered");
        assertEquals(1, sender.tokensIssued.get());
        assertEquals(0, sender.assertionsRejected.get(), "the retry must not look like a replay");
        assertEquals(2, saved.size());
        assertTrue(codes(o).contains("TOKEN_REQUEST_RETRIED"));
    }

    @Test
    void aTokenRequestThatKeepsFailingIsNotRetriedForever() {
        sender.dropTokenRequests.set(5);
        ArexClient.Outcome o = pull();
        assertNotNull(o.stoppedBecause());
        assertEquals(2, sender.count("POST /arex/auth/token"), "exactly one retry, then give up");
        assertTrue(codes(o).contains("TOKEN_REFUSED"));
        assertTrue(saved.isEmpty());
    }

    // ------------------------------------------------------------------ discovery (assumed; the spec is silent)

    @Test
    void discoveryFallsBackToTheOriginRoot() {
        sender.discoveryMode = "root";
        ArexClient.Outcome o = pull();
        assertNull(o.stoppedBecause());
        assertEquals(2, saved.size());
        assertEquals(1, sender.count("GET /arex/.well-known/smart-configuration"), "tried the ARex base first");
        assertEquals(1, sender.count("GET /.well-known/smart-configuration"));
    }

    @Test
    void noDiscoveryAtAllStopsWithAClearFinding() {
        sender.discoveryMode = "none";
        ArexClient.Outcome o = pull();
        assertTrue(codes(o).contains("TOKEN_ENDPOINT_NOT_DISCOVERED"));
        assertEquals(0, sender.count("POST /arex/auth/token"), "no token may be requested from a guessed endpoint");
        assertTrue(saved.isEmpty());
    }

    // ------------------------------------------------------------------ the published manifest (arex-39)

    /** arex-39: a retracted manifest means do not proceed. This is a positive signal, not a gap. */
    @Test
    void aRetractedPublishedManifestStopsThePullWithNoDownloads() {
        sender.manifestMode = "retracted";
        ArexClient.Outcome o = pull();
        assertNotNull(o.stoppedBecause());
        assertTrue(codes(o).contains("MANIFEST_NOT_CURRENT"));
        assertEquals(0, sender.count("GET /arex/Binary/"));
        assertTrue(saved.isEmpty());
    }

    /** Report-first: a sender with no manifest endpoint is a finding, not a reason to refuse the images. */
    @Test
    void aMissingPublishedManifestIsAFindingAndThePullContinues() {
        sender.manifestMode = "missing";
        ArexClient.Outcome o = pull();
        assertNull(o.stoppedBecause());
        assertTrue(codes(o).contains("PUBLISHED_MANIFEST_UNAVAILABLE"));
        assertEquals(2, saved.size());
    }

    @Test
    void aPublishedManifestThatDiffersIsAFindingAndThePullContinues() {
        sender.manifestMode = "differs";
        ArexClient.Outcome o = pull();
        assertNull(o.stoppedBecause());
        assertTrue(codes(o).contains("PUBLISHED_MANIFEST_DIFFERS"));
        assertEquals(2, saved.size());
    }

    // ------------------------------------------------------------------ redirects and content types

    /** Control: a sender must not be able to bounce us to another address. The redirect target must never be requested. */
    @Test
    void aRedirectIsNeverFollowed() {
        sender.redirectBinaryTo = sender.base() + "/internal/secret";
        ArexClient.Outcome o = pull();
        assertEquals("http-302", o.objects().get(0).status());
        assertEquals(0, sender.count("GET /internal/secret"));
        assertTrue(saved.isEmpty());
    }

    @Test
    void aNonImageDeclaredTypeIsAFindingButStillVerified() {
        refs = refs.stream().map(r -> new ArexClient.ContentRef(r.entryId(), r.url(), "text/html", r.size(), r.sha256())).toList();
        ArexClient.Outcome o = pull();
        assertTrue(codes(o).contains("CONTENT_TYPE_NOT_IMAGE"));
        assertFalse(o.objects().isEmpty());
    }
}
