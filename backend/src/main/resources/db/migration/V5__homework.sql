CREATE TABLE homework_submission (
    id VARCHAR(36) PRIMARY KEY,
    child_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    submitted_by VARCHAR(36) NOT NULL REFERENCES app_user(id),
    subject VARCHAR(40) NOT NULL,
    instructions VARCHAR(500),
    status VARCHAR(16) NOT NULL,
    model_id VARCHAR(128),
    ai_result CLOB,
    ai_score INT,
    ai_error VARCHAR(200),
    analyzing_at BIGINT,
    analysis_token VARCHAR(36),
    analyzed_at BIGINT,
    chat_started_at BIGINT,
    chat_token VARCHAR(36),
    parent_score INT,
    parent_note VARCHAR(500),
    reviewed_by VARCHAR(36) REFERENCES app_user(id),
    reviewed_at BIGINT,
    point_item_id VARCHAR(36) REFERENCES point_item(id),
    point_action_id VARCHAR(36) REFERENCES point_action(id),
    created_at BIGINT NOT NULL,
    CONSTRAINT ck_homework_status CHECK (status IN ('SUBMITTED', 'ANALYZING', 'ANALYZED', 'FAILED', 'REVIEWED')),
    CONSTRAINT ck_homework_scores CHECK ((ai_score IS NULL OR ai_score BETWEEN 0 AND 100)
        AND (parent_score IS NULL OR parent_score BETWEEN 0 AND 100))
);
CREATE INDEX idx_homework_child ON homework_submission(child_id, created_at DESC);

CREATE TABLE homework_photo (
    submission_id VARCHAR(36) NOT NULL REFERENCES homework_submission(id),
    ordinal INT NOT NULL,
    media_type VARCHAR(16) NOT NULL,
    byte_size INT NOT NULL,
    PRIMARY KEY (submission_id, ordinal)
);

CREATE TABLE homework_chat (
    id VARCHAR(36) PRIMARY KEY,
    submission_id VARCHAR(36) NOT NULL REFERENCES homework_submission(id),
    role VARCHAR(16) NOT NULL,
    content VARCHAR(3000) NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT ck_homework_chat_role CHECK (role IN ('USER', 'ASSISTANT'))
);
CREATE INDEX idx_homework_chat ON homework_chat(submission_id, created_at, id);

CREATE TABLE homework_score_rule (
    child_id VARCHAR(36) PRIMARY KEY REFERENCES app_user(id),
    excellent_item_id VARCHAR(36) REFERENCES point_item(id),
    pass_item_id VARCHAR(36) REFERENCES point_item(id),
    needs_work_item_id VARCHAR(36) REFERENCES point_item(id),
    updated_by VARCHAR(36) NOT NULL REFERENCES app_user(id),
    updated_at BIGINT NOT NULL
);
