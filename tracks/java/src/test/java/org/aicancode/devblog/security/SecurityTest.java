package org.aicancode.devblog.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.aicancode.devblog.domain.ApiException;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.domain.Models.User;
import org.aicancode.devblog.support.MutableClock;
import org.aicancode.devblog.support.TestApp;
import org.junit.jupiter.api.Test;

/** M2: passwords and tokens (FR-1, FR-2, NFR-3). */
class SecurityTest {

    private static final User USER = new User(7, "asha@devblog.test", "x", "Asha", "AUTHOR", Instant.EPOCH);
    private final MutableClock clock = new MutableClock();
    private final TokenService tokens = new TokenService(TestApp.TEST_SECRET, clock);

    private static void rejected(String token, TokenService tokens) {
        assertThatThrownBy(() -> tokens.verifyAccessToken(token)).isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(401);
                    assertThat(((ApiException) e).slug()).isEqualTo("unauthorized");
                });
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(JWTClaimsSet claims, String secret) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static JWTClaimsSet.Builder claims(String role, String iss) {
        return new JWTClaimsSet.Builder().subject("1").claim("role", role).issuer(iss);
    }

    @Test
    void passwordsAreHashedWithBcryptAndVerified() {
        PasswordHasher hasher = new PasswordHasher(4);
        String hashed = hasher.hash("correct horse");
        assertThat(hashed).startsWith("$2").doesNotContain("correct horse");
        assertThat(hasher.verify("correct horse", hashed)).isTrue();
        assertThat(hasher.verify("wrong horse", hashed)).isFalse();
        assertThat(hasher.hash("correct horse")).isNotEqualTo(hashed); // a new salt every time
    }

    @Test
    void unknownUserStillRunsBcryptAndFails() {
        assertThat(new PasswordHasher(4).verify("anything", null)).isFalse();
    }

    @Test
    void bcryptCostIsBounded() {
        assertThatThrownBy(() -> new PasswordHasher(3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accessTokenClaims() throws Exception {
        JWTClaimsSet c = SignedJWT.parse(tokens.issueAccessToken(USER)).getJWTClaimsSet();
        assertThat(List.of(c.getSubject(), c.getStringClaim("role"), c.getIssuer())).containsExactly("7", "AUTHOR", "devblog");
        assertThat(Duration.between(c.getIssueTime().toInstant(), c.getExpirationTime().toInstant()).toSeconds())
                .isEqualTo(900);
        assertThat(c.getIssueTime().toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void verifyReturnsThePrincipal() {
        assertThat(tokens.verifyAccessToken(tokens.issueAccessToken(USER))).isEqualTo(new Principal(7, "AUTHOR"));
    }

    @Test
    void expiredTokenIsRejected() {
        String token = tokens.issueAccessToken(USER);
        clock.advance(Duration.ofMinutes(15));
        rejected(token, tokens);
    }

    @Test
    void forgedOrBrokenTokensAreRejected() throws Exception {
        Date far = Date.from(Instant.ofEpochSecond(9_999_999_999L));
        List<String> forged = List.of(
                // "alg": "none" - the classic: an unsigned token that a careless verifier accepts
                b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "."
                        + b64("{\"sub\":\"1\",\"role\":\"ADMIN\",\"iss\":\"devblog\",\"exp\":9999999999}") + ".",
                sign(claims("ADMIN", "devblog").expirationTime(far).build(), "x".repeat(40)), // another secret
                sign(claims("ADMIN", "evil").expirationTime(far).build(), TestApp.TEST_SECRET), // wrong issuer
                sign(claims("ROOT", "devblog").expirationTime(far).build(), TestApp.TEST_SECRET), // unknown role
                sign(claims("ADMIN", "devblog").build(), TestApp.TEST_SECRET), // no expiry
                "not.a.jwt");
        for (String token : forged) {
            rejected(token, tokens);
        }
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = tokens.issueAccessToken(USER).split("\\.");
        String evil = b64("{\"sub\":\"7\",\"role\":\"ADMIN\",\"iss\":\"devblog\",\"exp\":9999999999}");
        rejected(parts[0] + "." + evil + "." + parts[2], tokens);
    }

    @Test
    void refreshTokensAreRandomAndOnlyTheHashIsKept() {
        String token = TokenService.newRefreshToken();
        assertThat(token).isNotEqualTo(TokenService.newRefreshToken()).hasSizeGreaterThanOrEqualTo(43);
        String digest = TokenService.hashRefreshToken(token);
        assertThat(digest).hasSize(64).doesNotContain(token);
    }

    @Test
    void jwtSecretRules() {
        assertThat(TokenService.resolveSecret("")).hasSizeGreaterThanOrEqualTo(32); // random, dev only
        assertThat(TokenService.resolveSecret("s".repeat(32))).isEqualTo("s".repeat(32));
        assertThatThrownBy(() -> TokenService.resolveSecret("too-short")).isInstanceOf(IllegalArgumentException.class);
    }
}
