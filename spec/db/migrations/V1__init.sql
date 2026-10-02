-- DevBlog V1 schema (Phase 2). Shared by the Java, Python and TypeScript tracks.
-- Never edit this file after it is merged: add V2__... instead.

CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,           -- bcrypt, never the password itself
    display_name  VARCHAR(50)  NOT NULL,
    role          VARCHAR(10)  NOT NULL DEFAULT 'READER',
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_users_role CHECK (role IN ('READER', 'AUTHOR', 'ADMIN'))
);

-- Refresh tokens are opaque random strings; only their SHA-256 hash is stored.
CREATE TABLE refresh_tokens (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash CHAR(64)    NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE posts (
    id           BIGSERIAL    PRIMARY KEY,
    slug         VARCHAR(100) NOT NULL,
    author_id    BIGINT       NOT NULL REFERENCES users (id),
    title        VARCHAR(200) NOT NULL,
    body         TEXT         NOT NULL,
    status       VARCHAR(10)  NOT NULL DEFAULT 'draft',
    version      INTEGER      NOT NULL DEFAULT 1,  -- optimistic locking (ADR-0003)
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    deleted_at   TIMESTAMPTZ,                      -- soft delete: user content is never hard-deleted
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', title || ' ' || body)) STORED,
    CONSTRAINT uq_posts_slug UNIQUE (slug),
    CONSTRAINT ck_posts_status CHECK (status IN ('draft', 'published')),
    CONSTRAINT ck_posts_body_length CHECK (char_length(body) <= 50000),
    CONSTRAINT ck_posts_published_at CHECK (status = 'draft' OR published_at IS NOT NULL)
);
-- The public feed: published, not deleted, newest first. Matches the keyset query exactly (ADR-0002).
CREATE INDEX idx_posts_feed ON posts (published_at DESC, id DESC)
    WHERE status = 'published' AND deleted_at IS NULL;
CREATE INDEX idx_posts_author ON posts (author_id);
CREATE INDEX idx_posts_search ON posts USING GIN (search_vector);

CREATE TABLE tags (
    id   BIGSERIAL   PRIMARY KEY,
    name VARCHAR(30) NOT NULL,
    CONSTRAINT uq_tags_name UNIQUE (name)
);

CREATE TABLE post_tags (
    post_id BIGINT NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    tag_id  BIGINT NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, tag_id)
);
CREATE INDEX idx_post_tags_tag ON post_tags (tag_id);

CREATE TABLE comments (
    id                    BIGSERIAL   PRIMARY KEY,
    post_id               BIGINT      NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    author_id             BIGINT      NOT NULL REFERENCES users (id),
    parent_id             BIGINT      REFERENCES comments (id),
    body                  TEXT        NOT NULL,
    status                VARCHAR(10) NOT NULL,
    moderation_label      VARCHAR(10) NOT NULL,
    moderation_confidence NUMERIC(3,2) NOT NULL,
    moderation_provider   VARCHAR(10) NOT NULL,
    moderation_fallback   BOOLEAN     NOT NULL DEFAULT FALSE,
    moderation_reason     VARCHAR(200) NOT NULL,
    moderated_by          BIGINT      REFERENCES users (id),   -- the human who overrode the AI, if any
    moderated_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL,
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT ck_comments_status CHECK (status IN ('visible', 'held', 'rejected')),
    CONSTRAINT ck_comments_label CHECK (moderation_label IN ('ok', 'spam', 'toxic')),
    CONSTRAINT ck_comments_confidence CHECK (moderation_confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_comments_body_length CHECK (char_length(body) BETWEEN 1 AND 2000)
);
CREATE INDEX idx_comments_post ON comments (post_id, created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX idx_comments_status ON comments (status, created_at) WHERE deleted_at IS NULL;
