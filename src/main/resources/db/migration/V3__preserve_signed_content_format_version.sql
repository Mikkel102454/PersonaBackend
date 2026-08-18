-- A content revision is deduplicated by installation and digest, but the format
-- version is part of each session's signed snapshot envelope. Preserve it beside
-- the other signed fields so a repeated digest cannot inherit an older version.
ALTER TABLE content_snapshot_envelope ADD COLUMN content_format_version integer;

UPDATE content_snapshot_envelope e
SET content_format_version = r.content_format_version
FROM content_revision r
WHERE r.installation_id = e.installation_id
  AND r.revision = e.revision;

ALTER TABLE content_snapshot_envelope ALTER COLUMN content_format_version SET NOT NULL;
