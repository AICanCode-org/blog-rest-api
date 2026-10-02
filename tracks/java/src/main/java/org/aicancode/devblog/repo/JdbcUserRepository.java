package org.aicancode.devblog.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.aicancode.devblog.domain.DuplicateException;
import org.aicancode.devblog.domain.Models.RefreshToken;
import org.aicancode.devblog.domain.Models.User;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL adapter for users and refresh tokens (plain SQL with JdbcClient, no ORM). */
public class JdbcUserRepository implements UserRepository {

    private static final String COLS = "id, email, password_hash, display_name, role, created_at";
    private final JdbcClient jdbc;

    public JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static User user(ResultSet rs, int row) throws SQLException {
        return new User(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getString("role"), instant(rs, "created_at"));
    }

    @Override
    public User insertUser(String email, String passwordHash, String displayName, String role, Instant at) {
        try {
            return jdbc.sql("INSERT INTO users (email, password_hash, display_name, role, created_at) "
                            + "VALUES (?, ?, ?, ?, ?) RETURNING " + COLS)
                    .params(email, passwordHash, displayName, role, Timestamp.from(at))
                    .query(JdbcUserRepository::user).single();
        } catch (DuplicateKeyException e) {
            throw new DuplicateException("email");
        }
    }

    @Override
    public Optional<User> findUserByEmail(String email) {
        return jdbc.sql("SELECT " + COLS + " FROM users WHERE email = ?").param(email)
                .query(JdbcUserRepository::user).optional();
    }

    @Override
    public Optional<User> findUser(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM users WHERE id = ?").param(id).query(JdbcUserRepository::user).optional();
    }

    @Override
    public Optional<User> updateRole(long id, String role) {
        return jdbc.sql("UPDATE users SET role = ? WHERE id = ? RETURNING " + COLS).params(role, id)
                .query(JdbcUserRepository::user).optional();
    }

    @Override
    public void insertRefreshToken(long userId, String tokenHash, Instant at, Instant expiresAt) {
        jdbc.sql("INSERT INTO refresh_tokens (user_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?)")
                .params(userId, tokenHash, Timestamp.from(at), Timestamp.from(expiresAt)).update();
    }

    @Override
    public Optional<RefreshToken> findRefreshToken(String tokenHash) {
        return jdbc.sql("SELECT id, user_id, token_hash, created_at, expires_at, revoked_at FROM refresh_tokens "
                        + "WHERE token_hash = ?").param(tokenHash)
                .query((rs, row) -> new RefreshToken(rs.getLong("id"), rs.getLong("user_id"), rs.getString("token_hash"),
                        instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "revoked_at")))
                .optional();
    }

    @Override
    public boolean revokeRefreshToken(long id, Instant at) {
        // "AND revoked_at IS NULL" makes this a compare-and-set: of two concurrent refreshes with the
        // same token, exactly one updates a row. The other is treated as reuse.
        return jdbc.sql("UPDATE refresh_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")
                .params(Timestamp.from(at), id).update() == 1;
    }

    @Override
    public void revokeAllRefreshTokens(long userId, Instant at) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")
                .params(Timestamp.from(at), userId).update();
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
