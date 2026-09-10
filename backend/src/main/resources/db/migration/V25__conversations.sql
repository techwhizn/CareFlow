CREATE TABLE conversations (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    subject_id VARCHAR(36) NOT NULL,
    subject_kind VARCHAR(16) NOT NULL,
    application_id VARCHAR(36) NOT NULL DEFAULT '',
    knowledge_base_ids TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    active_request_id VARCHAR(36) NULL,
    busy_until TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_conversations_owner ON conversations(tenant_id,subject_id,subject_kind,updated_at);
ALTER TABLE answers ADD COLUMN conversation_id VARCHAR(36) NULL;
ALTER TABLE answers ADD COLUMN turn_no BIGINT NULL;
CREATE UNIQUE INDEX idx_answers_conversation_turn ON answers(conversation_id,turn_no);
ALTER TABLE answer_evidence ADD COLUMN evidence_role VARCHAR(16) NOT NULL DEFAULT 'CURRENT';
ALTER TABLE answer_evidence ADD COLUMN evidence_json MEDIUMTEXT NULL;
