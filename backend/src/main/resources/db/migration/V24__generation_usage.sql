CREATE TABLE generation_usage (
    request_id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    subject_id VARCHAR(36) NOT NULL,
    configuration_id VARCHAR(36),
    request_state VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    usage_state VARCHAR(20) NOT NULL DEFAULT 'NOT_CALLED',
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    total_tokens BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    CONSTRAINT fk_generation_usage_request FOREIGN KEY(request_id) REFERENCES usage_events(id)
);
CREATE INDEX idx_generation_usage_tenant ON generation_usage(tenant_id, created_at);
