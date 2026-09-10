ALTER TABLE answers ADD COLUMN feedback_reason VARCHAR(40) NULL;
ALTER TABLE answers ADD COLUMN feedback_comment VARCHAR(2000) NULL;
ALTER TABLE answers ADD COLUMN feedback_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE answers ADD COLUMN query_record_id VARCHAR(36) NULL;
CREATE TABLE query_records (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    subject_id VARCHAR(36) NOT NULL,
    subject_kind VARCHAR(16) NOT NULL,
    application_id VARCHAR(36) NOT NULL DEFAULT '',
    question TEXT NOT NULL,
    configuration_id VARCHAR(36) NULL,
    application_revision BIGINT NOT NULL DEFAULT -1,
    knowledge_base_ids TEXT NOT NULL,
    query_options TEXT NOT NULL,
    evidence_status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_query_record_owner ON query_records(tenant_id,subject_id,created_at);
CREATE TABLE query_record_evidence (
    query_record_id VARCHAR(36) NOT NULL,
    document_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    chunk_id VARCHAR(36) NOT NULL,
    PRIMARY KEY(query_record_id,chunk_id),
    FOREIGN KEY(query_record_id) REFERENCES query_records(id) ON DELETE CASCADE
);
CREATE TABLE improvement_tasks (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    kb_id VARCHAR(36) NOT NULL,
    creator_id VARCHAR(36) NOT NULL,
    creator_kind VARCHAR(16) NOT NULL,
    source_kind VARCHAR(20) NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    answer_id VARCHAR(36) NULL,
    query_record_id VARCHAR(36) NULL,
    reason VARCHAR(40) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    assignee_id VARCHAR(36) NULL,
    resolution VARCHAR(2000) NOT NULL DEFAULT '',
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id,kb_id,source_kind,source_id),
    FOREIGN KEY(answer_id) REFERENCES answers(id) ON DELETE CASCADE,
    FOREIGN KEY(query_record_id) REFERENCES query_records(id) ON DELETE CASCADE
);
CREATE INDEX idx_improvement_queue ON improvement_tasks(tenant_id,kb_id,state,updated_at);
