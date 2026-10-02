---
phase: 1
slug: phase-1-requirements
title: Requirements, threats and user stories
duration: 2.5 h
start_tag: phase-0-end
end_tag: phase-1-end
---
# Phase 1 · Requirements, threats and user stories

## 1. Why it matters
Most failed projects fail before the first line of code: nobody wrote down what "done" means. For an
API with logins and roles there is a second question that teams skip until it hurts: **what could go
wrong?** A short, testable requirements document with an explicit threat list gives every test a
reason to exist, lets you say *no* to scope creep, and is the first thing an interviewer asks about.
AI assistants are great at drafting requirements, and just as good at inventing requirements nobody
asked for. Your job is to keep the ones you can test.

## 2. Objectives
- Turn a one-paragraph brief into numbered **functional** (FR) and **measurable non-functional** (NFR) requirements.
- Map personas to **roles** (READER, AUTHOR, ADMIN) and write down who may do what.
- List at least five **threats** (abuse cases) and the requirement that answers each.
- Write 8–10 **user stories** with Given/When/Then acceptance criteria and create them as GitHub Issues.
- Write an explicit **out-of-scope** list and a **back-of-the-envelope estimate**.

## 3. Step-by-step instructions

### Step 1.1 · Read the brief and the contract
> *Build DevBlog: the backend of a small multi-author blog for a student tech club. Anyone can read
> published posts, search them and filter by tag. Registered members comment. Authors write Markdown
> posts as drafts and publish when ready. Admins promote members to authors and review comments that
> an AI flagged. It must run on a laptop for free, work without any AI service, and be safe enough that
> a member cannot read drafts, edit someone else's post or make themselves admin.*

Open `spec/openapi.yaml`. The HTTP contract is **given** so the three tracks stay compatible; your
requirements must be consistent with it (status codes, limits, the `problem+json` error format, which
endpoints declare `security: bearerAuth`).

