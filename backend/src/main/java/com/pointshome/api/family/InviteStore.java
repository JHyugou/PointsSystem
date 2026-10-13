package com.pointshome.api.family;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InviteStore {
    public record Invite(String hash, String childId, long expiresAt, Long usedAt) {}

    private final JdbcTemplate jdbc;

    public InviteStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String hash, String childId, String createdBy, long expiresAt) {
        jdbc.update("DELETE FROM child_invite WHERE expires_at <= ?", System.currentTimeMillis());
        jdbc.update("""
                INSERT INTO child_invite (code_hash, child_id, created_by, expires_at, used_at)
                VALUES (?, ?, ?, ?, NULL)
                """, hash, childId, createdBy, expiresAt);
    }

    public Optional<Invite> byHashForUpdate(String hash) {
        return jdbc.query("SELECT * FROM child_invite WHERE code_hash = ? FOR UPDATE",
                (rs, row) -> new Invite(rs.getString("code_hash"), rs.getString("child_id"),
                        rs.getLong("expires_at"), (Long) rs.getObject("used_at")), hash)
                .stream().findFirst();
    }

    public void markUsed(String hash, long now) {
        jdbc.update("UPDATE child_invite SET used_at = ? WHERE code_hash = ?", now, hash);
    }
}
