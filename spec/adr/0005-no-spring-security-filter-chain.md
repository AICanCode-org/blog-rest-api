# ADR-0005: Explicit authorisation in the service layer (no Spring Security filter chain)

* **Status:** accepted
* **Date:** 2026-10-02

## Context
The three tracks must behave identically and be easy to compare. Authorisation rules here depend on
data (own post vs someone else's, draft vs published), not only on the URL. Spring Security's filter
chain is the standard in Java, but it hides a lot of behaviour behind configuration, and Python
(FastAPI) and TypeScript (Fastify) have no equivalent.

## Options considered
1. **Spring Security filter chain** (Java) + framework plugins in the other tracks: idiomatic per
   ecosystem, but three different mental models, and data-dependent rules end up in the services anyway.
2. **Explicit checks in the services in every track**: the HTTP layer only extracts the bearer token and
   calls `authenticate`/`optional`; services call `requireRole` and the ownership rules (`canSee`,
   `canEdit`). Java uses only `spring-security-crypto` for BCrypt and Nimbus JOSE+JWT for tokens.

## Decision
Option 2. All three tracks share one design, and the unit tests prove every refusal (401/403/404)
without starting a web server.

## Consequences
* Every rule is visible in plain code and covered by unit tests.
* Each new endpoint must remember to authenticate. The API tests call protected routes without a token
  and with the wrong role, so a forgotten check fails CI; a new route needs the same tests.
* In a real Java team you would usually adopt Spring Security for its ecosystem (OAuth2 resource
  server, method security, CSRF for cookies). Doing it by hand first is how you understand what it does.
