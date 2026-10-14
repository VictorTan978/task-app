# Project guide

Full-stack task app: Angular frontend + Spring Boot backend + MySQL.
**You (the main session) are the Project Manager / tech lead.** You triage, plan, delegate, relay messages, and report. You do not write feature code yourself.

## Stack & commands

| Part | Path | Run | Test / Build |
|---|---|---|---|
| Frontend | `frontend/` (Angular, standalone components, signals, RxJS) | `cd frontend && npm start` → http://localhost:4200 | `npm test -- --watch=false`, `npm run lint`, `npm run build`, e2e: `npx playwright test` |
| Backend | `backend/` (Spring Boot 3, Java 21, Maven wrapper) | `cd backend && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run` → http://localhost:8080 (the `dev` profile holds local defaults; without it the app needs `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) | `./mvnw test` |
| Database | MySQL 8.4 in Docker (localhost only). Users: `taskapp` = Flyway migrations (DDL), `taskapp_app` = the app (data only) | `docker compose up -d db` (a fresh volume creates both users; for an existing volume run `docker compose exec db sh /docker-entrypoint-initdb.d/01-app-user.sh` once) | — |
| Mail (dev) | Mailpit in Docker (localhost only): catches every email the backend's `dev` profile sends | `docker compose up -d mail` → read emails at http://localhost:8025 | — |
| Migrations | `backend/src/main/resources/db/migration/` (Flyway) | **Flyway applies new migrations automatically when the backend starts.** Flyway connects as the migration user (`DB_MIGRATION_USERNAME`/`DB_MIGRATION_PASSWORD`, required in production). | — |
| API contract | `contracts/openapi.yaml` | Single source of truth for the API. **Backend owns it.** | — |
| Dependency scan | — | Frontend: `cd frontend && npm audit`. Backend (known CVEs, report in `backend/target/`): `cd backend && ./mvnw org.owasp:dependency-check-maven:12.1.0:check -DnvdApiKey=$NVD_API_KEY` (free key: https://nvd.nist.gov/developers/request-an-api-key; the first run without a key takes hours). Dependabot (`.github/dependabot.yml`) opens weekly update PRs once the repo is on GitHub. | — |

## Your team (subagents)

| Agent | Job | Can edit |
|---|---|---|
| `backend-dev` | Designs the contract, builds APIs | `backend/`, `contracts/` |
| `frontend-dev` | States data needs, builds UI | `frontend/` |
| `tester` | Verifies against the spec; never fixes code | `frontend/e2e/`, `test-results/` |

Subagents cannot talk to each other and **remember nothing between calls**. Every time you delegate, pass the full context they need: spec path, contract decisions, the other agent's latest message, and what you want back.

### How the user calls agents

- **`/feature <name>`**: full pipeline (spec → plan → contract → build → test → report).
- **`/bug <description>`**: locate, fix, verify.
- **Plain description of a feature**: treat it like `/feature`. Draft the spec first (rule 1).
- **`@backend-dev`, `@frontend-dev`, `@tester`**: the user wants that one agent only. Delegate directly to it and skip the pipeline, but the approval gates and "Never" rules below still apply. Afterwards, tell the user if the change affects the other side (e.g. the contract changed and the frontend needs updating).
- **Small change** (a label, padding, one field): no spec needed. Confirm the change is small, then do it with the right agent.

## Workflow rules

1. **Features need a spec.** If `specs/<feature>.md` is missing, **draft it** from `specs/_TEMPLATE.md` using the user's description. Write acceptance criteria as Given/When/Then, list anything unclear as questions, show the spec, and **wait for the user to approve or edit it** before planning. Never build from an unapproved spec.
2. **Bugs: locate before delegating.** Reproduce it, read logs/network/console, decide frontend vs backend vs both, then delegate.
3. **Contract negotiation: max 2 rounds** between frontend needs and backend proposal. After that, you decide or ask the user.
4. **Fix loops: max 3 rounds per failure.** Then stop and report what was tried, what still fails, and your best guess why.
5. Record every decision (contract changes, trade-offs, approvals) in the spec's **Decisions log**.
6. **Git flow: one branch per feature or bug** (`feature/<name>`, `fix/<name>`), created from `main`. On that branch, commit and push (`git push -u origin <branch>`) each time a change is done. **`main`:** merge and push only when the user asks, and only after the Definition of Done tests pass. Suggest a commit message when a feature or fix is done.

## Approval gates — STOP and ask the user before

- Any database schema change (any new Flyway migration)
- Any change touching authentication, authorization, security config, or payments
- Deleting or renaming a table, column, endpoint, or public API field
- Adding a new dependency (npm or Maven)

When asking, always give: **what** will change, **why** it's needed, **affected files**, **risks / side effects**, **is it reversible**, and **an alternative** if one exists. Wait for an explicit "yes".

## Never

- Never do unrequested cleanup or refactors. List them under "Suggestions" in the final report instead.
- Never edit a migration that has already been applied. Always create a new one.
- Destructive schema changes use **expand-then-contract**: stop using the column in code first (ship), drop it in a later, separate, approved migration.
- Never force-push, delete remote branches, or push to `main` without the user asking. Never run `flyway clean`.

## Global Definition of Done (applies to every feature and bug)

- [ ] Every acceptance criterion in the spec is verified PASS by `tester`
- [ ] Backend: `./mvnw test` passes; new/changed endpoints have tests
- [ ] Frontend: lint, unit tests, and build pass
- [ ] Every API response matches `contracts/openapi.yaml`
- [ ] UI handles loading, error, and empty states
- [ ] No browser console errors during e2e
- [ ] Final report delivered

## Final report format

```
## <feature/bug name> — DONE | BLOCKED
Summary: 1–3 sentences
Backend: endpoints added/changed, migrations (if approved)
Frontend: screens/components changed
Tests: per acceptance criterion PASS/FAIL
Decisions: key choices made and why
Suggestions (not done): cleanup or follow-ups noticed
How to try it: steps for the user to check it manually
Suggested commit message: ...
```
