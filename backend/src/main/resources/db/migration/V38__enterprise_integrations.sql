CREATE TABLE integration_endpoints (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  name VARCHAR(200) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  endpoint_url VARCHAR(2000) NOT NULL,
  secret_ciphertext TEXT NOT NULL,
  events VARCHAR(1000) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  revision BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_integrations_tenant ON integration_endpoints(tenant_id, active, created_at);
