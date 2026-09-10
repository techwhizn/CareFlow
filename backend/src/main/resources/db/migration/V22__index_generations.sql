ALTER TABLE document_versions ADD COLUMN active_index_generation VARCHAR(36) NULL;
ALTER TABLE jobs ADD COLUMN index_generation_id VARCHAR(36) NULL;
ALTER TABLE jobs ADD COLUMN index_rebuild BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE index_generations (
  id VARCHAR(36) PRIMARY KEY,
  sequence_no BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
  tenant_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  job_id VARCHAR(36) NOT NULL,
  lease_token VARCHAR(36) NOT NULL,
  configuration_id VARCHAR(36) NOT NULL,
  model_identity VARCHAR(500) NOT NULL,
  content_revision BIGINT NOT NULL,
  previous_generation VARCHAR(36) NULL,
  state VARCHAR(16) NOT NULL,
  manifest VARCHAR(64) NULL,
  chunk_count INT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP NULL
);
CREATE INDEX idx_generations_version ON index_generations(tenant_id,version_id,created_at);
CREATE INDEX idx_generations_cleanup ON index_generations(state,completed_at);
CREATE TABLE index_checks (
  id VARCHAR(36) PRIMARY KEY,
  sequence_no BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
  tenant_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  generation_id VARCHAR(36) NULL,
  content_revision BIGINT NOT NULL,
  actor_id VARCHAR(36) NOT NULL,
  report_json TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_checks_version ON index_checks(tenant_id,version_id,created_at);
