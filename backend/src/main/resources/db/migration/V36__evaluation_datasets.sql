CREATE TABLE evaluation_datasets (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, kb_id VARCHAR(36) NOT NULL,
 name VARCHAR(200) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 created_by VARCHAR(36) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_evaluation_datasets ON evaluation_datasets(tenant_id,kb_id);
CREATE TABLE evaluation_dataset_versions (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, dataset_id VARCHAR(36) NOT NULL,
 version_number BIGINT NOT NULL, cases_json LONGTEXT NOT NULL, created_by VARCHAR(36) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(dataset_id,version_number), FOREIGN KEY(dataset_id) REFERENCES evaluation_datasets(id) ON DELETE CASCADE
);
CREATE TABLE evaluation_case_reviews (
 version_id VARCHAR(36) NOT NULL, case_id VARCHAR(100) NOT NULL, reviewer_id VARCHAR(36) NOT NULL,
 decision VARCHAR(16) NOT NULL, note VARCHAR(2000) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(version_id,case_id), FOREIGN KEY(version_id) REFERENCES evaluation_dataset_versions(id) ON DELETE CASCADE
);
CREATE TABLE evaluation_evidence (
 version_id VARCHAR(36) NOT NULL, document_id VARCHAR(36) NOT NULL,
 source_version_id VARCHAR(36) NOT NULL, chunk_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(version_id,chunk_id), FOREIGN KEY(version_id) REFERENCES evaluation_dataset_versions(id) ON DELETE CASCADE
);
CREATE INDEX idx_evaluation_source ON evaluation_evidence(source_version_id,version_id);
