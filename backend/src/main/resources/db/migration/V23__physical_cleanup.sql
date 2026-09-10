ALTER TABLE cleanup_requests ADD COLUMN phase VARCHAR(32) NOT NULL DEFAULT 'PREPARE';
ALTER TABLE cleanup_requests ADD COLUMN attempts INT NOT NULL DEFAULT 0;
ALTER TABLE cleanup_requests ADD COLUMN failures INT NOT NULL DEFAULT 0;
ALTER TABLE cleanup_requests ADD COLUMN lease_token VARCHAR(36) NULL;
ALTER TABLE cleanup_requests ADD COLUMN lease_until TIMESTAMP NULL;
ALTER TABLE cleanup_requests ADD COLUMN error_code VARCHAR(100) NULL;
ALTER TABLE cleanup_requests ADD COLUMN completed_at TIMESTAMP NULL;
ALTER TABLE cleanup_requests ADD COLUMN deadline_at TIMESTAMP NULL;
ALTER TABLE cleanup_requests ADD COLUMN payload_json MEDIUMTEXT NULL;
ALTER TABLE cleanup_requests ADD COLUMN compactions_json MEDIUMTEXT NULL;
CREATE INDEX idx_cleanup_due ON cleanup_requests(state,not_before,lease_until);
CREATE TABLE index_cache_references (
  tenant_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  generation_id VARCHAR(36) NOT NULL,
  model_identity VARCHAR(64) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  PRIMARY KEY(tenant_id,version_id,model_identity,content_hash)
);
CREATE INDEX idx_cache_owners ON index_cache_references(tenant_id,model_identity,content_hash);
ALTER TABLE document_versions ADD COLUMN purged_at TIMESTAMP NULL;
ALTER TABLE documents ADD COLUMN purged_at TIMESTAMP NULL;
ALTER TABLE knowledge_bases ADD COLUMN purged_at TIMESTAMP NULL;
