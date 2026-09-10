CREATE TABLE chunk_context_revisions (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  context_id VARCHAR(36) NOT NULL,
  previous_json MEDIUMTEXT NOT NULL,
  actor_id VARCHAR(36) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_context_revisions ON chunk_context_revisions(tenant_id,version_id,context_id,created_at);
