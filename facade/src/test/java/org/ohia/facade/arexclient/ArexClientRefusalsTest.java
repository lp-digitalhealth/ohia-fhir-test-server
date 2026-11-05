package org.ohia.facade.arexclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The payload is written by a stranger, so every URL in it is hostile until proven otherwise.
 * Part of this class runs with the PRODUCTION defaults (loopback and plain http refused) and part
 * with the test-only network flags on, to prove the same-origin rules with a second server that
 * must never be contacted.
 */
class ArexClientRefusalsTest {

    @TempDir
    Path dir;

    ArexSenderStub sender;
    ArexSenderStub other;
    ObjectNode bundle;
    final Map<String, byte[]> saved = new HashMap<>();
    final ArexClient.Sink sink = (id, bytes, type) -> saved.put(id, bytes);
    ServerKeyStore keys;

    private FacadeProperties props(boolean testFlags) {
        return new FacadeProperties("http://hapi:8080/fhir", "https://facade.test", "t", dir.toString(),
                dir.resolve("clients.json").toString(), new FacadeProperties.Seed("u", "r", "p"),
                new FacadeProperties.Send(testFlags, testFlags, 30),
                new FacadeProperties.Client("ohia-test-server", dir.resolve("key.json").toString(),
                        "Organization/org-barton-springs-dental", "arex-manifest", 20, 10_000_000L, 50_000_000L, 5, 10));
    }

    private ArexClient client(boolean testFlags) {
        FacadeProperties p = props(testFlags);
        return new ArexClient(p, new SsrfGuard(p), new ClientAssertionSigner(keys, Clock.systemUTC(), p), new ObjectMapper());
    }

    @BeforeEach
    void setUp() {
        keys = new ServerKeyStore(props(true));
        bundle = Uc04aFixture.bundle();
        sender = new ArexSenderStub(keys.publicJwks(), "ohia-test-server", Uc04aFixture.manifest(bundle), Uc04aFixture.photos());
        other = new ArexSenderStub(keys.publicJwks(), "ohia-test-server", Uc04aFixture.manifest(bundle), Uc04aFixture.photos());
    }

    @AfterEach
    void tearDown() {
        sender.stop();
        other.stop();
    }

    private ArexClient.Outcome pull(ArexClient c, List<ArexClient.ContentRef> refs) {
        return c.pull(Uc04aFixture.MANIFEST_ID, Uc04aFixture.manifest(bundle), refs, sink);
    }

    private static List<String> codes(ArexClient.Outcome o) {
        return o.findings().stream().map(ArexClient.Finding::code).toList();
    }

    // ------------------------------------------------------------------ production defaults

    /** The stand-in is plain http on loopback: with the production defaults it must not even be contacted. */
    @Test
    void withTheProductionDefaultsLoopbackAndHttpAreNeverContacted() {
        ArexClient.Outcome o = pull(client(false), Uc04aFixture.refs(bundle, sender.base()));
        assertNotNull(o.stoppedBecause());
        assertTrue(codes(o).contains("UNSAFE_URL_REFUSED"), codes(o).toString());
        assertEquals(0, sender.requests.size(), "not a single request may reach it: " + sender.requests);
        assertTrue(saved.isEmpty());
    }

    @Test
    void privateAndMetadataAddressesAreRefusedWithoutAnyConnection() {
        for (String host : new String[] {"https://localhost", "https://127.0.0.1", "https://10.0.0.5", "https://172.17.0.2",
                "https://192.168.1.10", "https://169.254.169.254", "https://[::1]", "https://hapi:8080"}) {
            List<ArexClient.ContentRef> refs = List.of(new ArexClient.ContentRef("arex-entry-photo-1",
                    host + "/arex/Binary/arex-entry-photo-1", "image/jpeg", 632, "00"));
            ArexClient.Outcome o = pull(client(false), refs);
            assertNotNull(o.stoppedBecause(), host);
            assertTrue(codes(o).contains("UNSAFE_URL_REFUSED"), host + " -> " + codes(o));
        }
        assertTrue(saved.isEmpty());
    }

    @Test
    void aUrlThatIsNotAnArexBinaryUrlIsRefused() {
        List<ArexClient.ContentRef> refs = List.of(new ArexClient.ContentRef("x", sender.base() + "/not-arex/photo.jpg", "image/jpeg", 632, "00"));
        ArexClient.Outcome o = pull(client(true), refs);
        assertNotNull(o.stoppedBecause());
        assertTrue(codes(o).contains("UNSAFE_URL_REFUSED"));
        assertEquals(0, sender.requests.size());
    }

    // ------------------------------------------------------------------ same-origin rules (test flags on)

    /** Control: one image on a different origin is refused on its own; the honest image is still pulled. */
    @Test
    void anObjectOnAnotherOriginIsRefusedAndTheOtherObjectsContinue() {
        List<ArexClient.ContentRef> refs = Uc04aFixture.refs(bundle, sender.base());
        ArexClient.ContentRef second = refs.get(1);
        refs = List.of(refs.get(0), new ArexClient.ContentRef(second.entryId(), other.base() + "/arex/Binary/" + second.entryId(),
                second.contentType(), second.size(), second.sha256()));
        ArexClient.Outcome o = pull(client(true), refs);
        assertEquals(List.of("saved", "refused"), o.objects().stream().map(ArexClient.ObjectResult::status).toList());
        assertEquals(0, other.requests.size(), "the other origin must never be contacted: " + other.requests);
        assertEquals(1, saved.size());
        assertTrue(codes(o).contains("UNSAFE_URL_REFUSED"));
    }

    @Test
    void anObjectOutsideTheSendersArexBaseIsRefused() {
        List<ArexClient.ContentRef> refs = Uc04aFixture.refs(bundle, sender.base());
        ArexClient.ContentRef second = refs.get(1);
        refs = List.of(refs.get(0), new ArexClient.ContentRef(second.entryId(), sender.base() + "/elsewhere/Binary/" + second.entryId(),
                second.contentType(), second.size(), second.sha256()));
        ArexClient.Outcome o = pull(client(true), refs);
        assertEquals("refused", o.objects().get(1).status());
        assertEquals(0, sender.count("GET /elsewhere/"));
    }

    /** Control: discovery must not be able to send our signed assertion to a server the payload did not name. */
    @Test
    void aTokenEndpointOnAnotherOriginIsRefusedAndNoAssertionIsSentThere() {
        sender.tokenEndpointOverride = other.tokenEndpoint();
        ArexClient.Outcome o = pull(client(true), Uc04aFixture.refs(bundle, sender.base()));
        assertNotNull(o.stoppedBecause());
        assertTrue(codes(o).contains("UNSAFE_URL_REFUSED"));
        assertEquals(0, other.requests.size(), "our assertion must never be posted to " + other.base() + ": " + other.requests);
        assertEquals(0, sender.count("GET /arex/Binary/"));
        assertNull(o.authorizationDetailsType(), "no token request was even prepared");
    }
}
