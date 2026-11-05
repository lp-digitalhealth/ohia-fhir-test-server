package org.ohia.facade.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.ohia.facade.arex.ClientAssertionVerifier;
import org.ohia.facade.arex.OAuthException;
import org.ohia.facade.clients.ClientEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The server's published identity, and a round trip: our signer against the facade's own verifier. */
@SpringBootTest
@AutoConfigureMockMvc
class ServerIdentityTest {

    static final String S1 = "unit-test-token-123";
    static final String AUD = "https://facade.test/arex/auth/token";
    static final Path dataDir = tempDir();
    static final Path clientsFile = dataDir.resolveSibling(dataDir.getFileName() + "-clients.json");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("facade.api-token", () -> S1);
        r.add("facade.data-dir", dataDir::toString);
        r.add("facade.clients-file", clientsFile::toString);
        r.add("facade.public-base-url", () -> "https://facade.test");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ServerKeyStore keys;
    @Autowired
    ClientAssertionSigner signer;
    @Autowired
    ClientAssertionVerifier verifier;

    @Test
    void thePublicKeySetIsOpenAndPublicOnly() throws Exception {
        MvcResult res = mvc.perform(get("/arex/jwks.json")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/jwk-set+json")))
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kid").value("ohia-server-1"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS384")).andReturn();
        String body = res.getResponse().getContentAsString();
        assertFalse(body.contains("\"d\":"), body);
        assertFalse(body.contains("\"p\":"), body);
        assertFalse(body.contains("\"q\":"), body);
        assertEquals(keys.publicJwks().getKeys().get(0).toJSONObject().get("n"),
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("keys").get(0).get("n").asText());
    }

    @Test
    void identityIsForTheOrganizerOnly() throws Exception {
        mvc.perform(get("/ode/identity")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ode/identity").header("Authorization", "Bearer " + S1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.client_id").value("ohia-test-server"))
                .andExpect(jsonPath("$.kid").value("ohia-server-1"))
                .andExpect(jsonPath("$.signing_algorithm").value("RS384"))
                .andExpect(jsonPath("$.jwks_uri").value("https://facade.test/arex/jwks.json"))
                .andExpect(jsonPath("$.requesting_organization").value("Organization/org-barton-springs-dental"))
                .andExpect(jsonPath("$.authorization_details_type").value("arex-manifest"));
    }

    /** Signer and verifier were written separately from the spec; if either has a wrong claim, this fails. */
    @Test
    void ourAssertionIsAcceptedByTheFacadesOwnVerifier() throws Exception {
        register();
        ClientEntry client = verifier.verify(signer.sign(AUD), AUD);
        assertEquals("ohia-test-server", client.clientId());
    }

    @Test
    void theAssertionCarriesTheClaimsTheSpecAsksFor() throws Exception {
        SignedJWT jwt = SignedJWT.parse(signer.sign(AUD));
        assertEquals("RS384", jwt.getHeader().getAlgorithm().getName());
        assertEquals("ohia-server-1", jwt.getHeader().getKeyID());
        assertEquals("ohia-test-server", jwt.getJWTClaimsSet().getIssuer());
        assertEquals("ohia-test-server", jwt.getJWTClaimsSet().getSubject());
        assertEquals(java.util.List.of(AUD), jwt.getJWTClaimsSet().getAudience());
        long lifetime = (jwt.getJWTClaimsSet().getExpirationTime().getTime() - jwt.getJWTClaimsSet().getIssueTime().getTime()) / 1000;
        assertTrue(lifetime > 0 && lifetime <= 300, "lifetime must be within the five-minute limit, was " + lifetime);
        assertNotEquals(jwt.getJWTClaimsSet().getJWTID(), SignedJWT.parse(signer.sign(AUD)).getJWTClaimsSet().getJWTID(), "every assertion needs a fresh jti");
    }

    /** Controls: the verifier must still refuse a wrong audience and a replay of our own assertion. */
    @Test
    void theVerifierStillRefusesAWrongAudienceAndAReplay() throws Exception {
        register();
        assertThrows(OAuthException.class, () -> verifier.verify(signer.sign("https://elsewhere.test/token"), AUD));
        String once = signer.sign(AUD);
        verifier.verify(once, AUD);
        assertThrows(OAuthException.class, () -> verifier.verify(once, AUD));
    }

    private void register() throws IOException {
        Files.writeString(clientsFile, "{\"clients\":[{\"clientId\":\"ohia-test-server\","
                + "\"requestingOrganization\":\"Organization/org-barton-springs-dental\","
                + "\"allowedTargetPrefixes\":[\"https://a.example.org/fhir\"],\"jwks\":" + keys.publicJwks() + "}]}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(clientsFile, FileTime.fromMillis(System.nanoTime() / 1000));
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("facade-test-identity");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
