CREATE TABLE integration_deliveries (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  endpoint_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  delivered_at TIMESTAMP NULL
);
CREATE INDEX idx_integration_delivery_queue ON integration_deliveries(status, next_attempt_at, created_at);