### Step 1.2 · Personas and the permission matrix
Write at least three personas (e.g. *Ravi, a reader on his phone*; *Meera, a second-year student who
writes tutorials*; *Arjun, the club admin*). Then fill a matrix: rows = actions (read published post,
read a draft, create post, edit own post, edit any post, comment, delete own comment, moderate,
change roles), columns = anonymous, READER, AUTHOR, ADMIN. Every "no" in this table becomes a test in
Phase 3. Decide now what a refused request returns: `401` (not logged in), `403` (logged in, not
allowed) or `404` (you should not even learn that it exists, e.g. someone else's draft).

### Step 1.3 · Functional requirements
In `spec/requirements.md` write FR-1 … FR-n as "The system shall …" statements. Each needs a number
or a status code, so a test can check it. Bad: *"Passwords must be secure."* Good: *"Passwords are 8–72
bytes and stored only as bcrypt hashes with cost ≥ 10."* Cover registration, login, refresh, logout,
roles, posts (drafts, publish, edit, delete), the feed (pagination, tags, search), comments,
moderation, summaries and health.

### Step 1.4 · Non-functional requirements and threats
Cover security, performance, availability, privacy, cost and portability. Make each one measurable:
*"access tokens expire after 15 minutes"*, *"the feed answers in < 100 ms p95 with 10 000 posts on a
laptop"*, *"comments can still be posted when the LLM is down"*.

Then the threat list. For each abuse case, name the requirement that stops it:

| Threat | Answer |
|---|---|
| A stolen refresh token is used by an attacker | rotation + reuse detection (FR-3) |
| A new user sends `"role":"ADMIN"` when registering | registration ignores `role` (FR-1) |
| Someone guesses the slug of a draft | drafts are `404` for others (FR-7) |
| Two authors edit the same post and one change silently disappears | optimistic locking (FR-8) |
| Spam floods the comments | moderation holds it for a human (FR-12, FR-13) |

### Step 1.5 · User stories → GitHub Issues
For each story use the **User story** issue template (`.github/ISSUE_TEMPLATE/user_story.yml`). It asks
for the story, the acceptance criteria and the Phase 3 milestone. Label them `user-story`. With the
GitHub CLI:
```bash
gh issue create --template user_story.yml      # or use the web UI: Issues → New issue
```
Write at least one acceptance criterion per story that describes a **refusal** (wrong role, wrong
owner, bad input), not only the happy path.

### Step 1.6 · Out of scope + estimates
List what v1 will **not** do (front end, image upload, e-mail verification, password reset, OAuth,
likes, rate limiting…). Then estimate: authors, posts/day, reads/day, comments/day, read:write ratio,
requests/second at peak (× 10 of average) and storage per year. Write one sentence on what the numbers
mean for the design (spoiler: one PostgreSQL instance is plenty; the hard part is correctness, not scale).

### Step 1.7 · Commit
```bash
git switch -c docs/phase-1-requirements
git add spec/requirements.md AI_LOG.md
git commit -m "docs(spec): requirements, threats and user stories"
git push -u origin docs/phase-1-requirements      # open a PR, wait for CI, merge
```
Compare with the reference: `git diff phase-0-end phase-1-end -- spec/requirements.md`.

## 4. AI-assist prompts

### Prompt 1: Draft requirements from the brief
```text
Here is a product brief: <paste the brief> and an OpenAPI contract: <paste spec/openapi.yaml>.
Draft numbered functional requirements ("The system shall ...") and measurable non-functional
requirements for v1. Every requirement must be testable and consistent with the contract (quote the
status code or limit). Do not add features that are not in the brief or the contract.
```
**Verify the output by:** checking every number and status code against `spec/openapi.yaml`, deleting
anything not in the brief (AI loves adding e-mail verification and OAuth), and asking "which test would
prove this?" for each line. If you cannot name the test, rewrite or drop it.

### Prompt 2: Threat-model the API
```text
Act as a security reviewer. For this API (contract: <paste>) with roles READER, AUTHOR and ADMIN and
JWT access tokens + refresh tokens, list the 8 most likely abuse cases for a small public blog. For
each: the attack in one sentence, the impact, and a concrete, testable countermeasure. Prefer
OWASP API Security Top 10 categories and name them.
```
**Verify the output by:** looking up each named OWASP API Top 10 category on owasp.org (assistants
sometimes misname them), keeping only threats that apply to *this* contract, and making sure each kept
countermeasure appears as an FR or NFR.

### Prompt 3: Turn my stories into Given/When/Then
```text
Here are my user stories: <paste>. For each, write 2-4 acceptance criteria in Given/When/Then form,
including at least one refusal case (wrong role, someone else's resource, invalid input). Use only
status codes from this contract: <paste the relevant paths>.
```
**Verify the output by:** cross-checking every status code with the contract and your permission
matrix. A criterion that says `403` where your matrix says `404` (drafts!) is a design decision you
must make consciously, not accept by default.

## 5. Deliverables
- [ ] `spec/requirements.md` with problem statement, personas + permission matrix, FRs, measurable NFRs,
  threats, out of scope and estimates.
- [ ] 8–10 GitHub Issues created from the *User story* template, each with a refusal criterion.
- [ ] A merged PR and an `AI_LOG.md` entry (what the assistant invented that you removed).

## 6. Self-check quiz

### Q1. Which requirement is testable?
- [ ] "The API should be fast."
- [ ] "Passwords must be secure."
- [x] "An access token expires 900 seconds after it is issued."
- [ ] "The system should handle many users."
> **Answer:** It has a number a test can check with an injected clock. The others need a measurable threshold first.

### Q2. A logged-in READER requests someone else's draft by its slug. What should the API answer?
- [ ] `200` with the draft
- [ ] `401 Unauthorized`
- [ ] `403 Forbidden`
- [x] `404 Not Found`
> **Answer:** `403` would confirm that a post with that slug exists. For resources the caller should not even know about, `404` leaks nothing.

### Q3. Why is "registration ignores a `role` field" a security requirement?
- [ ] It makes the request smaller
- [x] Otherwise a client could register itself as ADMIN (mass assignment)
- [ ] JSON cannot contain upper-case values
- [ ] Roles are stored in another table
> **Answer:** Mass assignment (OWASP API3: Broken Object Property Level Authorization) happens when a server copies client fields straight into a model. Every new account is a READER; only an ADMIN can change roles.

### Q4. Which of these is out of scope for v1 according to the brief?
- [ ] Drafts
- [ ] Comment moderation
- [x] Password reset by e-mail
- [ ] Searching posts
> **Answer:** It needs an e-mail provider and adds a whole token flow. Writing it down as out of scope protects the schedule.

### Q5. Your estimate says ~2 000 reads/day and ~20 writes/day. What does that mean for the design?
- [ ] You need a cache cluster and read replicas
- [x] One PostgreSQL instance is plenty; spend the effort on correctness and security
- [ ] You must use a NoSQL database
- [ ] You need a message queue for writes
> **Answer:** Even at 10× peak that is far below one request per second. Simple, correct and well-tested beats "web scale" here.

### Q6. An AI draft adds "FR-12: users can log in with Google". What do you do?
- [ ] Keep it, more features are better
- [x] Remove it (or move it to out of scope): it is not in the brief or the contract
- [ ] Add it to the OpenAPI contract
- [ ] Ask the AI if it is needed
> **Answer:** Requirements come from the brief and the contract, not from the assistant's idea of a typical app. Log the rejection in `AI_LOG.md`.

## 7. What you learned
- Writing testable FRs and measurable NFRs, consistent with a given API contract.
- A permission matrix and the 401 vs 403 vs 404 decision.
- Threat modelling as part of requirements: every abuse case gets a countermeasure you can test.
- User stories with refusal criteria, tracked as GitHub Issues.
- Using estimates to keep a design simple.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-2 phase-1-end
git show phase-1-end:spec/requirements.md | less     # the reference requirements
git diff phase-0-end phase-1-end --stat
```
