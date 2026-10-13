package com.pointshome.api.rewards;

import com.pointshome.api.auth.Account;
import com.pointshome.api.auth.AccountStore;
import com.pointshome.api.auth.Crypto;
import com.pointshome.api.auth.CurrentUser;
import com.pointshome.api.common.ApiProblem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TimeDeviceService {
    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final Crypto crypto;
    private final SecureRandom random = new SecureRandom();

    public TimeDeviceService(JdbcTemplate jdbc, AccountStore accounts, Crypto crypto) {
        this.jdbc = jdbc; this.accounts = accounts; this.crypto = crypto;
    }

    @Transactional
    public Registration register(CurrentUser user, String childId, String deviceId, String name) {
        if (user == null || user.role() != Account.Role.GUARDIAN || !accounts.isLinked(user.id(), childId))
            throw forbidden();
        validateDeviceId(deviceId);
        String cleanName = name == null ? "孩子手机" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 100) throw forbidden();
        // Share the account lock with redemption/approval to serialize device replacement.
        jdbc.queryForObject("SELECT child_id FROM point_account WHERE child_id = ? FOR UPDATE", String.class, childId);
        Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reward_redemption WHERE child_id = ? AND reward_kind = 'APP_TIME'
                AND status = 'APPROVED' AND device_id <> ?
                """, Integer.class, childId, deviceId);
        if (pending != null && pending > 0)
            throw new ApiProblem(HttpStatus.CONFLICT, "UNCLAIMED_TIME", "旧手机还有待领取时间，请先完成领取再更换绑定");
        byte[] secret = new byte[32]; random.nextBytes(secret);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.update("""
                MERGE INTO app_time_device (child_id, device_id, key_hash, device_name, configured_by, configured_at)
                KEY(child_id) VALUES (?, ?, ?, ?, ?, ?)
                """, childId, deviceId, crypto.sha256(key), cleanName, user.id(), System.currentTimeMillis());
        return new Registration(deviceId, key);
    }

    public void requireDevice(CurrentUser user, String deviceId, String key) {
        if (user == null || user.role() != Account.Role.CHILD) throw forbidden();
        validateDeviceId(deviceId);
        if (key == null || key.length() != 43) throw forbidden();
        String expected = jdbc.query("SELECT key_hash FROM app_time_device WHERE child_id = ? AND device_id = ?",
                (rs, row) -> rs.getString(1), user.id(), deviceId).stream().findFirst().orElse(null);
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                crypto.sha256(key).getBytes(StandardCharsets.UTF_8))) throw forbidden();
    }

    public boolean isBound(String childId, String deviceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM app_time_device WHERE child_id = ? AND device_id = ?",
                Boolean.class, childId, deviceId));
    }

    private void validateDeviceId(String id) {
        try { if (id == null || id.length() != 36) throw new IllegalArgumentException(); UUID.fromString(id); }
        catch (IllegalArgumentException e) { throw forbidden(); }
    }
    private ApiProblem forbidden() {
        return new ApiProblem(HttpStatus.FORBIDDEN, "TIME_DEVICE_REQUIRED", "请由关联家长在孩子手机上完成本机管理设置");
    }
    public record Registration(String deviceId, String deviceKey) {}
}
