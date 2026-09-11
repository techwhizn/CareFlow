ALTER TABLE members ADD COLUMN external_subject VARCHAR(300) NULL;
ALTER TABLE members ADD COLUMN external_issuer VARCHAR(500) NULL;
CREATE UNIQUE INDEX uq_member_external_identity ON members(tenant_id, external_issuer, external_subject);
