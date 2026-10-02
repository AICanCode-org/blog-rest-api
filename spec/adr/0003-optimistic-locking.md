# ADR-0003: Optimistic locking with a version column

* **Status:** accepted
* **Date:** 2026-10-02

## Context
Two authors (or an author and an admin fixing a typo) can edit the same post. Editing takes minutes;
saving takes milliseconds. A lost update silently destroys someone's work (threat T7).

## Options considered
1. **Last write wins**: simplest; loses data.
2. **Pessimistic lock** (`SELECT … FOR UPDATE` when the editor opens the post): impossible to hold
   across HTTP requests without lock tables, timeouts and "who locked it?" support tickets.
3. **Optimistic locking**: a `version` column; clients send the version they loaded; the update is
   `… SET version = version + 1 WHERE id = ? AND version = ?`.

## Decision
Option 3. `version` is required on `PATCH`. Zero updated rows means someone else saved first: the API
returns `409 version-conflict` and states the current version, so the client can reload and merge.
The check happens in the same SQL statement as the update, so there is no race between check and write.

## Consequences
* No lost updates, no held locks, one extra integer per row.
* Clients must handle `409`. The contract documents it; the smoke test exercises it.
* Soft-deleted posts also fail the update (`AND deleted_at IS NULL`).
