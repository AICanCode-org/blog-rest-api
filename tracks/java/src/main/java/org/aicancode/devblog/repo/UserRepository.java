package org.aicancode.devblog.repo;

import java.time.Instant;
import java.util.Optional;
import org.aicancode.devblog.domain.Models.RefreshToken;
import org.aicancode.devblog.domain.Models.User;

/** Persistence port for users and refresh tokens. */
public interface UserRepository {

    User insertUser(String email, String passwordHash, String displayName, String role, Instant at);

    Optional<User> findUserByEmail(String email);

    Optional<User> findUser(long id);

    Optional<User> updateRole(long id, String role);

    void insertRefreshToken(long userId, String tokenHash, Instant at, Instant expiresAt);

    Optional<RefreshToken> findRefreshToken(String tokenHash);

    /** Compare-and-set: true only for the caller that actually revoked it. */
    boolean revokeRefreshToken(long id, Instant at);

    void revokeAllRefreshTokens(long userId, Instant at);

    boolean ping();
}
