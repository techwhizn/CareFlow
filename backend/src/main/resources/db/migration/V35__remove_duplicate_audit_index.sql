-- V1 already provides (tenant_id,created_at); preserve its index and remove V34's duplicate.
DROP INDEX idx_audit_retention ON audit_events;
