CREATE TABLE application_configurations (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  application_id VARCHAR(36) NOT NULL,
  config_json TEXT NOT NULL,
  actor_id VARCHAR(36) NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_application_configurations ON application_configurations(tenant_id,application_id,created_at);
