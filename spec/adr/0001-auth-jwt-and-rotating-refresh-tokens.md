# ADR-0001: Short-lived JWT access tokens + opaque rotating refresh tokens

* **Status:** accepted
* **Date:** 2026-10-02

## Context
Clients (a web front end, a mobile app, `curl`) must authenticate on every request. We need logout,
a way to cut off a stolen session, and no per-request database lookup for the common case. One
PostgreSQL instance, no Redis (requirements §8).

## Options considered
1. **Server-side sessions** (random id in a cookie, row in a `sessions` table): simple revocation, but a
   DB lookup per request and CSRF protection needed for cookies; awkward for non-browser clients.
2. **Long-lived JWT only** (e.g. 7 days): no DB lookup, but a stolen token works until it expires and
   logout is impossible without a deny-list (which brings the DB lookup back).
3. **Short JWT (15 min) + opaque refresh token (7 days) that rotates on every use**, stored only as a
   SHA-256 hash, with **reuse detection**: if a revoked refresh token is presented, revoke all of the
   user's refresh tokens.

## Decision
Option 3. Access token: HS256, claims `sub`, `role`, `iss=devblog`, `iat`, `exp`; algorithm pinned on
verification; secret ≥ 32 bytes from `JWT_SECRET`. Refresh token: 32 bytes from a CSPRNG, base64url;
revoked with a compare-and-set `UPDATE … WHERE revoked_at IS NULL` so concurrent refreshes cannot both
succeed. Each refresh re-reads the user, so a role change takes effect within 15 minutes at most.

## Consequences
* Requests need no DB call to authenticate; logout and theft response work through the refresh token.
* A stolen **access** token stays valid for up to 15 minutes. Accepted; that is why it is short.
* Reuse detection logs out the legitimate user too when a token is stolen. Accepted: safe by default.
* Sessions (option 1) are genuinely simpler for a browser-only app with cookies. Revisit if DevBlog ever
  ships its own server-rendered front end.
* Expired/revoked rows accumulate; a periodic purge is a known follow-up.
