ALTER TABLE publications ADD COLUMN document_revision BIGINT NULL;
ALTER TABLE publications ADD COLUMN version_revision BIGINT NULL;
ALTER TABLE publications ADD COLUMN previous_version VARCHAR(36) NULL;
CREATE INDEX idx_publications_document ON publications(tenant_id,document_id,created_at);
