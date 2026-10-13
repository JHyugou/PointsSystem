package com.pointshome.api.auth;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CodeStore {
    public record EmailCode(String email, String hash, long expiresAt, long nextSendAt, int attempts) {}
    public record IpRate(String hash, long windowStartedAt, int sentCount) {}

    private final JdbcTemplate jdbc;

    public CodeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String table(VerificationMailSender.Purpose purpose) {
        return purpose == VerificationMailSender.Purpose.REGISTRATION
                ? "email_code" : "password_reset_code";
    }

    public Optional<EmailCode> byEmailForUpdate(String email, VerificationMailSender.Purpose purpose) {
        return jdbc.query("SELECT * FROM " + table(purpose) + " WHERE email = ? FOR UPDATE",
                (rs, row) -> new EmailCode(rs.getString("email"), rs.getString("code_hash"),
                        rs.getLong("expires_at"), rs.getLong("next_send_at"), rs.getInt("attempts")),
                email).stream().findFirst();
    }

    public void saveCode(String email, String hash, long expiresAt, long nextSendAt,
                         boolean exists, VerificationMailSender.Purpose purpose) {
        String table = table(purpose);
        if (exists) {
            jdbc.update("UPDATE " + table + " SET code_hash = ?, expires_at = ?, "
                    + "next_send_at = ?, attempts = 0 WHERE email = ?",
                    hash, expiresAt, nextSendAt, email);
        } else {
            jdbc.update("INSERT INTO " + table + " (email, code_hash, expires_at, next_send_at, attempts) "
                    + "VALUES (?, ?, ?, ?, 0)", email, hash, expiresAt, nextSendAt);
        }
    }

    public void incrementAttempts(String email, VerificationMailSender.Purpose purpose) {
        jdbc.update("UPDATE " + table(purpose) + " SET attempts = attempts + 1 WHERE email = ?", email);
    }

    public void delete(String email, VerificationMailSender.Purpose purpose) {
        jdbc.update("DELETE FROM " + table(purpose) + " WHERE email = ?", email);
    }

    public Optional<IpRate> ipRateForUpdate(String hash) {
        return jdbc.query("SELECT * FROM email_ip_rate WHERE ip_hash = ? FOR UPDATE",
                (rs, row) -> new IpRate(rs.getString("ip_hash"),
                        rs.getLong("window_started_at"), rs.getInt("sent_count")), hash)
                .stream().findFirst();
    }

    public void recordIp(String hash, long windowStartedAt, int count, boolean exists) {
        if (exists) {
            jdbc.update("UPDATE email_ip_rate SET window_started_at = ?, sent_count = ? WHERE ip_hash = ?",
                    windowStartedAt, count, hash);
        } else {
            jdbc.update("INSERT INTO email_ip_rate (ip_hash, window_started_at, sent_count) VALUES (?, ?, ?)",
                    hash, windowStartedAt, count);
        }
    }
}
