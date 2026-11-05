package org.ohia.facade.arex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ohia.facade.UcStubs;
import org.ohia.facade.seed.PackageIndex;
import org.ohia.facade.seed.PackageStore;
import org.ohia.facade.seed.SeedService;
import org.ohia.facade.send.ManifestRecord;
import org.ohia.facade.send.ManifestStore;
import org.ohia.facade.send.PerSendCopier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ARex authorization and content retrieval, derived from the spec statements (arex-NN) that the
 * plan quotes. Time is controlled by a mutable clock so that expiry rules are tested exactly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ArexTest.ClockOverride.class)
class ArexTest {

    static final String S1 = "unit-test-token-123";
    static final String AUD = "https://facade.test/arex/auth/token";
    static final Instant T0 = Instant.parse("2026-11-05T12:00:00Z");
    static final String ORG_A = "Organization/org-restorative-dental-tx";
    static final String ORG_B = "Organization/org-pediatric-dental-tx";
    static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    static long registryVersion = 1;
    static final MutableClock CLOCK = new MutableClock(T0);
    static final Path dataDir = tempDir("facade-test-arex");
    static final Path clientsFile = dataDir.resolveSibling(dataDir.getFileName() + "-clients.json");
    static RSAKey rsaA;
    static ECKey ecA;
    static RSAKey rsaB;
    static RSAKey attacker;

