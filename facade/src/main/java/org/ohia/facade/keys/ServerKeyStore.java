package org.ohia.facade.keys;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.text.ParseException;
import java.util.Set;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Component;

/**
 * This server's own key pair, used to sign the client assertion when it pulls from a sender's
 * ARex server. The private key never leaves this class: only {@link #publicJwks()} is published.
 *
 * <p>The key is created on first use (RSA 2048, RS384) and written to the key file through a
 * temporary file and an atomic move, readable by the owner only where the file system allows.
 * An organizer can supply a key instead by mounting a file at the same path. A key that is
 * unreadable, has no private part, or is smaller than 2048 bits is refused rather than replaced,
 * so a broken mount is never silently swapped for a new identity.
 */
@Component
public class ServerKeyStore {

    static final String DEFAULT_KEY_ID = "ohia-server-1";
    private static final int MIN_BITS = 2048;

    private final Path file;
    private RSAKey key;

    public ServerKeyStore(FacadeProperties props) {
        this.file = Path.of(props.client().keyFile());
    }

    /** The private signing key (loaded or created on the first call). */
    public synchronized RSAKey signingKey() {
        if (key == null) {
            key = Files.isRegularFile(file) ? load() : create();
        }
        return key;
    }

    public String keyId() {
        return signingKey().getKeyID();
    }

    /** Public half only. Nothing private can be reached through this set. */
    public JWKSet publicJwks() {
        return new JWKSet(signingKey().toPublicJWK());
    }

    private RSAKey load() {
        try {
            JWK parsed = JWK.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof RSAKey rsa) || !rsa.isPrivate()) {
                throw new IllegalStateException("The key file " + file + " must hold an RSA key with its private part.");
            }
            if (rsa.size() < MIN_BITS) {
                throw new IllegalStateException("The key in " + file + " is smaller than " + MIN_BITS + " bits.");
            }
            if (rsa.getKeyID() == null) {
                throw new IllegalStateException("The key in " + file + " needs a key id (kid).");
            }
            return rsa;
        } catch (ParseException | IOException e) {
            throw new IllegalStateException("The key file " + file + " cannot be read: " + e.getMessage(), e);
        }
    }

    private RSAKey create() {
        try {
            RSAKey generated = new RSAKeyGenerator(MIN_BITS).keyID(DEFAULT_KEY_ID).keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS384).generate();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, generated.toJSONString(), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException e) {
                // not a POSIX file system: nothing more we can do here
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return generated;
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not create the server key: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
