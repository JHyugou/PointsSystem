package com.pointshome.api.auth;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionStore {
    private final JdbcTemplate jdbc;

    public SessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String tokenHash, String userId, long now, long expiresAt) {
        jdbc.update("DELETE FROM login_session WHERE expires_at <= ?", now);
        jdbc.update("""
                INSERT INTO login_session (token_hash, user_id, expires_at, created_at)
                VALUES (?, ?, ?, ?)
                """, tokenHash, userId, expiresAt, now);
    }

    public Optional<CurrentUser> lookup(String tokenHash, long now) {
        return jdbc.query("""
                SELECT u.id, u.role FROM app_user u
                JOIN login_session s ON s.user_id = u.id
                WHERE s.token_hash = ? AND s.expires_at > ?
                """, (rs, row) -> new CurrentUser(rs.getString("id"),
                Account.Role.valueOf(rs.getString("role"))), tokenHash, now)
                .stream().findFirst();
    }

    public void delete(String tokenHash) {
        jdbc.update("DELETE FROM login_session WHERE token_hash = ?", tokenHash);
    }

    public void deleteForUser(String userId) {
        jdbc.update("DELETE FROM login_session WHERE user_id = ?", userId);
    }
}
