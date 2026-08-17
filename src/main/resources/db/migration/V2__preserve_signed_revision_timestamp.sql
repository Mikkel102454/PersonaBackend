-- PostgreSQL timestamptz stores at most microseconds, while Java Instant and the
-- signature envelope can contain nanoseconds. Keep the exact signed representation
-- alongside the indexed timestamp so reading a revision cannot alter its signature.
ALTER TABLE content_revision ADD COLUMN signed_created_at varchar(40);

CREATE TABLE content_snapshot_envelope (
    session_id uuid PRIMARY KEY REFERENCES editor_session(id) ON DELETE CASCADE,
    installation_id uuid NOT NULL,
    revision char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    signed_created_at varchar(40) NOT NULL,
    signature text NOT NULL,
    FOREIGN KEY (installation_id, revision)
        REFERENCES content_revision(installation_id, revision) ON DELETE CASCADE
);
CREATE INDEX content_snapshot_envelope_revision_idx
    ON content_snapshot_envelope(installation_id, revision);

-- Existing envelopes may already have lost sub-microsecond precision. They are kept
-- for audit/history; the next plugin upload replaces the session envelope exactly.
INSERT INTO content_snapshot_envelope(session_id, installation_id, revision, created_at,
                                      signed_created_at, signature)
SELECT source_session_id, installation_id, revision, created_at,
       to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), signature
FROM content_revision
WHERE source_session_id IS NOT NULL;