    static {
        try {
            rsaA = new RSAKeyGenerator(2048).keyID("rsa-a").generate();
            ecA = new ECKeyGenerator(Curve.P_384).keyID("ec-a").generate();
            rsaB = new RSAKeyGenerator(2048).keyID("rsa-b").generate();
            attacker = new RSAKeyGenerator(2048).keyID("rsa-a").generate(); // same kid, different key
            writeRegistry(ORG_A);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void writeRegistry(String orgForA) throws IOException {
        String jwksA = new JWKSet(List.of(rsaA.toPublicJWK(), ecA.toPublicJWK())).toString();
        String jwksB = new JWKSet(List.of(rsaB.toPublicJWK())).toString();
        Files.writeString(clientsFile, "{\"clients\":["
                + "{\"clientId\":\"tester-a\",\"requestingOrganization\":\"" + orgForA + "\","
                + "\"allowedTargetPrefixes\":[\"https://a.example.org/fhir\"],\"jwks\":" + jwksA + "},"
                + "{\"clientId\":\"tester-b\",\"requestingOrganization\":\"" + ORG_B + "\","
                + "\"allowedTargetPrefixes\":[\"https://b.example.org/fhir\"],\"jwks\":" + jwksB + "}]}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(clientsFile, FileTime.fromMillis(System.nanoTime() / 1000 + registryVersion++));
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("facade.api-token", () -> S1);
        r.add("facade.data-dir", dataDir::toString);
        r.add("facade.clients-file", clientsFile::toString);
        r.add("facade.public-base-url", () -> "https://facade.test");
        r.add("facade.hapi-base-url", UcStubs::hapiBaseUrl);
        r.add("facade.seed.data-base-url", UcStubs::dataBaseUrl);
        r.add("facade.seed.data-ref", () -> UcStubs.REF);
        r.add("facade.seed.package-path", () -> UcStubs.PKG);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ManifestStore manifests;
    @Autowired
    PackageStore packages;

    byte[] periapical;
    byte[] bitewing;

    @BeforeEach
    void setUp() throws Exception {
        CLOCK.set(T0);
        writeRegistry(ORG_A);
        for (String sub : new String[] {"manifests", "package", "arex-payloads"}) {
            Path p = dataDir.resolve(sub);
            if (Files.exists(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                }
            }
        }
        periapical = UcStubs.fixtureBytes("radiograph-periapical.png");
        bitewing = UcStubs.fixtureBytes("radiograph-bitewing.png");
        packages.save(new PackageIndex("testref", "arex-manifest-radiographs", List.of(),
                List.of(new PackageIndex.Payload("arex-entry-periapical", "image/png", periapical.length, SeedService.sha256(periapical)),
                        new PackageIndex.Payload("arex-entry-bitewing", "image/png", bitewing.length, SeedService.sha256(bitewing)))),
                Map.of("arex-entry-periapical", periapical, "arex-entry-bitewing", bitewing));
        // man-a: tester-a's manifest (two images); man-a2: another manifest of the same tester;
        // man-b: tester-b's; man-short: tester-a's, but it expires 60 seconds from now.
        saveManifest("man-a", "tester-a", ORG_A, T0.plus(Duration.ofDays(30)), List.of(entry("bin-peri", "arex-entry-periapical", periapical), entry("bin-bite", "arex-entry-bitewing", bitewing)));
        saveManifest("man-a2", "tester-a", ORG_A, T0.plus(Duration.ofDays(30)), List.of(entry("bin-other", "arex-entry-periapical", periapical)));
        saveManifest("man-b", "tester-b", ORG_B, T0.plus(Duration.ofDays(30)), List.of(entry("bin-b", "arex-entry-periapical", periapical)));
        saveManifest("man-short", "tester-a", ORG_A, T0.plus(Duration.ofSeconds(60)), List.of(entry("bin-short", "arex-entry-periapical", periapical)));
    }

    // ------------------------------------------------------------------ discovery

    @Test
    void discoveryAdvertisesBackendServicesWithPrivateKeyJwt() throws Exception {
        mvc.perform(get("/arex/.well-known/smart-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_endpoint").value(AUD))
                .andExpect(jsonPath("$.grant_types_supported[0]").value("client_credentials"))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported[0]").value("private_key_jwt"))
                .andExpect(jsonPath("$.token_endpoint_auth_signing_alg_values_supported[0]").value("RS384"))
                .andExpect(jsonPath("$.token_endpoint_auth_signing_alg_values_supported[1]").value("ES384"));
    }

    // ------------------------------------------------------------------ token endpoint (arex-19, 23-25)

    @Test
    void rs384AssertionGetsAFiveMinuteManifestScopedToken() throws Exception {
        mvc.perform(tokenRequest(rs384("tester-a", AUD, 120, UUID.randomUUID().toString()), "man-a", null))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.token_type").value("bearer"))
                .andExpect(jsonPath("$.expires_in").value(300))
                .andExpect(jsonPath("$.scope").value("system/Binary.r system/List.rs"))
                .andExpect(jsonPath("$.authorization_details[0].manifest").value("man-a"))
                .andExpect(jsonPath("$.access_token").isNotEmpty());
    }

    @Test
    void es384AssertionAlsoWorks() throws Exception {
        mvc.perform(tokenRequest(es384("tester-a", AUD, 120, UUID.randomUUID().toString()), "man-a", "system/List.rs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("system/List.rs"));
    }

    @Test
    void anAssertionSignedByAnotherKeyIsRefusedEvenWithTheSameKeyId() throws Exception {
        mvc.perform(tokenRequest(sign(attacker, JWSAlgorithm.RS384, claims("tester-a", "tester-a", AUD, 120, UUID.randomUUID().toString())), "man-a", null))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void sharedSecretAndUnsignedAssertionsAreRefused() throws Exception {
        SignedJWT hs = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims("tester-a", "tester-a", AUD, 120, UUID.randomUUID().toString()));
        hs.sign(new MACSigner(new byte[32]));
        mvc.perform(tokenRequest(hs.serialize(), "man-a", null)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
        String none = new PlainJWT(claims("tester-a", "tester-a", AUD, 120, UUID.randomUUID().toString())).serialize();
        mvc.perform(tokenRequest(none, "man-a", null)).andExpect(status().isUnauthorized());
    }

    @Test
    void everyAssertionDefectGivesTheSameInvalidClient() throws Exception {
        String[] defective = {
                rs384("tester-a", "https://elsewhere.test/token", 120, "j1" + UUID.randomUUID()),        // wrong audience
                sign(rsaA, JWSAlgorithm.RS384, claims("tester-a", "tester-b", AUD, 120, "j2" + UUID.randomUUID())), // iss != sub
                sign(rsaA, JWSAlgorithm.RS384, claims("nobody", "nobody", AUD, 120, "j3" + UUID.randomUUID())),   // unknown client
                rs384("tester-a", AUD, -120, "j4" + UUID.randomUUID()),                                 // already expired
                rs384("tester-a", AUD, 3600, "j5" + UUID.randomUUID()),                                 // lives far longer than 5 minutes
                sign(rsaA, JWSAlgorithm.RS384, claims("tester-a", "tester-a", AUD, 120, null)),          // no jti
                sign(rsaB, JWSAlgorithm.RS384, claims("tester-a", "tester-a", AUD, 120, "j7" + UUID.randomUUID())) // tester-b's key
        };
        String first = null;
        for (String assertion : defective) {
            String body = mvc.perform(tokenRequest(assertion, "man-a", null)).andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();
            if (first == null) {
                first = body;
            }
            assertEquals(first, body, "a caller must not learn which check failed");
        }
    }

    @Test
    void anAssertionCanBeUsedOnlyOnce() throws Exception {
        String assertion = rs384("tester-a", AUD, 120, "replayed-jti");
        mvc.perform(tokenRequest(assertion, "man-a", null)).andExpect(status().isOk());
        mvc.perform(tokenRequest(assertion, "man-a", null)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void theGrantMustBeScopedToAManifest() throws Exception {
        mvc.perform(post("/arex/auth/token").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "client_credentials").param("client_assertion_type", ASSERTION_TYPE)
                .param("client_assertion", rs384("tester-a", AUD, 120, UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post("/arex/auth/token").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "client_credentials").param("client_assertion_type", ASSERTION_TYPE)
                .param("client_assertion", rs384("tester-a", AUD, 120, UUID.randomUUID().toString()))
                .param("authorization_details", "[{\"type\":\"something-else\",\"manifest\":\"man-a\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void otherTokenRequestErrors() throws Exception {
        mvc.perform(tokenRequest(rs384("tester-a", AUD, 120, UUID.randomUUID().toString()), "man-a", "system/Patient.rs"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
        mvc.perform(post("/arex/auth/token").contentType("application/x-www-form-urlencoded").param("grant_type", "password"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("unsupported_grant_type"));
        mvc.perform(post("/arex/auth/token").contentType("application/x-www-form-urlencoded").param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
    }

    /** arex-25 and the rule that the answer must not reveal which manifests exist. */
    @Test
    void unavailableManifestsAllGetTheSameInvalidGrant() throws Exception {
        String other = body(tokenRequest(rs384("tester-a", AUD, 120, UUID.randomUUID().toString()), "man-b", null));       // someone else's
        String unknown = body(tokenRequest(rs384("tester-a", AUD, 120, UUID.randomUUID().toString()), "no-such", null));    // does not exist
        CLOCK.advance(Duration.ofSeconds(61));
        String expired = body(tokenRequest(rs384("tester-a", AUD, 120, UUID.randomUUID().toString()), "man-short", null));  // expired
        mvc.perform(post("/ode/manifests/man-a2/retract").header("Authorization", "Bearer " + S1)).andExpect(status().isOk());
        String retracted = body(tokenRequest(rs384("tester-a", AUD, 120, UUID.randomUUID().toString()), "man-a2", null));   // retracted
        assertTrue(other.contains("invalid_grant"), other);
        assertEquals(other, unknown);
        assertEquals(other, expired);
        assertEquals(other, retracted);
    }

    @Test
    void authenticatingIsNotAuthorizingTheRegisteredOrganizationMustMatchTheManifest() throws Exception {
        writeRegistry(ORG_B); // tester-a is now registered as a different organization than the manifest names
        mvc.perform(tokenRequest(rs384("tester-a", AUD, 120, UUID.randomUUID().toString()), "man-a", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    // ------------------------------------------------------------------ retrieval

    @Test
    void anonymousAndGarbageRetrievalIsAlwaysRefused() throws Exception {
        for (String path : new String[] {"/arex/List/man-a", "/arex/Binary/bin-peri",
                "/arex/List?arex-manifest-transaction-identifier=x"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("Bearer")));
            mvc.perform(get(path).header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer " + S1)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void anEntitledTokenReadsItsManifest() throws Exception {
        String token = tokenFor("tester-a", "man-a");
        MvcResult res = mvc.perform(get("/arex/List/man-a").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/fhir+json")))
                .andReturn();
        assertEquals(manifests.load("man-a").orElseThrow().manifest(), UcStubs.M.readTree(res.getResponse().getContentAsString()));
    }

    @Test
    void binaryIsServedAsNativeOctetsAndMatchesItsDigest() throws Exception {
        String token = tokenFor("tester-a", "man-a");
        MvcResult res = mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token).header("Accept", "image/png"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Accept-Ranges", "bytes")).andReturn();
        byte[] served = res.getResponse().getContentAsByteArray();
        assertArrayEquals(periapical, served);
        assertEquals(SeedService.sha256(periapical), SeedService.sha256(served));
        mvc.perform(get("/arex/Binary/bin-bite").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void binaryAsAFhirBinaryWhenJsonIsAsked() throws Exception {
        String token = tokenFor("tester-a", "man-a");
        MvcResult res = mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token).header("Accept", "application/fhir+json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resourceType").value("Binary"))
                .andExpect(jsonPath("$.contentType").value("image/png")).andReturn();
        JsonNode j = UcStubs.M.readTree(res.getResponse().getContentAsString());
        assertArrayEquals(periapical, Base64.getDecoder().decode(j.get("data").asText()));
    }

    @Test
    void rangeRequestsAreSupported() throws Exception {
        String token = tokenFor("tester-a", "man-a");
        int len = periapical.length;
        byte[] first10 = mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token).header("Range", "bytes=0-9"))
                .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range", "bytes 0-9/" + len))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(java.util.Arrays.copyOfRange(periapical, 0, 10), first10);
        byte[] tail = mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token).header("Range", "bytes=" + (len - 7) + "-"))
                .andExpect(status().isPartialContent()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(java.util.Arrays.copyOfRange(periapical, len - 7, len), tail);
        byte[] suffix = mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token).header("Range", "bytes=-5"))
                .andExpect(status().isPartialContent()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(java.util.Arrays.copyOfRange(periapical, len - 5, len), suffix);
        mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token).header("Range", "bytes=500-"))
                .andExpect(status().isRequestedRangeNotSatisfiable()).andExpect(header().string("Content-Range", "bytes */" + len));
        mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token).header("Range", "bytes=a-b"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
    }

    /** arex-23: a token for one manifest must not open another manifest's content. */
    @Test
    void aTokenForOneManifestDoesNotOpenAnother() throws Exception {
        String token = tokenFor("tester-a", "man-a");
        mvc.perform(get("/arex/List/man-a2").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get("/arex/Binary/bin-other").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get("/arex/List/man-b").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get("/arex/Binary/bin-b").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get("/arex/Binary/does-not-exist").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    /** arex-25 at retrieval time: if the registered organization stops matching, an issued token stops working. */
    @Test
    void organizationIsRecheckedOnEveryRetrieval() throws Exception {
        String token = tokenFor("tester-a", "man-a");
        mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        writeRegistry(ORG_B);
        mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get("/arex/List/man-a").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    /**
     * arex-26: after the manifest's expiry, retrieval is refused whatever the token says. The control
     * is a second token, issued at the same moment for a manifest that has not expired: it still works
     * after the same delay, so the refusal can only be caused by the manifest's own expiry.
     */
    @Test
    void anExpiredManifestIsRefusedEvenWithAStillValidToken() throws Exception {
        String shortToken = tokenFor("tester-a", "man-short"); // token lives 300 s, manifest only 60 s
        String longToken = tokenFor("tester-a", "man-a");
        mvc.perform(get("/arex/Binary/bin-short").header("Authorization", "Bearer " + shortToken)).andExpect(status().isOk());
        CLOCK.advance(Duration.ofSeconds(61));
        mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + longToken)).andExpect(status().isOk());
        mvc.perform(get("/arex/Binary/bin-short").header("Authorization", "Bearer " + shortToken)).andExpect(status().isNotFound());
        mvc.perform(get("/arex/List/man-short").header("Authorization", "Bearer " + shortToken)).andExpect(status().isNotFound());
    }

    @Test
    void tokensExpireAfterFiveMinutes() throws Exception {
        String token = tokenFor("tester-a", "man-a");
        CLOCK.advance(Duration.ofSeconds(299));
        mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        CLOCK.advance(Duration.ofSeconds(2));
        mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    /** arex-14 and arex-15: retraction ends access at once, including for tokens already issued. */
    @Test
    void retractionEndsAccessImmediately() throws Exception {
        String token = tokenFor("tester-a", "man-a");
        mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/ode/manifests/man-a/retract").header("Authorization", "Bearer " + S1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.retracted").value(true));
        mvc.perform(get("/arex/Binary/bin-peri").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get("/arex/List/man-a").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(tokenRequest(rs384("tester-a", AUD, 120, UUID.randomUUID().toString()), "man-a", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        assertEquals("retracted", manifests.load("man-a").orElseThrow().manifest().get("status").asText());
    }

    @Test
    void searchByTransactionIdentifierOnlyFindsTheTokensOwnManifest() throws Exception {
        String token = tokenFor("tester-a", "man-a");
        String ref = "urn:ohia:referral-id|REF-2026-UC07-001";
        mvc.perform(get("/arex/List").param("arex-manifest-transaction-identifier", ref).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resourceType").value("Bundle"))
                .andExpect(jsonPath("$.type").value("searchset")).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entry[0].resource.id").value("arex-manifest-radiographs"));
        mvc.perform(get("/arex/List").param("arex-manifest-transaction-identifier", "REF-2026-UC07-001").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/arex/List").param("arex-manifest-transaction-identifier", "urn:ohia:referral-id|OTHER").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/arex/List").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
    }

    @Test
    void anArexTokenDoesNotOpenTheOrganizerEndpoints() throws Exception {
        String token = tokenFor("tester-a", "man-a");
        mvc.perform(get("/ode/package").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/ode/manifests/man-a/retract").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void organizerCanInspectAndRetractOnlyWithTheSharedToken() throws Exception {
        mvc.perform(get("/ode/manifests/man-a")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ode/manifests/man-a").header("Authorization", "Bearer " + S1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clientId").value("tester-a"))
                .andExpect(jsonPath("$.contentObjects").value(2)).andExpect(jsonPath("$.retracted").value(false));
        mvc.perform(get("/ode/manifests/nope").header("Authorization", "Bearer " + S1)).andExpect(status().isNotFound());
        mvc.perform(post("/ode/manifests/nope/retract").header("Authorization", "Bearer " + S1)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private void saveManifest(String id, String clientId, String org, Instant expires, List<PerSendCopier.Entry> entries) throws IOException {
        JsonNode authored = UcStubs.M.readTree(UcStubs.fixtureText("interaction-01-bundle.json"));
        JsonNode list = null;
        for (JsonNode e : authored.get("entry")) {
            if (e.get("resource").get("resourceType").asText().equals("List")) {
                list = e.get("resource");
            }
        }
        assertNotNull(list);
        manifests.save(new ManifestRecord(id, "send-" + id, clientId, org, T0, expires, false, "REF-2026-UC07-001", list, entries));
    }

    private static PerSendCopier.Entry entry(String binaryId, String originalId, byte[] bytes) {
        return new PerSendCopier.Entry(originalId, binaryId, "image/png", bytes.length, SeedService.sha256(bytes));
    }

    private org.springframework.test.web.servlet.RequestBuilder tokenRequest(String assertion, String manifestId, String scope) {
        var b = post("/arex/auth/token").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "client_credentials").param("client_assertion_type", ASSERTION_TYPE)
                .param("client_assertion", assertion)
                .param("authorization_details", "[{\"type\":\"arex-manifest\",\"manifest\":\"" + manifestId + "\"}]");
        return scope == null ? b : b.param("scope", scope);
    }

    private String body(org.springframework.test.web.servlet.RequestBuilder r) throws Exception {
        return mvc.perform(r).andReturn().getResponse().getContentAsString();
    }

    private String tokenFor(String clientId, String manifestId) throws Exception {
        String assertion = clientId.equals("tester-a") ? rs384(clientId, AUD, 120, UUID.randomUUID().toString())
                : sign(rsaB, JWSAlgorithm.RS384, claims(clientId, clientId, AUD, 120, UUID.randomUUID().toString()));
        String response = mvc.perform(tokenRequest(assertion, manifestId, null)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UcStubs.M.readTree(response).get("access_token").asText();
    }

    private String rs384(String clientId, String aud, int secondsFromNow, String jti) throws Exception {
        return sign(rsaA, JWSAlgorithm.RS384, claims(clientId, clientId, aud, secondsFromNow, jti));
    }

    private String es384(String clientId, String aud, int secondsFromNow, String jti) throws Exception {
        return sign(ecA, JWSAlgorithm.ES384, claims(clientId, clientId, aud, secondsFromNow, jti));
    }

    private static JWTClaimsSet claims(String iss, String sub, String aud, int secondsFromNow, String jti) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(iss).subject(sub).audience(aud)
                .issueTime(Date.from(CLOCK.instant())).expirationTime(Date.from(CLOCK.instant().plusSeconds(secondsFromNow)));
        if (jti != null) {
            b.jwtID(jti);
        }
        return b.build();
    }

    private static String sign(RSAKey key, JWSAlgorithm alg, JWTClaimsSet c) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(key.getKeyID()).build(), c);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static String sign(ECKey key, JWSAlgorithm alg, JWTClaimsSet c) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(key.getKeyID()).build(), c);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A clock the tests can move. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant t) {
            this.now = t;
        }

        void advance(Duration d) {
            this.now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
