package com.pointshome.api.ai;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiSettingStore {
    public record Setting(String encryptedApiKey, String modelId, long updatedAt) {}

    private final JdbcTemplate jdbc;

    public AiSettingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Setting> find(String guardianId) {
        return jdbc.query("""
                SELECT encrypted_api_key, model_id, updated_at
                FROM ai_provider_setting WHERE guardian_id = ?
                """, (rs, row) -> new Setting(rs.getString(1), rs.getString(2), rs.getLong(3)),
                guardianId).stream().findFirst();
    }

    public void save(String guardianId, String encryptedKey, String modelId, long now) {
        int changed = jdbc.update("""
                UPDATE ai_provider_setting
                SET encrypted_api_key = ?, model_id = ?, updated_at = ?
                WHERE guardian_id = ?
                """, encryptedKey, modelId, now, guardianId);
        if (changed == 0) jdbc.update("""
                INSERT INTO ai_provider_setting (guardian_id, encrypted_api_key, model_id, updated_at)
                VALUES (?, ?, ?, ?)
                """, guardianId, encryptedKey, modelId, now);
    }

    public void delete(String guardianId) {
        jdbc.update("DELETE FROM ai_provider_setting WHERE guardian_id = ?", guardianId);
    }
}
