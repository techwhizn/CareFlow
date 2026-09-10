CREATE TABLE ocr_page_calls (
 id VARCHAR(36) PRIMARY KEY,
 tenant_id VARCHAR(36) NOT NULL,
 job_id VARCHAR(36) NOT NULL,
 lease_token VARCHAR(36) NOT NULL,
 state VARCHAR(20) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 completed_at TIMESTAMP NULL
);
CREATE INDEX idx_ocr_page_tenant ON ocr_page_calls(tenant_id,created_at);
CREATE INDEX idx_ocr_page_job ON ocr_page_calls(job_id,lease_token);
