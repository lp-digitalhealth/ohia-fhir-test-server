package org.ohia.facade.keys;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Component;

/**
 * Builds the SMART Backend Services client assertion (private_key_jwt) that proves who we are to
 * a sender's ARex token endpoint: a JWT signed RS384 with our key, issuer = subject = our client
 * id, audience = the token endpoint exactly as the sender advertises it, a two-minute lifetime
 * (the limit is five), and a fresh one-time {@code jti} every call.
 */
@Component
public class ClientAssertionSigner {

    private static final long LIFETIME_SECONDS = 120;

    private final ServerKeyStore keys;
    private final Clock clock;
    private final String clientId;

    public ClientAssertionSigner(ServerKeyStore keys, Clock clock, FacadeProperties props) {
        this.keys = keys;
        this.clock = clock;
        this.clientId = props.client().id();
    }

    public String clientId() {
        return clientId;
    }

    public String sign(String audience) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(clientId).subject(clientId).audience(audience)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(LIFETIME_SECONDS)))
                .jwtID(UUID.randomUUID().toString()).build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS384).keyID(keys.keyId()).build(), claims);
            jwt.sign(new RSASSASigner(keys.signingKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the client assertion: " + e.getMessage(), e);
        }
    }
}
