import { describe } from "vitest";

// ---------------------------------------------------------------------------
// PENDING topics describe behaviour you have not built yet, so their tests are
// skipped (vitest reports them as "skipped"). When you start a milestone, delete
// its line here, watch the tests fail (red), implement until they pass (green).
// See docs/phase-3.
// ---------------------------------------------------------------------------
export const PENDING = new Set<string>([
  "validation", // Phase 3 M1 - validation
  "text", // Phase 3 M1 - slugs, excerpts, cursors
  "security", // Phase 3 M2 - passwords and tokens
  "auth", // Phase 3 M2 - AuthService
  "api-auth", // Phase 3 M2 - auth endpoints
  "posts", // Phase 3 M3 - PostService
  "api-posts", // Phase 3 M3 - post endpoints
  "feed", // Phase 3 M4 - feed, search, tags
  "moderation", // Phase 3 M5 - AI moderation
  "summaries", // Phase 3 M5 - AI summaries
  "comments", // Phase 3 M5 - CommentService
  "api-comments", // Phase 3 M5 - comment endpoints
  "integration", // Phase 4 - integration tests
]);

/** `const describe = pendingDescribe("codes")` - a describe that is skipped while the topic is pending. */
export const pendingDescribe = (topic: string) => describe.skipIf(PENDING.has(topic));
