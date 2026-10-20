# Next session: T4 — Due times, reminders, notifications, recurring tasks

Written 2026-10-20 after the maintenance pass. Start a new Claude Code session in `/Users/victor/task-app` and paste the prompt below.

## Prompt to paste

```
Read docs/next-session-T4-due-times-reminders.md and CLAUDE.md. You are the PM.
Run `git checkout feature/t4-reminders` (already created from main and pushed).
Draft specs/due-times-reminders.md from specs/_TEMPLATE.md for T4 (due times, reminders, notifications,
recurring tasks), using the "Open questions" in the handoff doc. Propose a T4a/T4b split if it is too big for one
part. Show me the spec and wait for my answers and approval (rule 1), using the CLAUDE.md options format
(2–4 options, the recommended option first, pros/cons).
```

---

## Where the project stands

| Part | Status |
|---|---|
| A1–A5 (account, sessions, email auth, Google sign-in, roles/admin) | DONE, on `main` |
| T1 priority/status/tags, T2 search/sort/paging | DONE, on `main` |
| T3a lists, T3b sharing (`specs/sharing-team-lists.md`) | DONE, on `main` (`11aae94`, `bad15f8`) |
| Maintenance 2026-10-20: lazy-loaded routes (bundle 549 → 476 kB, under the 500 kB budget), angular-eslint 22.6.0, typescript-eslint 8.71.1, prettier 3.9.10, Maven 3.10.0 | DONE, on `main` (`99bfd7c`) |
| e2e AC6 flake fix (`lists.spec.ts` polls titles after deep links) | DONE, on `main` (`2c81df2`) |
| **Spring Boot 3.5.16 → 4.1.1**, springdoc 3.1.1, **Jackson 3** (`tools.jackson`) | DONE, on `main` (`24c5f65`). Behaviour identical, contract unchanged |
| **T4 due times, reminders, notifications, recurring tasks** | **next — branch `feature/t4-reminders`** |
| T5 undo, trash, drag and drop, bulk actions | later |

**Test baseline on `main` (`24c5f65`):** backend 1179 tests; frontend lint clean, 882 unit tests, build clean (initial bundle 475.77 kB). Full e2e: **304 tests** = 285 passed + 18 skipped (env-gated) + 1 known flake that is now fixed. The T1 Tags AC10 test (`tasks-priority-status-tags.spec.ts:673`) flaked once under load, so watch it. Contract `contracts/openapi.yaml` is at **0.13.0**. **Next migration is V12.**

## Things the build must know (Boot 4)

