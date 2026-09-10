CREATE TABLE model_profiles (
 id CHAR(36) PRIMARY KEY,
 tenant_id CHAR(36) NOT NULL,
 name VARCHAR(200) NOT NULL,
 kind VARCHAR(20) NOT NULL,
 base_url VARCHAR(500) NOT NULL,
 model VARCHAR(200) NOT NULL,
 model_revision VARCHAR(200) NOT NULL,
 dimensions INT,
 external_processing BOOLEAN NOT NULL,
 encrypted_key TEXT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (tenant_id) REFERENCES tenants(id),
 INDEX ix_model_profiles_tenant (tenant_id)
);
