CREATE TABLE billing_rules (
 id VARCHAR(36) PRIMARY KEY,
 tenant_id VARCHAR(36) NOT NULL,
 name VARCHAR(200) NOT NULL,
 currency VARCHAR(3) NOT NULL,
 customer_rates LONGTEXT NOT NULL,
 provider_rates LONGTEXT NOT NULL,
 created_by VARCHAR(36) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_billing_rules_tenant ON billing_rules(tenant_id,created_at);
ALTER TABLE tenants ADD COLUMN billing_rule_id VARCHAR(36) NULL;
ALTER TABLE tenants ADD COLUMN billing_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE usage_events ADD COLUMN billing_rule_id VARCHAR(36) NULL;
ALTER TABLE jobs ADD COLUMN billing_rule_id VARCHAR(36) NULL;
ALTER TABLE jobs ADD COLUMN source_write_bytes BIGINT NOT NULL DEFAULT 0;
