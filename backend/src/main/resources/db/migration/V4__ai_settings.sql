CREATE TABLE ai_provider_setting (
    guardian_id VARCHAR(36) PRIMARY KEY REFERENCES app_user(id),
    encrypted_api_key VARCHAR(2048) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    updated_at BIGINT NOT NULL
);
