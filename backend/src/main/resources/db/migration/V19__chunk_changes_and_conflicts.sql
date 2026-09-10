ALTER TABLE chunks ADD COLUMN tags_json VARCHAR(4000) NOT NULL DEFAULT '[]';
ALTER TABLE chunks MODIFY COLUMN source_text MEDIUMTEXT NOT NULL;
CREATE TABLE chunk_changes (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  action VARCHAR(32) NOT NULL,
  previous_json MEDIUMTEXT NOT NULL,
  result_ids_json TEXT NOT NULL,
  actor_id VARCHAR(36) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_chunk_changes_version ON chunk_changes(tenant_id,version_id,created_at);
CREATE TABLE content_conflicts (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  source_version_id VARCHAR(36) NOT NULL,
  source_chunk_id VARCHAR(36) NOT NULL,
  snapshot_json MEDIUMTEXT NOT NULL,
  resolution VARCHAR(32),
  result_chunk_id VARCHAR(36),
  actor_id VARCHAR(36),
  reason VARCHAR(1000),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at TIMESTAMP NULL,
  UNIQUE(version_id,source_chunk_id)
);
CREATE INDEX idx_conflicts_version ON content_conflicts(tenant_id,version_id,resolution);
