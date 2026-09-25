-- TASK-002 stores the minimum durable event state needed for insert and lookup.
CREATE TABLE evidence_lab.events (
    id UUID PRIMARY KEY,
    event_type TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
