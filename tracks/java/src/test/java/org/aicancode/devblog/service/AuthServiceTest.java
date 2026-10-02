package org.aicancode.devblog.service;

import static org.aicancode.devblog.support.Problems.codeOf;
import static org.aicancode.devblog.support.Problems.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.domain.Models.TokenPair;
import org.aicancode.devblog.domain.Models.User;
import org.aicancode.devblog.support.InMemoryStore;
import org.aicancode.devblog.support.MutableClock;
import org.aicancode.devblog.support.TestApp;
import org.junit.jupiter.api.Test;

/** M2: registration, login, refresh rotation and roles, at the service level (FR-1, FR-2, FR-3). */
class AuthServiceTest {

    private static final Map<String, Object> REG =
            Map.of("email", "Asha@DevBlog.test", "password", "password-123", "displayName", "Asha");
    private final InMemoryStore store = new InMemoryStore();
    private final MutableClock clock = new MutableClock();
    private final AuthService auth = TestApp.auth(store, clock);

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> m = new HashMap<>(REG);
        m.put(key, value);
        return m;
    }

    private static Map<String, Object> refresh(TokenPair pair) {
        return Map.of("refreshToken", pair.refreshToken());
    }

    @Test
    void registerStoresABcryptHashAndALowerCaseEmail() {
        User user = auth.register(REG);
        assertThat(List.of(user.email(), user.role(), user.displayName())).containsExactly("asha@devblog.test", "READER", "Asha");
        assertThat(store.users.get(user.id()).passwordHash()).startsWith("$2").doesNotContain("password-123");
    }

    @Test
    void registerIgnoresARoleInTheBody() {
        // Mass assignment: copying request fields into the model would let anyone become ADMIN.
        assertThat(auth.register(with("role", "ADMIN")).role()).isEqualTo("READER");
    }

    @Test
    void duplicateEmailIs409CaseInsensitively() {
        auth.register(REG);
        assertThat(statusOf(() -> auth.register(with("email", "asha@devblog.TEST")))).containsExactly(409, "email-taken");
    }

    @Test
    void registerValidates() {
        assertThat(codeOf(() -> auth.register(with("password", "short")))).isEqualTo(400);
        assertThat(codeOf(() -> auth.register(List.of("not", "an", "object")))).isEqualTo(400);
    }

    @Test
    void loginReturnsTokens() {
        auth.register(REG);
        TokenPair pair = auth.login(Map.of("email", " ASHA@devblog.test", "password", "password-123"));
        assertThat(pair.user().email()).isEqualTo("asha@devblog.test");
        assertThat(auth.authenticate("Bearer " + pair.accessToken()).userId()).isEqualTo(pair.user().id());
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() {
        auth.register(REG);
        List<Object> wrong = statusOf(() -> auth.login(Map.of("email", "Asha@DevBlog.test", "password", "password-124")));
        List<Object> unknown = statusOf(() -> auth.login(Map.of("email", "nobody@devblog.test", "password", "password-123")));
        assertThat(wrong).isEqualTo(unknown).containsExactly(401, "invalid-credentials");
        assertThat(codeOf(() -> auth.login(Map.of("email", "Asha@DevBlog.test")))).isEqualTo(400);
    }

    @Test
    void refreshRotatesTheToken() {
        auth.register(REG);
        TokenPair first = auth.login(REG);
        TokenPair second = auth.refresh(refresh(first));
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(auth.authenticate("Bearer " + second.accessToken()).userId()).isEqualTo(first.user().id());
    }

    @Test
    void reusingARefreshTokenRevokesEverySession() {
        auth.register(REG);
        TokenPair stolen = auth.login(REG);
        TokenPair otherDevice = auth.login(REG);
        auth.refresh(refresh(stolen)); // the legitimate rotation
        assertThat(statusOf(() -> auth.refresh(refresh(stolen)))).containsExactly(401, "invalid-refresh-token");
        // ...and the other device's still-unused token is now revoked too
        assertThat(codeOf(() -> auth.refresh(refresh(otherDevice)))).isEqualTo(401);
    }

    @Test
    void refreshTokenExpiresAfter7Days() {
        auth.register(REG);
        TokenPair pair = auth.login(REG);
        clock.advance(Duration.ofDays(7));
        assertThat(statusOf(() -> auth.refresh(refresh(pair)))).containsExactly(401, "invalid-refresh-token");
    }

    @Test
    void refreshRejectsUnknownAndMissingTokens() {
        assertThat(statusOf(() -> auth.refresh(Map.of("refreshToken", "made-up")))).containsExactly(401, "invalid-refresh-token");
        assertThat(codeOf(() -> auth.refresh(Map.of()))).isEqualTo(400);
    }

    @Test
    void refreshPicksUpANewRole() {
        User user = auth.register(REG);
        TokenPair pair = auth.login(REG);
        store.setRole(user.id(), "AUTHOR");
        assertThat(auth.authenticate("Bearer " + pair.accessToken()).role()).isEqualTo("READER"); // stateless: old claim
        TokenPair renewed = auth.refresh(refresh(pair));
        assertThat(auth.authenticate("Bearer " + renewed.accessToken()).role()).isEqualTo("AUTHOR");
    }

    @Test
    void logoutRevokesAndIsIdempotent() {
        auth.register(REG);
        TokenPair pair = auth.login(REG);
        auth.logout(refresh(pair));
        auth.logout(refresh(pair));
        auth.logout(Map.of("refreshToken", "never-existed"));
        assertThat(codeOf(() -> auth.refresh(refresh(pair)))).isEqualTo(401);
    }

    @Test
    void authorizationHeaderRules() {
        assertThat(auth.optional(null)).isNull();
        for (String bad : List.of("", "Bearer", "Bearer ", "Basic abc", "Token xyz", "Bearer not-a-jwt")) {
            assertThat(statusOf(() -> auth.optional(bad))).containsExactly(401, "unauthorized");
        }
        assertThat(statusOf(() -> auth.authenticate(null))).containsExactly(401, "unauthorized");
    }

    @Test
    void onlyAdminsChangeRoles() {
        User target = auth.register(REG);
        Principal author = new Principal(99, "AUTHOR");
        Principal admin = new Principal(100, "ADMIN");
        assertThat(statusOf(() -> auth.changeRole(author, target.id(), Map.of("role", "ADMIN"))))
                .containsExactly(403, "forbidden");
        assertThat(auth.changeRole(admin, target.id(), Map.of("role", "AUTHOR")).role()).isEqualTo("AUTHOR");
        assertThat(statusOf(() -> auth.changeRole(admin, 12345, Map.of("role", "AUTHOR")))).containsExactly(404, "not-found");
        assertThat(codeOf(() -> auth.changeRole(admin, target.id(), Map.of("role", "GOD")))).isEqualTo(400);
        assertThat(codeOf(() -> auth.changeRole(admin, admin.userId(), Map.of("role", "READER")))).isEqualTo(400);
    }

    @Test
    void meReturnsTheCurrentUser() {
        User user = auth.register(REG);
        assertThat(auth.me(new Principal(user.id(), "READER")).email()).isEqualTo("asha@devblog.test");
        assertThat(codeOf(() -> auth.me(new Principal(999, "READER")))).isEqualTo(401);
    }

    @Test
    void ensureAdminCreatesOrPromotes() {
        User created = auth.ensureAdmin("Root@DevBlog.test", "admin-password");
        assertThat(List.of(created.role(), created.email())).containsExactly("ADMIN", "root@devblog.test");
        assertThat(auth.ensureAdmin("root@devblog.test", "ignored-password").id()).isEqualTo(created.id());
        User reader = auth.register(REG);
        assertThat(auth.ensureAdmin("Asha@DevBlog.test", "whatever-123").id()).isEqualTo(reader.id());
        assertThat(auth.me(new Principal(reader.id(), "READER")).role()).isEqualTo("ADMIN");
    }
}
