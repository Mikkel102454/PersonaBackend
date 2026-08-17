CREATE TABLE server_installation (
    id uuid PRIMARY KEY,
    public_key bytea NOT NULL,
    created_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    CHECK (octet_length(public_key) BETWEEN 32 AND 256)
);

CREATE TABLE editor_session (
    id uuid PRIMARY KEY,
    installation_id uuid NOT NULL REFERENCES server_installation(id),
    initiator_id varchar(128) NOT NULL,
    initiator_name varchar(160) NOT NULL,
    content_scope varchar(32) NOT NULL,
    restrictions jsonb NOT NULL,
    requested_capabilities jsonb NOT NULL,
    browser_verified boolean NOT NULL DEFAULT false,
    browser_description varchar(160),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX editor_session_installation_expiry_idx ON editor_session(installation_id, expires_at DESC);

CREATE TABLE browser_identity (
    session_id uuid PRIMARY KEY REFERENCES editor_session(id) ON DELETE CASCADE,
    public_key bytea NOT NULL,
    bound_at timestamptz NOT NULL,
    CHECK (octet_length(public_key) BETWEEN 32 AND 256)
);

CREATE TABLE capability_grant (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES editor_session(id) ON DELETE CASCADE,
    capability varchar(64) NOT NULL,
    granted_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX capability_grant_active_idx ON capability_grant(session_id, capability) WHERE revoked_at IS NULL;

CREATE TABLE content_revision (
    installation_id uuid NOT NULL REFERENCES server_installation(id),
    revision char(64) NOT NULL,
    content_format_version integer NOT NULL,
    created_at timestamptz NOT NULL,
    source_session_id uuid REFERENCES editor_session(id),
    signature text NOT NULL,
    PRIMARY KEY (installation_id, revision)
);
CREATE INDEX content_revision_latest_idx ON content_revision(installation_id, created_at DESC);

CREATE TABLE content_revision_file (
    installation_id uuid NOT NULL,
    revision char(64) NOT NULL,
    path varchar(512) NOT NULL,
    sha256 char(64) NOT NULL,
    content text NOT NULL,
    PRIMARY KEY (installation_id, revision, path),
    FOREIGN KEY (installation_id, revision) REFERENCES content_revision(installation_id, revision) ON DELETE CASCADE
);

CREATE TABLE draft (
    id uuid PRIMARY KEY,
    installation_id uuid NOT NULL REFERENCES server_installation(id),
    session_id uuid NOT NULL REFERENCES editor_session(id),
    author_id varchar(128) NOT NULL,
    author_name varchar(160) NOT NULL,
    base_revision char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX draft_session_updated_idx ON draft(session_id, updated_at DESC);
CREATE INDEX draft_installation_base_idx ON draft(installation_id, base_revision);

CREATE TABLE draft_file (
    draft_id uuid NOT NULL REFERENCES draft(id) ON DELETE CASCADE,
    path varchar(512) NOT NULL,
    sha256 char(64) NOT NULL,
    content text NOT NULL,
    PRIMARY KEY (draft_id, path)
);

CREATE TABLE publish_request (
    id uuid PRIMARY KEY,
    installation_id uuid NOT NULL REFERENCES server_installation(id),
    session_id uuid NOT NULL REFERENCES editor_session(id),
    draft_id uuid REFERENCES draft(id),
    base_revision char(64) NOT NULL,
    proposed_revision char(64) NOT NULL,
    status varchar(32) NOT NULL,
    requested_at timestamptz NOT NULL,
    completed_at timestamptz,
    validation_result jsonb,
    semantic_diff jsonb,
    rollback_revision char(64)
);
CREATE INDEX publish_request_installation_time_idx ON publish_request(installation_id, requested_at DESC);

CREATE TABLE subscription_definition (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES editor_session(id) ON DELETE CASCADE,
    subscription_type varchar(64) NOT NULL,
    filters jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX subscription_session_expiry_idx ON subscription_definition(session_id, expires_at);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    installation_id uuid,
    session_id uuid,
    actor_type varchar(32) NOT NULL,
    actor_id varchar(160) NOT NULL,
    event_type varchar(64) NOT NULL,
    outcome varchar(32) NOT NULL,
    occurred_at timestamptz NOT NULL,
    details jsonb NOT NULL,
    correlation_id varchar(128)
);
CREATE INDEX audit_event_installation_time_idx ON audit_event(installation_id, occurred_at DESC);
CREATE INDEX audit_event_session_time_idx ON audit_event(session_id, occurred_at DESC);
CREATE INDEX audit_event_type_time_idx ON audit_event(event_type, occurred_at DESC);
