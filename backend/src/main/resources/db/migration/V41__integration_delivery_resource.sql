ALTER TABLE integration_deliveries ADD COLUMN resource_id VARCHAR(36) NULL;
CREATE INDEX idx_integration_delivery_resource ON integration_deliveries(tenant_id, resource_id);
