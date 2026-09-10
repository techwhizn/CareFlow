ALTER TABLE usage_events ADD COLUMN operation VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE usage_events ADD COLUMN outcome VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE usage_events ADD COLUMN error_code VARCHAR(100) NULL;
ALTER TABLE usage_events ADD COLUMN completed_at TIMESTAMP NULL;
ALTER TABLE usage_events ADD COLUMN application_revision BIGINT NULL;
ALTER TABLE usage_events ADD COLUMN configuration_id VARCHAR(36) NULL;
ALTER TABLE usage_events ADD COLUMN application_configuration_id VARCHAR(36) NULL;
CREATE INDEX idx_usage_tenant_requests ON usage_events(tenant_id,resource_type,created_at,id);
CREATE INDEX idx_usage_application_running ON usage_events(tenant_id,application_id,resource_type,state);
