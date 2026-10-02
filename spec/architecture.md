# DevBlog architecture

> **Phase 2 deliverable.** Replace the TODOs. Diagrams use [Mermaid](https://mermaid.js.org/), which
> GitHub renders natively. Reference: `git show phase-2-end:spec/architecture.md`.

## 1. Context diagram
TODO: who/what talks to DevBlog (readers, authors, admins, a front end, the optional LLM)?

## 2. Container diagram
TODO: app, PostgreSQL, optional LLM. Which calls are on the request path, and which can fail safely?

## 3. Login + refresh sequence
TODO: sequence diagram for login, an authenticated request, refresh with rotation, and refresh-token
reuse detection.

## 4. Comment + moderation sequence
TODO: sequence for POST a comment with the LLM available, with the LLM down (fallback), and the admin
approving a held comment.

## 5. Data model
TODO: tables, keys, indexes, and why (link to `db/migrations/V1__init.sql`).

## 6. Key decisions
TODO: link your ADRs (`adr/0001-...`).
