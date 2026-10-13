package com.pointshome.api.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AccountStore {
    private static final RowMapper<Account> ACCOUNT_MAPPER = (rs, row) -> new Account(
            rs.getString("id"), Account.Role.valueOf(rs.getString("role")),
            rs.getString("email"), rs.getString("username"),
            rs.getString("display_name"), rs.getString("password_hash"));

    private final JdbcTemplate jdbc;

    public AccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> byEmail(String email) {
        return jdbc.query("SELECT * FROM app_user WHERE email = ?", ACCOUNT_MAPPER, email)
                .stream().findFirst();
    }

    public Optional<Account> byUsername(String username) {
        return jdbc.query("SELECT * FROM app_user WHERE username = ?", ACCOUNT_MAPPER, username)
                .stream().findFirst();
    }

    public Optional<Account> byId(String id) {
        return jdbc.query("SELECT * FROM app_user WHERE id = ?", ACCOUNT_MAPPER, id)
                .stream().findFirst();
    }

    public void insert(Account account, long now) {
        jdbc.update("""
                INSERT INTO app_user (id, role, email, username, display_name, password_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, account.id(), account.role().name(), account.email(), account.username(),
                account.displayName(), account.passwordHash(), now);
    }

    public void updatePassword(String userId, String passwordHash) {
        jdbc.update("UPDATE app_user SET password_hash = ? WHERE id = ?", passwordHash, userId);
    }

    public List<Account> childrenOf(String guardianId) {
        return jdbc.query("""
                SELECT u.* FROM app_user u JOIN guardian_child link ON link.child_id = u.id
                WHERE link.guardian_id = ? ORDER BY link.linked_at, u.id
                """, ACCOUNT_MAPPER, guardianId);
    }

    public List<Account> guardiansOf(String childId) {
        return jdbc.query("""
                SELECT u.* FROM app_user u JOIN guardian_child link ON link.guardian_id = u.id
                WHERE link.child_id = ? ORDER BY link.linked_at, u.id
                """, ACCOUNT_MAPPER, childId);
    }

    public boolean isLinked(String guardianId, String childId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM guardian_child WHERE guardian_id = ? AND child_id = ?
                """, Integer.class, guardianId, childId);
        return count != null && count > 0;
    }

    public void link(String guardianId, String childId, long now) {
        jdbc.update("INSERT INTO guardian_child (guardian_id, child_id, linked_at) VALUES (?, ?, ?)",
                guardianId, childId, now);
    }
}
