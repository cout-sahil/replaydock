CREATE TABLE IF NOT EXISTS endpoints (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(80) NOT NULL, target_url VARCHAR(2048),
 secret VARCHAR(64) NOT NULL, max_attempts INT NOT NULL, base_delay_ms BIGINT NOT NULL,
 created_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
 id VARCHAR(36) PRIMARY KEY, endpoint_id VARCHAR(36) NOT NULL REFERENCES endpoints(id),
 source_id VARCHAR(128) NOT NULL, payload CLOB NOT NULL, status VARCHAR(20) NOT NULL,
 attempt_count INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP NOT NULL,
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 CONSTRAINT unique_source_event UNIQUE(endpoint_id, source_id)
);
CREATE INDEX IF NOT EXISTS due_events ON events(status, next_attempt_at);
CREATE TABLE IF NOT EXISTS attempts (
 id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36) NOT NULL REFERENCES events(id),
 attempt_number INT NOT NULL, http_status INT, outcome VARCHAR(30) NOT NULL,
 detail VARCHAR(1024) NOT NULL, duration_ms BIGINT NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS mock_controls (
 endpoint_id VARCHAR(36) PRIMARY KEY REFERENCES endpoints(id), failures_left INT NOT NULL DEFAULT 2,
 failure_status INT NOT NULL DEFAULT 503, delay_ms INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS mock_processed (
 endpoint_id VARCHAR(36) NOT NULL REFERENCES endpoints(id), source_id VARCHAR(128) NOT NULL,
 processed_at TIMESTAMP NOT NULL, PRIMARY KEY(endpoint_id, source_id)
);
