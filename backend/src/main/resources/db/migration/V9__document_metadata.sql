ALTER TABLE documents ADD COLUMN source VARCHAR(2000) NOT NULL DEFAULT '';
ALTER TABLE documents ADD COLUMN language VARCHAR(20) NOT NULL DEFAULT 'zh';
ALTER TABLE documents ADD COLUMN tags_json TEXT NULL;
ALTER TABLE documents ADD COLUMN product_models_json TEXT NULL;
ALTER TABLE documents ADD COLUMN valid_from DATETIME NULL;
ALTER TABLE documents ADD COLUMN valid_until DATETIME NULL;
CREATE TABLE document_metadata_history (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  revision BIGINT NOT NULL,
  actor_id VARCHAR(36) NOT NULL,
  metadata_json TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id, document_id, revision)
);
