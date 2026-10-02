package org.aicancode.devblog.service;

import java.time.Clock;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.domain.Models.TokenPair;
import org.aicancode.devblog.domain.Models.User;
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
        // TODO(phase-3 M2): 403 forbidden unless principal.role is one of the allowed roles.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.requireRole");
    }

    public UserRepository users() {
        return users;
    }

    // ---- accounts ----
    public User register(Object payload) {
        // TODO(phase-3 M2): validate email, password, displayName; ALWAYS create a READER (ignore any 'role' in the
        // payload: mass assignment!); hash the password; DuplicateError -> 409 email-taken.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.register");
    }

    public TokenPair login(Object payload) {
        // TODO(phase-3 M2): same 401 invalid-credentials for unknown e-mail and wrong password; for an unknown
        // e-mail still run one bcrypt verify (timing). Return access + refresh token pair.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.login");
    }

    public TokenPair refresh(Object payload) {
        // TODO(phase-3 M2): hash the token, find it; unknown/expired -> 401 invalid-refresh-token. Revoke with
        // compare-and-set: if it was already revoked it is REUSE -> revoke all the user's tokens, 401. Re-read the
        // user's role, issue a new pair.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.refresh");
    }

    public void logout(Object payload) {
        // TODO(phase-3 M2): require a refreshToken string (400 otherwise); revoke it if it exists. Idempotent:
        // always succeeds.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.logout");
    }

    public User me(Principal principal) {
        // TODO(phase-3 M2): the current user, 404 if it no longer exists.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.me");
    }

    public User changeRole(Principal principal, long userId, Object payload) {
        // TODO(phase-3 M2): ADMIN only (403); validate the role; changing your own role -> 400; unknown user ->
        // 404.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.changeRole");
    }

    /** Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN). */
    public User ensureAdmin(String rawEmail, String password) {
        // TODO(phase-3 M2): create ADMIN_EMAIL as ADMIN, or promote it if it exists. Until you build it the app
        // starts without an admin.
        LOG.warn("TODO(phase-3 M2): ensureAdmin - starting without an admin");
        return null;
    }

    // ---- tokens ----
    public Principal authenticate(String authorization) {
        // TODO(phase-3 M2): header must be 'Bearer <jwt>'; missing or bad -> 401 unauthorized (the HTTP layer adds
        // WWW-Authenticate).
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.authenticate");
    }

    /** No header -> anonymous. A header that is present but wrong is always a 401, never anonymous. */
    public Principal optional(String authorization) {
        // TODO(phase-3 M2): no header -> null (anonymous). A header that is present but bad is still a 401: never
        // silently anonymous.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.optional");
    }

    private TokenPair issue(User user) {
        // TODO(phase-3 M2): HS256 JWT with sub (user id as string), role, iss 'devblog', iat, exp = now + 900 s,
        // using the injected clock.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.issue");
    }

    private static String refreshTokenFrom(Object payload) {
        // TODO(phase-3 M2): the payload must be an object with a non-empty refreshToken string, else 400
        //   'refreshToken is required'.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.refreshTokenFrom");
    }
}
