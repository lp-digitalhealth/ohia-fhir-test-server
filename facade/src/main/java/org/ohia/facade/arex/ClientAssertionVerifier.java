package org.ohia.facade.arex;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.ohia.facade.clients.ClientEntry;
import org.ohia.facade.clients.ClientRegistry;
import org.springframework.stereotype.Component;

/**
 * Verifies a SMART Backend Services client assertion (private_key_jwt):
 * a JWT signed with the tester's private key, which proves who is asking.
 *
 * <p>Rules: only RS384 and ES384 are accepted (so never "none" or a shared-secret algorithm);
 * issuer and subject must both be a registered client id; the audience must be exactly this
 * server's token URL; the expiry must be in the future but no more than five minutes ahead;
 * and the {@code jti} may be used only once. Every failure answers the same
 * {@code invalid_client}, so a caller learns nothing about which check failed.
 */
@Component
public class ClientAssertionVerifier {

    static final Set<JWSAlgorithm> ALLOWED = Set.of(JWSAlgorithm.RS384, JWSAlgorithm.ES384);
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(5);
    private static final Duration SKEW = Duration.ofSeconds(30);

    private final ClientRegistry registry;
    private final Clock clock;
    private final Map<String, Instant> seenJti = new ConcurrentHashMap<>();

    public ClientAssertionVerifier(ClientRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /** Returns the authenticated client, or throws an invalid_client OAuthException. */
    public ClientEntry verify(String assertion, String expectedAudience) {
        try {
            SignedJWT jwt = SignedJWT.parse(assertion);
            if (!ALLOWED.contains(jwt.getHeader().getAlgorithm())) {
                throw fail();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            String iss = claims.getIssuer();
            String sub = claims.getSubject();
            if (iss == null || !iss.equals(sub)) {
                throw fail();
            }
            ClientEntry client = registry.find(iss).orElseThrow(ClientAssertionVerifier::fail);
            if (!signatureValid(jwt, client)) {
                throw fail();
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(expectedAudience)) {
                throw fail();
            }
            Instant now = clock.instant();
            if (claims.getExpirationTime() == null) {
                throw fail();
            }
            Instant exp = claims.getExpirationTime().toInstant();
            if (!exp.isAfter(now.minus(SKEW)) || exp.isAfter(now.plus(MAX_LIFETIME).plus(SKEW))) {
                throw fail();
            }
            if (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now.plus(SKEW))) {
                throw fail();
            }
            String jti = claims.getJWTID();
            if (jti == null || jti.isBlank() || !rememberOnce(client.clientId() + "|" + jti, exp.plus(SKEW), now)) {
                throw fail();
            }
            return client;
        } catch (ParseException | JOSEException | ClassCastException e) {
            throw fail();
        }
    }

    private boolean signatureValid(SignedJWT jwt, ClientEntry client) throws JOSEException, ParseException {
        List<JWK> keys = JWKSet.parse(client.jwks().toString()).getKeys();
        String kid = jwt.getHeader().getKeyID();
        for (JWK key : keys) {
            if (kid != null && !kid.equals(key.getKeyID())) {
                continue;
            }
            JWSVerifier verifier;
            if (key instanceof RSAKey rsa && jwt.getHeader().getAlgorithm().equals(JWSAlgorithm.RS384)) {
                if (rsa.size() < 2048) {
                    continue;
                }
                verifier = new RSASSAVerifier(rsa.toRSAPublicKey());
            } else if (key instanceof ECKey ec && jwt.getHeader().getAlgorithm().equals(JWSAlgorithm.ES384)) {
                verifier = new ECDSAVerifier(ec.toECPublicKey());
            } else {
                continue;
            }
            if (jwt.verify(verifier)) {
                return true;
            }
        }
        return false;
    }

    /** True the first time a jti is seen; false on a replay. Expired entries are dropped as we go. */
    private boolean rememberOnce(String key, Instant keepUntil, Instant now) {
        seenJti.values().removeIf(t -> t.isBefore(now));
        return seenJti.putIfAbsent(key, keepUntil) == null;
    }

    private static OAuthException fail() {
        return new OAuthException(401, "invalid_client", "Client authentication failed.");
    }
}
