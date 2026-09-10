ALTER TABLE applications ADD COLUMN owner_id VARCHAR(36) NULL;
ALTER TABLE applications ADD COLUMN published_configuration VARCHAR(36) NULL;
ALTER TABLE application_configurations ADD COLUMN models_json TEXT NULL;
CREATE TABLE application_publications (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    configuration_id VARCHAR(36) NOT NULL,
    previous_configuration_id VARCHAR(36) NULL,
    actor_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_application_publications ON application_publications(tenant_id,application_id,revision);
