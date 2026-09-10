ALTER TABLE usage_events ADD COLUMN expires_at TIMESTAMP NULL;
ALTER TABLE usage_events ADD COLUMN result_committed BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE usage_events SET expires_at=TIMESTAMPADD(SECOND,300,CURRENT_TIMESTAMP) WHERE resource_type='QUERY' AND state='RESERVED';
UPDATE usage_events u SET result_committed=TRUE WHERE u.resource_type='QUERY' AND u.state='RESERVED' AND (EXISTS(SELECT 1 FROM answers a WHERE a.tenant_id=u.tenant_id AND a.query_record_id=u.id) OR (u.operation='SEARCH' AND EXISTS(SELECT 1 FROM query_records q WHERE q.tenant_id=u.tenant_id AND q.id=u.id)));
CREATE INDEX idx_query_reservation_expiry ON usage_events(resource_type,state,expires_at);
