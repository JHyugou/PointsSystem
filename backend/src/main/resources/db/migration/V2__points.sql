CREATE TABLE point_account (
    child_id VARCHAR(36) PRIMARY KEY REFERENCES app_user(id),
    balance BIGINT NOT NULL DEFAULT 0
);

INSERT INTO point_account (child_id, balance)
SELECT id, 0 FROM app_user WHERE role = 'CHILD';

CREATE TABLE point_item (
    id VARCHAR(36) PRIMARY KEY,
    child_id VARCHAR(36) NOT NULL REFERENCES point_account(child_id),
    created_by VARCHAR(36) NOT NULL REFERENCES app_user(id),
    name VARCHAR(80) NOT NULL,
    points INT NOT NULL,
    max_uses INT,
    remaining_uses INT,
    expires_at BIGINT,
    created_at BIGINT NOT NULL,
    CONSTRAINT ck_point_item_points CHECK (points BETWEEN -100000 AND 100000 AND points <> 0),
    CONSTRAINT ck_point_item_uses CHECK (
        (max_uses IS NULL AND remaining_uses IS NULL)
        OR (max_uses > 0 AND remaining_uses BETWEEN 0 AND max_uses)
    )
);
CREATE INDEX idx_point_item_child ON point_item(child_id, created_at);

CREATE TABLE point_claim (
    id VARCHAR(36) PRIMARY KEY,
    child_id VARCHAR(36) NOT NULL REFERENCES point_account(child_id),
    item_id VARCHAR(36) NOT NULL REFERENCES point_item(id),
    status VARCHAR(16) NOT NULL,
    note VARCHAR(300),
    created_at BIGINT NOT NULL,
    decided_at BIGINT,
    decided_by VARCHAR(36) REFERENCES app_user(id),
    CONSTRAINT ck_point_claim_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);
CREATE INDEX idx_point_claim_child ON point_claim(child_id, created_at);

CREATE TABLE point_action (
    id VARCHAR(36) PRIMARY KEY,
    child_id VARCHAR(36) NOT NULL REFERENCES point_account(child_id),
    item_id VARCHAR(36) REFERENCES point_item(id),
    claim_id VARCHAR(36) REFERENCES point_claim(id),
    actor_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    request_id VARCHAR(36),
    kind VARCHAR(24) NOT NULL,
    amount INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(300),
    due_at BIGINT,
    created_at BIGINT NOT NULL,
    applied_at BIGINT,
    reversed_at BIGINT,
    reversed_by VARCHAR(36) REFERENCES app_user(id),
    CONSTRAINT ck_point_action_amount CHECK (amount BETWEEN -100000 AND 100000 AND amount <> 0),
    CONSTRAINT ck_point_action_status CHECK (status IN ('PENDING', 'APPLIED', 'CANCELLED', 'REVERSED')),
    CONSTRAINT ck_point_action_kind CHECK (kind IN ('ITEM_GRANT', 'DIRECT_DEDUCTION', 'CLAIM_APPROVAL')),
    CONSTRAINT uq_point_action_request UNIQUE (actor_id, request_id),
    CONSTRAINT uq_point_action_claim UNIQUE (claim_id)
);
CREATE INDEX idx_point_action_due ON point_action(status, due_at);
CREATE INDEX idx_point_action_child ON point_action(child_id, created_at);

CREATE TABLE point_ledger (
    id VARCHAR(36) PRIMARY KEY,
    child_id VARCHAR(36) NOT NULL REFERENCES point_account(child_id),
    action_id VARCHAR(36) NOT NULL REFERENCES point_action(id),
    actor_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    delta INT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    reverses_ledger_id VARCHAR(36) REFERENCES point_ledger(id),
    created_at BIGINT NOT NULL,
    CONSTRAINT ck_point_ledger_delta CHECK (delta <> 0),
    CONSTRAINT ck_point_ledger_kind CHECK (kind IN ('APPLY', 'REVERSAL'))
);
CREATE INDEX idx_point_ledger_child ON point_ledger(child_id, created_at);
CREATE UNIQUE INDEX uq_point_ledger_action_kind ON point_ledger(action_id, kind);
