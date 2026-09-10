CREATE TABLE retrieval_model_calls (
 id VARCHAR(36) PRIMARY KEY,
 tenant_id VARCHAR(36) NOT NULL,
 request_id VARCHAR(36) NOT NULL,
 stage VARCHAR(20) NOT NULL,
 configuration_id VARCHAR(36),
 input_count INT NOT NULL,
 call_state VARCHAR(20) NOT NULL,
 usage_state VARCHAR(20) NOT NULL,
 total_tokens BIGINT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 completed_at TIMESTAMP NULL,
 CONSTRAINT fk_retrieval_call_request FOREIGN KEY(request_id) REFERENCES usage_events(id)
);
CREATE INDEX idx_retrieval_calls_request ON retrieval_model_calls(tenant_id,request_id);
