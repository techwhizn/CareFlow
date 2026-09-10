CREATE TABLE processing_model_calls (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  job_id VARCHAR(36) NOT NULL,
  lease_token VARCHAR(36) NOT NULL,
  model_identity VARCHAR(500) NOT NULL,
  input_count INT NOT NULL,
  state VARCHAR(16) NOT NULL,
  tokens BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP NULL
);
CREATE INDEX idx_processing_calls_job ON processing_model_calls(tenant_id,job_id);
ALTER TABLE jobs ADD COLUMN indexed_chunks INT NULL;
ALTER TABLE jobs ADD COLUMN embedded_texts INT NULL;
ALTER TABLE jobs ADD COLUMN reused_chunks INT NULL;
