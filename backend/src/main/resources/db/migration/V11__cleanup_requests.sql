CREATE TABLE cleanup_requests (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  resource_id VARCHAR(36) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  requested_by VARCHAR(36) NOT NULL,
  not_before TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,resource_type,resource_id)
);
