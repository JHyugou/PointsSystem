CREATE TABLE password_reset_code (
    email VARCHAR(254) PRIMARY KEY,
    code_hash VARCHAR(64) NOT NULL,
    expires_at BIGINT NOT NULL,
    next_send_at BIGINT NOT NULL,
    attempts INT NOT NULL
);
