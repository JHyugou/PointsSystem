ALTER TABLE point_action DROP CONSTRAINT ck_point_action_kind;
ALTER TABLE point_action ADD CONSTRAINT ck_point_action_kind CHECK (
    kind IN ('ITEM_GRANT', 'DIRECT_DEDUCTION', 'CLAIM_APPROVAL', 'REWARD_REDEMPTION')
);

CREATE TABLE reward_item (
    id VARCHAR(36) PRIMARY KEY,
    child_id VARCHAR(36) NOT NULL REFERENCES point_account(child_id),
    created_by VARCHAR(36) NOT NULL REFERENCES app_user(id),
    name VARCHAR(80) NOT NULL,
    description VARCHAR(300),
    cost INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    archived_at BIGINT,
    CONSTRAINT ck_reward_cost CHECK (cost BETWEEN 1 AND 100000)
);
CREATE INDEX idx_reward_item_child ON reward_item(child_id, created_at);

CREATE TABLE reward_redemption (
    id VARCHAR(36) PRIMARY KEY,
    child_id VARCHAR(36) NOT NULL REFERENCES point_account(child_id),
    reward_item_id VARCHAR(36) NOT NULL REFERENCES reward_item(id),
    action_id VARCHAR(36) NOT NULL UNIQUE REFERENCES point_action(id),
    reward_name VARCHAR(80) NOT NULL,
    reward_description VARCHAR(300),
    cost INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    requested_at BIGINT NOT NULL,
    offered_at BIGINT,
    offered_by VARCHAR(36) REFERENCES app_user(id),
    received_at BIGINT,
    CONSTRAINT ck_redemption_cost CHECK (cost BETWEEN 1 AND 100000),
    CONSTRAINT ck_redemption_status CHECK (status IN ('REQUESTED', 'OFFERED', 'RECEIVED'))
);
CREATE INDEX idx_reward_redemption_child ON reward_redemption(child_id, requested_at);
