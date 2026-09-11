ALTER TABLE members ADD COLUMN external_subject VARCHAR(300) NULL;
ALTER TABLE members ADD COLUMN external_issuer VARCHAR(500) NULL;
-- MySQL utf8mb4 cannot index the full 300+500 character identity tuple.
-- A deterministic digest keeps uniqueness exact without a lossy prefix index.
ALTER TABLE members
  ADD COLUMN external_identity_hash BINARY(32)
    GENERATED ALWAYS AS (
      UNHEX(SHA2(CONCAT(tenant_id, CHAR(0), external_issuer, CHAR(0), external_subject), 256))
    ) STORED;
CREATE UNIQUE INDEX uq_member_external_identity ON members(external_identity_hash);
