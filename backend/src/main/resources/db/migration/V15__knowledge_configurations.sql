CREATE TABLE knowledge_configurations (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  kb_id VARCHAR(36) NOT NULL,
  definition_json TEXT NOT NULL,
  models_json TEXT NOT NULL,
  actor_id VARCHAR(36) NOT NULL,
  ever_published BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_kb_configurations ON knowledge_configurations(tenant_id,kb_id,created_at);
ALTER TABLE knowledge_bases ADD COLUMN published_configuration VARCHAR(36) NULL;
ALTER TABLE knowledge_bases ADD COLUMN configuration_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE document_versions ADD COLUMN configuration_id VARCHAR(36) NULL;
ALTER TABLE jobs ADD COLUMN configuration_id VARCHAR(36) NULL;
CREATE TABLE knowledge_configuration_publications (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  kb_id VARCHAR(36) NOT NULL,
  configuration_id VARCHAR(36) NOT NULL,
  previous_configuration VARCHAR(36) NULL,
  revision BIGINT NOT NULL,
  actor_id VARCHAR(36) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(kb_id,revision)
);
