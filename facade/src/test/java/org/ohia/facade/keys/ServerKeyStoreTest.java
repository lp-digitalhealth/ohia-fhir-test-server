package org.ohia.facade.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ohia.facade.config.FacadeProperties;

class ServerKeyStoreTest {

    @TempDir
    Path dir;

    private ServerKeyStore store(Path keyFile) {
        return new ServerKeyStore(new FacadeProperties("h", "p", "t", dir.toString(), "/c.json",
                new FacadeProperties.Seed("u", "r", "p"), new FacadeProperties.Send(false, false, 30),
                new FacadeProperties.Client("ohia-test-server", keyFile.toString(), "Organization/org-barton-springs-dental",
                        "arex-manifest", 20, 10_000_000L, 50_000_000L, 5, 60)));
    }

    @Test
    void createsAKeyOnFirstUseAndReloadsTheSameOneAfterwards() throws IOException {
        Path file = dir.resolve("keys").resolve("server-key.json");
        assertFalse(Files.exists(file), "nothing is created before first use");
        RSAKey first = store(file).signingKey();
        assertTrue(Files.isRegularFile(file));
        RSAKey again = store(file).signingKey(); // a new store object, as after a restart
        assertEquals(first.getModulus(), again.getModulus());
        assertEquals(first.getPrivateExponent(), again.getPrivateExponent());
        assertEquals("ohia-server-1", again.getKeyID());
        assertEquals(JWSAlgorithm.RS384, again.getAlgorithm());
        assertTrue(again.size() >= 2048);
    }

    @Test
    void theKeyFileIsReadableByTheOwnerOnly() throws IOException {
        Path file = dir.resolve("server-key.json");
        store(file).signingKey();
        try {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system: the permission check cannot apply
        }
        assertFalse(Files.exists(dir.resolve("server-key.json.tmp")), "no temporary file may be left behind");
    }

    /** The published key set must be usable by a sender and must reveal nothing private. */
    @Test
    void thePublishedKeySetHasNoPrivateMembers() {
        String json = store(dir.resolve("server-key.json")).publicJwks().toString();
        for (String secret : new String[] {"\"d\"", "\"p\"", "\"q\"", "\"dp\"", "\"dq\"", "\"qi\""}) {
            assertFalse(json.contains(secret + ":"), "private member " + secret + " must not be published: " + json);
        }
        assertTrue(json.contains("\"kty\":\"RSA\""));
        assertTrue(json.contains("\"kid\":\"ohia-server-1\""));
        assertTrue(json.contains("\"alg\":\"RS384\""));
        assertTrue(json.contains("\"use\":\"sig\""));
    }

    @Test
    void aSuppliedKeyIsUsedAsIs() throws Exception {
        RSAKey supplied = new RSAKeyGenerator(3072).keyID("organizer-key").generate();
        Path file = dir.resolve("supplied.json");
        Files.writeString(file, supplied.toJSONString(), StandardCharsets.UTF_8);
        RSAKey used = store(file).signingKey();
        assertEquals(supplied.getModulus(), used.getModulus());
        assertEquals("organizer-key", used.getKeyID());
    }

    /** Control: a broken mount must never be silently replaced by a brand-new identity. */
    @Test
    void refusesBrokenKeyFilesInsteadOfReplacingThem() throws Exception {
        Path garbage = dir.resolve("garbage.json");
        Files.writeString(garbage, "not a key", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> store(garbage).signingKey());
        assertEquals("not a key", Files.readString(garbage), "the file must be left alone");

        Path publicOnly = dir.resolve("public-only.json");
        Files.writeString(publicOnly, new RSAKeyGenerator(2048).keyID("k").generate().toPublicJWK().toJSONString(), StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> store(publicOnly).signingKey());

        Path small = dir.resolve("small.json");
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024); // Nimbus's own generator refuses this size, so build the weak key with plain Java
        java.security.KeyPair weak = kpg.generateKeyPair();
        RSAKey weakKey = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) weak.getPublic())
                .privateKey(weak.getPrivate()).keyID("k").build();
        assertEquals(1024, weakKey.size());
        Files.writeString(small, weakKey.toJSONString(), StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> store(small).signingKey());

        Path noKid = dir.resolve("nokid.json");
        Files.writeString(noKid, new RSAKeyGenerator(2048).generate().toJSONString(), StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> store(noKid).signingKey());
    }

    @Test
    void twoFreshStoresInDifferentFoldersGetDifferentKeys() {
        assertNotEquals(store(dir.resolve("a.json")).signingKey().getModulus(), store(dir.resolve("b.json")).signingKey().getModulus());
    }
}
