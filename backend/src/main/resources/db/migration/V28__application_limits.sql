ALTER TABLE applications ADD COLUMN requests_per_minute INT NOT NULL DEFAULT 60;
ALTER TABLE applications ADD COLUMN concurrent_requests INT NOT NULL DEFAULT 4;
ALTER TABLE applications ADD COLUMN limits_revision BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_usage_application_window ON usage_events(tenant_id, application_id, resource_type, created_at);