- Use Jackson 3 imports (`tools.jackson.databind.*`). Annotations stay in `com.fasterxml.jackson.annotation`. Do not add `spring-boot-jackson2`.
- `application.yml` keeps `spring.jackson.mapper.sort-properties-alphabetically: false` so JSON keeps declaration order. Error bodies set `type: about:blank` in `Problems.of(...)`.
- Test slices moved: `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, and so on.
- No `@Scheduled` / `@EnableScheduling` exists yet. A scheduler for reminders is new. Spring's built-in scheduling needs no new dependency. Anything like Quartz or ShedLock is a dependency gate.
- Mail goes through `JavaMailSender`. In dev it is caught by Mailpit (http://localhost:8025).

## Today's due date

`Task.dueDate` is a **date only** (`'2026-10-10'`). "Overdue" means not Done and the date is before today. Sort "Due date" puts the soonest first and tasks with no date last (`specs/tasks-priority-status-tags.md`, `specs/tasks-search-sort-pagination.md`). Shared lists exist, and every member sees every task in the list.

## Open questions for the T4 spec (PM recommendations in bold)

1. **Split?** **T4a = due time + reminders + notifications; T4b = recurring tasks.** Recurring touches completion and paging, so it is easier to test separately.
2. **Due time:** keep `dueDate` and add an optional `dueTime` (local time), or replace both with one `dueAt` instant? **Add an optional `dueTime`.** It leaves today's date-only tasks and sorts untouched; that is an additive contract change. It needs a **user time zone** (see 3).
3. **Time zone:** **store an IANA zone on the user. The browser sets it on sign-in, and the user can change it in Account.** It is needed so "9:00" and "overdue" mean the user's local time and the scheduler can fire at the right moment. This is a migration (gate).
4. **Reminder options:** **none, at due time, 10 min / 1 h / 1 day before; one reminder per task.** Only for tasks with a due date (date-only tasks remind at 09:00 local time?).
5. **Who gets reminded on shared lists?** **The person who set the reminder only.** That means reminders are per user per task, not per task. Alternative: all members, which is noisy.
6. **Channel:** **email (via the existing mail setup) + in-app notifications (a bell with a count, like the Invitations badge, and a `/notifications` page).** Browser push / service workers would be later; that is a dependency and security gate.
7. **Email opt-out:** **a "Reminder emails" on/off toggle in Account, default on**, plus a per-account hourly cap like the invite emails (`RATE_LIMIT_*`).
8. **Scheduler:** **Spring `@Scheduled` every minute, picking due reminders with `SELECT … FOR UPDATE SKIP LOCKED` so two instances never send twice; mark them sent in the same transaction.** No new dependency.
9. **Missed reminders** (server down): **send late if less than 1 hour late, otherwise mark as skipped.**
10. **Done / deleted / moved / list left:** **no reminder for Done or deleted tasks, and none if the user lost access to the list.**
11. **Recurring (T4b):** **daily / weekly (pick weekdays) / monthly (same day; the 31st → last day) / yearly; when you complete one, the next copy is created with the next due date (title, description, priority, tags, list, reminder offset are copied).** "Stop repeating" is in the edit dialog.
12. **Notifications retention:** **keep 90 days, newest first, "Mark all read".**

Approval gates expected:
- migration V12 (user time zone, `due_time`, reminders and notifications tables)
- possibly V13 (recurrence)
- a scheduler that sends emails (security / abuse limits)
- any new dependency

## How to run the full e2e suite

The user often has their own backend (8080) and `ng serve` (4200) running. **Don't stop them.** The tester runs its own stack:
- Docker Desktop running; `docker compose up -d db mail oidc`. From a git worktree, set `COMPOSE_PROJECT_NAME=task-app` or the DB helpers miss the container.
- Backend on 8081: `SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8081 APP_BASE_URL=http://localhost:4201`. Set these to 1000000:
  - `RATE_LIMIT_{REGISTER,FORGOT_PASSWORD,RESET_PASSWORD,CHECK_RESET_TOKEN,VERIFY_EMAIL}_PER_IP_PER_HOUR`
  - `RATE_LIMIT_{LOGIN,GOOGLE_START,GOOGLE_CALLBACK}_PER_IP_PER_MINUTE`
  - `RATE_LIMIT_ADMIN_ACTIONS_PER_USER_PER_MINUTE`
  - `RATE_LIMIT_INVITES_PER_USER_PER_HOUR`
  - `RATE_LIMIT_INVITE_EMAILS_PER_ACCOUNT_PER_HOUR`
- Run `ng serve --port 4201` with a proxy sending `/api` to 8081. Use a copied Playwright config (baseURL 4201, absolute testDir) with `E2E_BASE_URL=http://localhost:4201`. Set `E2E_BACKEND_LOG`/`BACKEND_LOG` to the 8081 log, or the audit tests skip. Never edit `frontend/playwright.config.ts`.
- Backend tests: `./mvnw clean test`, with Docker running. Never run Maven and e2e at the same time.

## How the user wants us to work

- Follow `CLAUDE.md`. Gates and questions use the **options format**: 2–4 options, the recommended one first, with pros/cons.
- Model routing: Opus plans and reviews; `backend-dev`, `frontend-dev` and `tester` run on Sonnet; the `researcher` agent locates files.
- **No pause before build:** once the spec is approved and the contract agreed, build straight away. Stop only for approval gates or real questions.
- **Git:** one branch per part. Commit and push after each change. Merge to `main` only when the user says "merge", and only after a green full rerun. Never force-push. The user may have their own branch checked out in `/Users/victor/task-app`; use a git worktree rather than switching their branch.

## Loose ends (suggestions, not scheduled)

- Five more one-shot `titles()` reads in `lists.spec.ts` (lines ~670, 748, 761, 774, 994) could flake like AC6 did.
- No IPv6 trusted-proxy test exists. `AdminBootstrapTest` uses a deprecated API. Surefire logged "kill self fork JVM … after System.exit(0)" once without failing.
- `maven-wrapper.properties` has no `distributionSha256Sum`. The build runs on Java 25 while the target is 21 (confirm). The Maven 401 warnings come from the flyway-community and MavenFeed metadata repos.
- Dependabot: the 5 Angular 22.2.2 branches are obsolete (main has it). Hold TypeScript 7 until Angular supports it. The Boot 4.1.1 and springdoc 3.1.1 PRs are superseded.
- Merged local/remote branches can be deleted (only if the user asks). Scratchpad worktrees `wt-ac6`, `wt-boot4` and `wt-t4` belong to the 2026-10-20 session.
- The user's branch `fix/approval-format` (the new gate format in CLAUDE.md) is waiting for the user's own commit and their "merge".
