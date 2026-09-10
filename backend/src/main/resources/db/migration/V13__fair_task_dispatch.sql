CREATE TABLE task_dispatch_cursor (id INT PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL DEFAULT '');
INSERT INTO task_dispatch_cursor(id,tenant_id) VALUES(1,'');
ALTER TABLE jobs ADD COLUMN dispatch_until TIMESTAMP NULL;
ALTER TABLE jobs ADD COLUMN dispatch_token VARCHAR(36) NULL;
ALTER TABLE jobs ADD COLUMN wait_reason VARCHAR(40) NULL;
CREATE INDEX idx_jobs_tenant_dispatch ON jobs(tenant_id,state,dispatch_until);
CREATE INDEX idx_outbox_job ON outbox(job_id);
