ALTER TABLE knowledge_bases ADD COLUMN tags_json TEXT NULL;
ALTER TABLE document_versions ADD COLUMN size_bytes BIGINT NULL;
