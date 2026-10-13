CREATE TABLE app_user (
    id VARCHAR(36) PRIMARY KEY,
    role VARCHAR(16) NOT NULL,
    email VARCHAR(254) UNIQUE,
    username VARCHAR(24) UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT ck_user_role CHECK (
        (role = 'GUARDIAN' AND email IS NOT NULL AND username IS NULL)
        OR (role = 'CHILD' AND email IS NULL AND username IS NOT NULL)
    )
);

CREATE TABLE email_code (
    email VARCHAR(254) PRIMARY KEY,
    code_hash VARCHAR(64) NOT NULL,
    expires_at BIGINT NOT NULL,
    next_send_at BIGINT NOT NULL,
    attempts INT NOT NULL
);

CREATE TABLE email_ip_rate (
    ip_hash VARCHAR(64) PRIMARY KEY,
    window_started_at BIGINT NOT NULL,
    sent_count INT NOT NULL
);

CREATE TABLE login_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_session_user ON login_session(user_id);

CREATE TABLE guardian_child (
    guardian_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    child_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    linked_at BIGINT NOT NULL,
    PRIMARY KEY (guardian_id, child_id)
);
CREATE INDEX idx_guardian_child_child ON guardian_child(child_id);

CREATE TABLE child_invite (
    code_hash VARCHAR(64) PRIMARY KEY,
    child_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    created_by VARCHAR(36) NOT NULL REFERENCES app_user(id),
    expires_at BIGINT NOT NULL,
    used_at BIGINT
);
CREATE INDEX idx_child_invite_expiry ON child_invite(expires_at);
