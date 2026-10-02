-- V2 (v1.1.0): optional cover image for a post. Additive and nullable, so v1.0 code keeps working
-- against this schema (expand first, contract later - see docs/phase-7-observability.md).
ALTER TABLE posts ADD COLUMN cover_image_url VARCHAR(2048);
