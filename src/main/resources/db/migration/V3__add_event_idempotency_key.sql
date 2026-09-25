-- TASK-003 adds a caller-supplied key and preserves existing rows during migration.
ALTER TABLE evidence_lab.events
    ADD COLUMN idempotency_key TEXT;

UPDATE evidence_lab.events
SET idempotency_key = id::TEXT
WHERE idempotency_key IS NULL;

ALTER TABLE evidence_lab.events
    ALTER COLUMN idempotency_key SET NOT NULL;

ALTER TABLE evidence_lab.events
    ADD CONSTRAINT events_idempotency_key_key UNIQUE (idempotency_key);
