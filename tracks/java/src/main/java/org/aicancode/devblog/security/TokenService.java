package org.aicancode.devblog.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.domain.Models.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Access tokens (JWT, HS256) and refresh tokens (opaque, only the SHA-256 hash is stored). */
public class TokenService {

    public static final String ISSUER = "devblog";
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);
    public static final int MIN_SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);

    private final byte[] secret;
    private final Clock clock;

    public TokenService(String secret, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** JWT_SECRET: unset means a random per-process secret (dev only); shorter than 32 bytes is an error. */
    public static String resolveSecret(String configured) {
        if (configured == null || configured.isEmpty()) {
            LOG.warn("JWT_SECRET is not set: using a random secret, tokens die with this process (dev only)");
            return randomToken(48);
        }
        if (configured.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        return configured;
    }

    public String issueAccessToken(User user) {
        // TODO(phase-3 M2): HS256 JWT with sub (user id as string), role, iss 'devblog', iat, exp = now + 900 s,
        // using the injected clock.
        throw new UnsupportedOperationException("TODO(phase-3 M2): TokenService.issueAccessToken");
    }

    public Principal verifyAccessToken(String token) {
        // TODO(phase-3 M2): pin the algorithm to HS256, check signature, iss and exp against the injected clock;
        // any problem -> 401 unauthorized. Return Principal(user id, role).
        throw new UnsupportedOperationException("TODO(phase-3 M2): TokenService.verifyAccessToken");
    }

    public static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** Returns a new opaque refresh token for the client (32 random bytes, URL-safe). */
    public static String newRefreshToken() {
        // TODO(phase-3 M2): 32 random bytes from a CSPRNG, base64url without padding. Opaque: not a JWT.
        throw new UnsupportedOperationException("TODO(phase-3 M2): TokenService.newRefreshToken");
    }

    public static String hashRefreshToken(String token) {
        // TODO(phase-3 M2): SHA-256 hex digest. Only the hash is stored (like a password, but fast is fine: it has
        // 256 bits of entropy).
        throw new UnsupportedOperationException("TODO(phase-3 M2): TokenService.hashRefreshToken");
    }
}
