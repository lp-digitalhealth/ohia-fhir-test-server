package org.ohia.facade.arex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Issues and checks short-lived, opaque access tokens, each tied to one client and one manifest.
 * Tokens live in memory only and are stored as hashes. A restart simply invalidates them, which is
 * acceptable for five-minute tokens.
 */
@Component
public class AccessTokenStore {

    static final long LIFETIME_SECONDS = 300;

    public record TokenInfo(String clientId, String manifestId, Instant expiresAt) {
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();

    public AccessTokenStore(Clock clock) {
        this.clock = clock;
    }

    public String issue(String clientId, String manifestId) {
        Instant now = clock.instant();
        tokens.values().removeIf(t -> !t.expiresAt().isAfter(now));
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.put(hash(token), new TokenInfo(clientId, manifestId, now.plusSeconds(LIFETIME_SECONDS)));
        return token;
    }

    /** The token's details if it exists and has not expired. */
    public Optional<TokenInfo> check(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        TokenInfo info = tokens.get(hash(token));
        if (info == null || !info.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(info);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
