# ADR-0004: AI moderation holds comments for a human; rules are the fallback

* **Status:** accepted
* **Date:** 2026-10-02

## Context
Comments attract spam and abuse. A small model (local Ollama `llama3.2:1b`) or a free hosted model can
classify text, but it will make mistakes, may be slow or unavailable, and the comment text itself can
contain instructions aimed at the model (prompt injection). The course must work offline at no cost.

## Options considered
1. **No moderation**: simple; the blog fills with spam.
2. **LLM decides** (delete or reject automatically): fast, but every false positive silently loses a
   real comment and the outcome depends on a service we do not control.
3. **AI proposes, human decides**: the verdict can only move a comment to `held`; an admin approves or
   rejects. A deterministic rule-based moderator is the baseline and the fallback.

## Decision
Option 3. `held` when `label != ok` and `confidence >= 0.7`; otherwise `visible`. The LLM gets the
comment inside `<comment>` tags with an instruction to treat it as data, and its answer is parsed
**strictly** (JSON object, label in {ok, spam, toxic}, confidence 0..1). Any error, timeout or
unexpected answer falls back to the rules with `fallback: true` (logged as a warning). The verdict,
provider and fallback flag are stored with the comment; the admin's decision is stored next to it.

## Consequences
* A model mistake costs a delay, never a lost comment. Admins see why something was held.
* Prompt injection can at worst flip a label; it cannot make the model take an action, and the strict
  parser rejects free-form answers.
* Every comment waits for the LLM up to `LLM_TIMEOUT_MS` when it is slow or down. Revisit if that
  hurts (it did, in the Phase 7 game day: see the post-mortem and the circuit breaker in v1.1.0).
