package org.aicancode.devblog.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aicancode.devblog.domain.ApiException;
import org.aicancode.devblog.domain.DuplicateException;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.domain.Models.RefreshToken;
import org.aicancode.devblog.domain.Models.TokenPair;
import org.aicancode.devblog.domain.Models.User;
import org.aicancode.devblog.domain.Times;
import org.aicancode.devblog.domain.Validation;
import org.aicancode.devblog.repo.UserRepository;
import org.aicancode.devblog.security.PasswordHasher;
import org.aicancode.devblog.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Accounts, login, token rotation and roles. Framework-free: the HTTP layer only parses and formats. */
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final Clock clock;

    public AuthService(UserRepository users, PasswordHasher hasher, TokenService tokens, Clock clock) {
        this.users = users;
        this.hasher = hasher;
        this.tokens = tokens;
        this.clock = clock;
    }

    public static void requireRole(Principal principal, String... roles) {
        if (!List.of(roles).contains(principal.role())) {
            throw ApiException.forbidden("requires role " + String.join(" or ", roles));
        }
    }

    public UserRepository users() {
        return users;
    }

    // ---- accounts ----
    public User register(Object payload) {
        Map<String, Object> data = Validation.requireObject(payload);
        String email = Validation.email(data.get("email"));
        String password = Validation.password(data.get("password"));
        String name = Validation.displayName(data.get("displayName"));
        // Never read "role" from the request: everyone starts as READER (mass-assignment defence).
        try {
            return users.insertUser(email, hasher.hash(password), name, "READER", Times.now(clock));
        } catch (DuplicateException e) {
            throw ApiException.emailTaken();
        }
    }

    public TokenPair login(Object payload) {
        Map<String, Object> data = Validation.requireObject(payload);
        if (!(data.get("email") instanceof String email) || !(data.get("password") instanceof String password)) {
            throw ApiException.validation("email and password are required");
        }
        Optional<User> user = users.findUserByEmail(email.strip().toLowerCase(java.util.Locale.ROOT));
        // verify() also runs for unknown e-mails (against a dummy hash): same answer, same timing.
        boolean ok = password.getBytes(StandardCharsets.UTF_8).length <= 72
                && hasher.verify(password, user.map(User::passwordHash).orElse(null));
        if (!ok || user.isEmpty()) {
            throw ApiException.invalidCredentials();
        }
        return issue(user.get());
    }

    public TokenPair refresh(Object payload) {
        String token = refreshTokenFrom(payload);
        RefreshToken stored = users.findRefreshToken(TokenService.hashRefreshToken(token))
                .orElseThrow(ApiException::invalidRefreshToken);
        Instant now = Times.now(clock);
        if (stored.revokedAt() != null || !users.revokeRefreshToken(stored.id(), now)) {
            // A used token came back: someone has a copy. Log out every session of this user.
            LOG.warn("refresh token reuse detected for user {}: revoking all sessions", stored.userId());
            users.revokeAllRefreshTokens(stored.userId(), now);
            throw ApiException.invalidRefreshToken();
        }
        if (!stored.expiresAt().isAfter(now)) {
            throw ApiException.invalidRefreshToken();
        }
        User user = users.findUser(stored.userId()).orElseThrow(ApiException::invalidRefreshToken);
        return issue(user); // the role is re-read from the database here
    }

    public void logout(Object payload) {
        users.findRefreshToken(TokenService.hashRefreshToken(refreshTokenFrom(payload)))
                .ifPresent(t -> users.revokeRefreshToken(t.id(), Times.now(clock)));
    }

    public User me(Principal principal) {
        return users.findUser(principal.userId()).orElseThrow(() -> ApiException.unauthorized("account no longer exists"));
    }

    public User changeRole(Principal principal, long userId, Object payload) {
        requireRole(principal, "ADMIN");
        String newRole = Validation.role(Validation.requireObject(payload).get("role"));
        if (userId == principal.userId()) {
            throw ApiException.validation("you cannot change your own role");
        }
        return users.updateRole(userId, newRole).orElseThrow(() -> ApiException.notFound("user"));
    }

    /** Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN). */
    public User ensureAdmin(String rawEmail, String password) {
        String email = Validation.email(rawEmail);
        Optional<User> existing = users.findUserByEmail(email);
        if (existing.isPresent()) {
            User u = existing.get();
            return "ADMIN".equals(u.role()) ? u : users.updateRole(u.id(), "ADMIN").orElseThrow();
        }
        return users.insertUser(email, hasher.hash(Validation.password(password)), "Admin", "ADMIN", Times.now(clock));
    }

    // ---- tokens ----
    public Principal authenticate(String authorization) {
        Principal principal = optional(authorization);
        if (principal == null) {
            throw ApiException.unauthorized();
        }
        return principal;
    }

    /** No header -> anonymous. A header that is present but wrong is always a 401, never anonymous. */
    public Principal optional(String authorization) {
        if (authorization == null) {
            return null;
        }
        int space = authorization.indexOf(' ');
        String scheme = space < 0 ? authorization : authorization.substring(0, space);
        String token = space < 0 ? "" : authorization.substring(space + 1).strip();
        if (!"bearer".equalsIgnoreCase(scheme) || token.isEmpty()) {
            throw ApiException.unauthorized("use Authorization: Bearer <token>");
        }
        return tokens.verifyAccessToken(token);
    }

    private TokenPair issue(User user) {
        String refresh = TokenService.newRefreshToken();
        Instant now = Times.now(clock);
        users.insertRefreshToken(user.id(), TokenService.hashRefreshToken(refresh), now,
                now.plus(TokenService.REFRESH_TOKEN_TTL));
        return new TokenPair(tokens.issueAccessToken(user), refresh, user);
    }

    private static String refreshTokenFrom(Object payload) {
        Object token = Validation.requireObject(payload).get("refreshToken");
        if (!(token instanceof String s) || s.isEmpty()) {
            throw ApiException.validation("refreshToken is required");
        }
        return s;
    }
}
