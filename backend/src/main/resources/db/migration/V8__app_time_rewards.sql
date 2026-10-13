ALTER TABLE reward_item ADD COLUMN reward_kind VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE reward_item ADD COLUMN time_minutes INT;
ALTER TABLE reward_item ADD CONSTRAINT ck_reward_time CHECK (
    (reward_kind = 'STANDARD' AND time_minutes IS NULL)
    OR (reward_kind = 'APP_TIME' AND time_minutes IS NOT NULL AND time_minutes BETWEEN 1 AND 1440)
);
ALTER TABLE reward_redemption ALTER COLUMN action_id DROP NOT NULL;
ALTER TABLE reward_redemption ADD COLUMN reward_kind VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE reward_redemption ADD COLUMN time_minutes INT;
ALTER TABLE reward_redemption ADD COLUMN device_id VARCHAR(36);
ALTER TABLE reward_redemption ADD COLUMN request_id VARCHAR(36);
UPDATE reward_redemption SET request_id = (SELECT request_id FROM point_action WHERE id = action_id);
ALTER TABLE reward_redemption ALTER COLUMN request_id SET NOT NULL;
ALTER TABLE reward_redemption ADD CONSTRAINT uq_redemption_request UNIQUE (child_id, request_id);
ALTER TABLE reward_redemption DROP CONSTRAINT ck_redemption_status;
ALTER TABLE reward_redemption ADD CONSTRAINT ck_redemption_status CHECK (
    status IN ('REQUESTED', 'OFFERED', 'RECEIVED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED')
);
ALTER TABLE reward_redemption ADD CONSTRAINT ck_redemption_time CHECK (
    (reward_kind = 'STANDARD' AND time_minutes IS NULL AND device_id IS NULL AND action_id IS NOT NULL)
    OR (reward_kind = 'APP_TIME' AND time_minutes IS NOT NULL AND time_minutes BETWEEN 1 AND 1440 AND device_id IS NOT NULL)
);
CREATE TABLE app_time_device (
    child_id VARCHAR(36) PRIMARY KEY REFERENCES point_account(child_id),
    device_id VARCHAR(36) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    device_name VARCHAR(100) NOT NULL,
    configured_by VARCHAR(36) NOT NULL REFERENCES app_user(id),
    configured_at BIGINT NOT NULL
);
CREATE INDEX idx_time_delivery ON reward_redemption(child_id, device_id, status, reward_kind);
