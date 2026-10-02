package org.aicancode.devblog.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.aicancode.devblog.domain.Models.RefreshToken;
import org.aicancode.devblog.domain.Models.User;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL adapter for users and refresh tokens (plain SQL with JdbcClient, no ORM). */
public class JdbcUserRepository implements UserRepository {

    private static final String COLS = "id, email, password_hash, display_name, role, created_at";
    private final JdbcClient jdbc;

    public JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        // TODO(phase-3 M2): helper: convert a nullable java.sql.Timestamp column to an Instant (null stays
        //   null).
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.instant");
    }

    private static User user(ResultSet rs, int row) throws SQLException {
        return new User(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getString("role"), instant(rs, "created_at"));
    }

    @Override
    public User insertUser(String email, String passwordHash, String displayName, String role, Instant at) {
        // TODO(phase-3 M2): INSERT ... RETURNING; unique violation on email -> DuplicateError.
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.insertUser");
    }

    @Override
    public Optional<User> findUserByEmail(String email) {
        // TODO(phase-3 M2): SELECT by email (already lower-cased).
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.findUserByEmail");
    }

    @Override
    public Optional<User> findUser(long id) {
        // TODO(phase-3 M2): SELECT by id.
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.findUser");
    }

    @Override
    public Optional<User> updateRole(long id, String role) {
        // TODO(phase-3 M2): UPDATE ... RETURNING; null when the user does not exist.
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.updateRole");
    }

    @Override
    public void insertRefreshToken(long userId, String tokenHash, Instant at, Instant expiresAt) {
        // TODO(phase-3 M2): store user id, token HASH, created_at, expires_at.
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.insertRefreshToken");
    }

    @Override
    public Optional<RefreshToken> findRefreshToken(String tokenHash) {
        // TODO(phase-3 M2): SELECT by token hash.
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.findRefreshToken");
    }

    @Override
    public boolean revokeRefreshToken(long id, Instant at) {
        // TODO(phase-3 M2): compare-and-set: UPDATE ... SET revoked_at WHERE id = ? AND revoked_at IS NULL; return
        // true only if a row changed (this is what makes concurrent refreshes safe).
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.revokeRefreshToken");
    }

    @Override
    public void revokeAllRefreshTokens(long userId, Instant at) {
        // TODO(phase-3 M2): revoke every not-yet-revoked token of the user.
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.revokeAllRefreshTokens");
    }

    @Override
    public boolean ping() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return true;
        } catch (RuntimeException e) { // health checks must never throw
            return false;
        }
    }
}
