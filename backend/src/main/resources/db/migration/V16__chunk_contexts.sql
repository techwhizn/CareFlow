CREATE TABLE chunk_contexts (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  ordinal_no INT NOT NULL,
  kind VARCHAR(16) NOT NULL,
  source_text TEXT NOT NULL,
  content TEXT NOT NULL,
  location TEXT NOT NULL,
  token_count INT NOT NULL,
  question VARCHAR(1000),
  alternatives_json TEXT NOT NULL,
  origin VARCHAR(20) NOT NULL DEFAULT 'PARSED',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(version_id,ordinal_no),
  UNIQUE(tenant_id,version_id,id),
  CHECK(kind IN ('PARENT','FAQ'))
);
ALTER TABLE chunks ADD COLUMN context_id VARCHAR(36);
ALTER TABLE chunks ADD COLUMN origin VARCHAR(20) NOT NULL DEFAULT 'PARSED';
ALTER TABLE chunks ADD CONSTRAINT fk_chunk_context_version
  FOREIGN KEY(tenant_id,version_id,context_id) REFERENCES chunk_contexts(tenant_id,version_id,id);
CREATE INDEX idx_chunk_context ON chunks(tenant_id,version_id,context_id);
