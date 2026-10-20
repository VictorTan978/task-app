# Feature: Due times, reminders, notifications, recurring tasks (T4)

**Status:** APPROVED 2026-10-20. **T4a DONE** 2026-10-20 (AC1–AC26 PASS, merged to `main`). **T4b DONE** 2026-10-20 on `feature/t4-recurring` (not merged; AC27–AC33 PASS). **Depends on:** `specs/tasks.md`, `specs/tasks-priority-status-tags.md`, `specs/tasks-search-sort-pagination.md`, `specs/sharing-team-lists.md`, `specs/account-settings.md` (all DONE, on `main`).

**Approving this spec does not approve the gates.** Migration V12 (and V13), the email-sending scheduler, and any new dependency each get their own approval question at build time.

**Proposed split (Q1).** Each part has its own branch, contract version, migration, tests and merge:

| Part | What | Branch | Contract | Migration |
|---|---|---|---|---|
| **T4a: Due times + reminders** | Account time zone, optional due time, one reminder per user per task, scheduler, reminder email, in-app notifications (bell + `/notifications`) | `feature/t4-reminders` (exists) | 0.14.0 | V12 |
| **T4b: Recurring tasks** | Repeat rules, next copy on completion, "Stop repeating" | `feature/t4-recurring` | 0.15.0 | V13 |

## Why
Today a task only has a date. People forget things that are due at a set time, and tasks that come back every week have to be retyped by hand.

## User story
As a signed-in user, I want to give a task a due time and get reminded before it, and to have repeating tasks recreate themselves, so that I don't miss things and don't retype routine work.

---

## Part A — T4a: Due times, reminders, notifications

### Time zone
- [x] AC1: Given a user without a stored time zone (every existing user after V12, and every new account), when they sign in (password or Google), then the browser's IANA zone (e.g. `Europe/Bucharest`) is saved on their account. It is never overwritten on later sign-ins (Q3).
- [x] AC2: Given Account settings, then a "Time zone" field shows the stored zone and lets me pick any IANA zone. Saving changes when my due times count as overdue and when my reminders fire. An unknown zone is refused with a field error. If the browser's zone differs from the stored one, a hint offers "Use <browser zone>" (Q3).

### Due time
- [x] AC3: Given the edit dialog, when a task has a due date, then I can add an optional due time (`HH:mm`, 24-hour or the browser's locale format). Without a due date the time field is disabled. Clearing the date clears the time. Quick-add is unchanged (no time).
- [x] AC4: Given a task with a due date and time, then the row shows date and time (e.g. "Oct 21, 09:30") in my account's zone. A date-only task looks exactly as today.
- [x] AC5: Given "Overdue" (not Done and past due), then a date-only task is overdue when its date is before today, and a timed task is overdue when its date + time has passed, both in my account's zone (Q6). The badge updates within a minute without a reload.
- [x] AC6: Given sort "Due date", then tasks order by date, then within a date timed tasks by time first and date-only tasks after them, then tasks without a date last; ties keep today's order. "Load more" never skips or repeats a task. Cursors issued before T4a may be rejected with the existing `INVALID_CURSOR` (the list reloads from page 1) (Q7).
- [x] AC7: Given a shared list, then any member who can edit the task (Owner or Member, as in T3b) can set or clear its due time. Every member sees the same date and time (a wall-clock value, like today's date-only `dueDate`): 09:30 means 09:30 in each member's own zone, for display, overdue and their own reminder.

### Reminders
- [x] AC8: Given a task with a due date, when I open the edit dialog, then I can pick **my** reminder: timed task: None / At due time / 10 min before / 1 h before / 1 day before. Date-only task: None / On the day at 09:00 / 1 day before at 09:00 (Q4, Q8). Without a due date the picker is disabled. One reminder per user per task.
- [x] AC9: Given a shared list, when I set a reminder on a task, then only I get it; other members don't see or get my reminder (Q5). Each member can set their own. The row shows a small bell when **I** have a reminder on that task.
- [x] AC10: Given a reminder, when its fire time (due date + time − offset, in my zone) arrives, then within about a minute I get an in-app notification and, if allowed (AC14), an email. It fires once.
- [x] AC11: Given a reminder, when I change the task's due date or time, the reminder offset, or my time zone, then the fire time is recomputed. A reminder that already fired fires again only if the new fire time is in the future.
- [x] AC12: Given a reminder whose fire time is already in the past when I save it, then it is saved but never fires, and the dialog says "This reminder time has passed" (Q9).
- [x] AC13: Given a reminder, then it does not fire if at fire time the task is Done, deleted, or I am no longer a member of its list (left, removed, task moved to a list I'm not in). Reopening a Done task re-arms a future reminder. Deleting my account deletes my reminders and notifications (Q10).

### Email
- [x] AC14: Given a reminder fires, then an email is sent only if: my account is active (not disabled), my email is verified, "Reminder emails" is on in Account (default on), and I'm under the hourly cap (`RATE_LIMIT_REMINDER_EMAILS_PER_ACCOUNT_PER_HOUR`, default 20, silent). The in-app notification is created in every case (Q11, Q12).
- [x] AC15: Given a reminder email, then it contains the task title (HTML-escaped), the due date/time in my zone, the list name, a link to the list (`/tasks` for "My tasks", else `/lists/<id>`), and a line on how to turn reminder emails off. It never contains the description. In dev it arrives in Mailpit.
- [x] AC16: Given Account settings, then a "Reminder emails" on/off toggle saves immediately and is respected from the next reminder.

### Notifications
- [x] AC17: Given I have unread notifications, then the header shows a bell with the unread count (like the Invitations badge); no badge at 0. The count refreshes on navigation and every 60 s while the tab is visible (Q13).
- [x] AC18: Given `/notifications`, then I see my notifications newest first ("Reminder: <title> — due <date time>", time received, unread marked). Clicking one marks it read and opens the task's list. If the task is gone or I lost access, it says "This task is no longer available". "Mark all read" clears the count. Paging uses "Load more". Notifications older than 90 days are deleted (Q14).
- [x] AC19: Given users A and B, then A never sees B's reminders or notifications in any response; another user's notification id answers 404.

### Scheduler, reliability, limits
- [x] AC20: Given two backend instances run against one database, then no reminder fires twice (tester runs two instances or a stress script). Claiming uses `SELECT … FOR UPDATE SKIP LOCKED` in a short transaction; the email is sent outside the row lock (Q15).
- [x] AC21: Given the server was down at a reminder's fire time, when it starts again, then a reminder less than 1 hour late fires (email says it's late), and an older one is marked skipped with no email or notification (Q16).
- [x] AC22: Given SMTP fails, then the notification still exists, the email is retried up to 3 times on later ticks, then marked failed and logged. No 500s anywhere, and other reminders keep going.
- [x] AC23: Given DST, then a due time in a spring-forward gap moves to the first valid time after it, and a time in a fall-back overlap uses the earlier one. Reminders fire at the matching instant.
- [x] AC24: Concurrency: editing or deleting a task, leaving a list, or changing the zone while its reminder is being claimed never returns 500 and never sends for a task deleted before the claim. (A delete committed milliseconds after the claim may still be followed by one email: sending happens outside the lock.)

### Compatibility, states
- [x] AC25: Every existing AC in `specs/tasks*.md` and `specs/sharing-team-lists.md` still passes. API clients that never send `dueTime` or a reminder behave as today (additive contract change).
- [x] AC26: The time field, reminder picker, Account fields, bell and `/notifications` show loading, error (with Retry where it fits) and empty ("No notifications yet") states. No browser console errors in normal use.

---

## Part B — T4b: Recurring tasks

- [x] AC27: Given a task with a due date, when I set "Repeat" in the edit dialog, then I can pick: Never / Daily / Weekly (pick one or more weekdays) / Monthly (same day; the 29th–31st fall back to the month's last day) / Yearly (Feb 29 → Feb 28). Without a due date "Repeat" is disabled. The row shows a repeat icon.
- [x] AC28: Given a repeating task, when I complete it (checkbox or status Done), then a new task is created in the same list with the next due date, the same due time, title, description, priority and tags, and status To do. Each member's reminder on the old task is copied to the new one if they are still members. The completed task stays Done (Q17).
- [x] AC29: Given a repeating task completed late, then the next due date is the first occurrence after the old due date that is today or later, so no backlog of copies is created (Q18).
- [x] AC30: Given a completed repeating task, when I reopen it, then it becomes not Done and the copy already created stays (no duplicate is created when I complete it again) (Q19).
- [x] AC31: Given the edit dialog, when I choose "Stop repeating" (Repeat = Never), then completing it creates no copy. Deleting a repeating task deletes only that task, and no further copy is created.
- [x] AC32: Concurrency: two members (or two tabs) completing the same repeating task at once create exactly one copy; no 500.
- [x] AC33: States: the Repeat picker shows validation (at least one weekday for Weekly) and save errors; no console errors.

---

## Out of scope
- Browser push / service workers, SMS, mobile push (later; dependency + security gate)
- More than one reminder per task per user, snooze, reminders for other members, custom offsets
- Live updates (websockets/SSE); the bell polls (Q13)
- Notifications for anything other than reminders (invitations keep their own badge)
- Calendar sync / ICS export, an assignee
- "Every N days/weeks", "the 2nd Tuesday", end dates or counts for recurring tasks; editing "this and future" occurrences
- A dev-only "run the scheduler now" endpoint (tests use a short poll interval instead)

## UI notes
- **Edit dialog:** Due date → Due time (next to it) → Reminder → (T4b) Repeat.
- **Row:** due badge becomes "Oct 21, 09:30" for timed tasks; a small bell icon when I have a reminder; (T4b) a repeat icon.
- **Header:** bell with unread count next to the Invitations badge; links to `/notifications` (lazy-loaded route).
- **Account settings:** "Time zone" (searchable select) and "Reminder emails" toggle.

## Constraints
- **Contract:** additive only. `Task` gets `dueTime` (nullable `HH:mm`) and `myReminder` (the caller's offset or null). The user/account response gets `timeZone` and `reminderEmails`. New `/api/notifications` endpoints (list, unread count, mark read, mark all read). Exact shapes are agreed in contract negotiation (rule 3).
- **Time:** the backend uses an injectable `Clock`; the poll interval is configurable (`app.reminders.poll-interval`, default 60 s) so tests run in seconds, not minutes.
- **No new dependency:** Spring's built-in `@Scheduled`; no Quartz or ShedLock.
- **Mail:** existing `JavaMailSender`; the new env var `RATE_LIMIT_REMINDER_EMAILS_PER_ACCOUNT_PER_HOUR` is added to the e2e run setup (set to 1000000 there).
- **Performance:** the scheduler's claim query uses an index on the fire time and stays under 50 ms at 100,000 pending reminders; sort/paging keeps the T2 bar (first page < 300 ms at 10,000 tasks).
- **Privacy:** admins can't see reminders or notifications (as with tasks). Reminder emails never include the description.
- Existing behaviour that must not change: date-only tasks, their overdue rule (now evaluated in the account zone), quick-add, all T1–T3 behaviour.

## Open questions (PM recommendation in bold)
1. **Split?** **T4a = due time + reminders + notifications; T4b = recurring.** Alternative: 3 parts (due time + zone / reminders + notifications / recurring).
2. **Due time model:** **keep `dueDate`, add optional `dueTime`** vs replace with one `dueAt` instant.
3. **Time zone source:** **browser sets it only when empty; Account can change it; hint if the browser differs** vs overwrite on every sign-in.
4. **Reminder offsets:** **None / at due time / 10 min / 1 h / 1 day before.**
5. **Who is reminded on shared lists:** **only the person who set it (per user per task)** vs all members.
6. **Overdue zone:** **account zone** vs browser zone.
7. **Sort within a date:** **timed tasks (by time) before date-only** vs date-only first.
8. **Date-only reminders:** **"On the day at 09:00" / "1 day before at 09:00"** vs no reminders for date-only tasks.
9. **Reminder set in the past:** **save, never fire, show a note** vs refuse to save.
10. **Done / deleted / lost access:** **never fire; reopening re-arms.**
11. **Channel:** **email + in-app** (push later).
12. **Email rules:** **verified, active accounts only; "Reminder emails" toggle default on; cap 20/hour, silent; in-app always** vs any active account (as invite emails).
13. **Bell refresh:** **poll every 60 s while visible + on navigation** vs navigation only (like Invitations).
14. **Retention:** **90 days, newest first, "Mark all read".**
15. **Scheduler:** **`@Scheduled` + `SKIP LOCKED`, send outside the lock, 3 retries.**
16. **Missed reminders:** **< 1 h late → send (marked late); older → skipped.**
17. **Recurring copy (T4b):** **copy title, description, priority, tags, list, due time, and each member's reminder.**
18. **Completed late (T4b):** **skip to the first occurrence ≥ today** vs next occurrence after the old date (may already be overdue).
19. **Reopen a completed repeating task (T4b):** **keep the copy, no duplicate later** vs delete the copy.

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-20 | Open questions Q1–Q19 answered, all as the PM recommended: split T4a/T4b; optional `dueTime`; zone set once by the browser, editable, with a hint; per-user reminders; overdue in the account zone; timed before date-only within a day; handoff offsets + 09:00 for date-only; a reminder in the past is saved and never fires; verified-only emails, cap 20/h; bell polls every 60 s; late repeat skips to the first date ≥ today. Q10, Q11, Q14–Q17, Q19 accepted as written | Asked in 3 rounds in the options format | User |
| 2026-10-20 | T4a contract 0.14.0 agreed in round 1 (PM calls on differences). `Task` + `dueTime` (`HH:mm`|null, only with `dueDate`), `myReminder` (`AT_DUE`/`TEN_MINUTES`/`ONE_HOUR`/`ONE_DAY`/`ON_THE_DAY`|null; timed: first four; date-only: `ON_THE_DAY` 09:00, `ONE_DAY` 09:00 day before), `myReminderPassed`. Set via `PUT /api/tasks/{id}` fields `dueTime` + `reminder` (missing/null = unchanged, `NONE` clears, like `priority`); clearing `dueDate` drops time + my reminder; an offset that no longer fits is cleared. Errors `INVALID_REMINDER`, `REQUIRES_DUE_DATE`. `User` + `timeZone`, `reminderEmails`; `PUT /api/account/time-zone {timeZone, ifUnset?}` (atomic set-if-empty, region ids only, `INVALID_TIME_ZONE`), `PUT /api/account/reminder-emails {enabled}`. Frontend calls `ifUnset` from an effect on the signed-in user, not inside login (no auth change). Notifications: `GET /api/notifications` (cursor, 20/50), `/count`, `POST /{id}/read` (idempotent, `NOTIFICATION_NOT_FOUND`), `POST /read-all`; item `id,type,read,createdAt,late,title,dueDate,dueTime,taskId,listId`, `taskId`/`listId` null when gone or no access; frontend derives the "My tasks" link from its lists. Title snapshot kept. "Late" = over 5 min. DUE cursor version 3 | Backend owns the contract; consistent with existing PUT rules; one atomic save | PM |
| 2026-10-20 | AC7: due time is a wall-clock value, the same clock time in each member's zone (no `due_zone`) | Matches date-only `dueDate`; no day shifts | User |
| 2026-10-20 | Gate 1 approved: V12 (`users.time_zone` NULL, `users.reminder_emails` default true, `tasks.due_time` with check, `reminders` UNIQUE(task,user) cascades + claim index, `notifications` user cascade / task SET NULL + indexes) | Needed by AC1–AC18; additive | User |
| 2026-10-20 | Gate 2 approved: `@EnableScheduling` `ReminderScheduler` (poll 60 s configurable, `app.reminders.enabled` kill switch, SKIP LOCKED claim, email outside the lock, verified + active + toggle, `RATE_LIMIT_REMINDER_EMAILS_PER_ACCOUNT_PER_HOUR` 20 silent per instance, 3 retries then FAILED, >1 h late skipped, 90-day retention job); at-least-once on crash. No new dependency | Approved spec; invite-email pattern | User |
| 2026-10-20 | Spec approved. Gates (V12, email scheduler, any dependency) are still asked separately at build time | Rule 1 | User |
| 2026-10-20 | Build-time PM calls: `dueTime` on PUT missing = unchanged, null = clear; clearing `dueDate` (or timed → date-only) removes every member's reminder that no longer fits; timed `ONE_DAY` = same wall-clock time the previous day; a reminder saved into the past is stored SKIPPED; bad time format = `MALFORMED_REQUEST`; notifications `limit` 1–50 (`OUT_OF_RANGE`); new `MalformedNotificationId` 400. Row shows `Due 2026-10-21 09:30` (existing ISO style, not "Oct 21, 09:30"). Dialog stays open with the "passed" note when a save makes my reminder past. V12 as built adds scheduler state columns, CHECKs and indexes inside the approved tables (no extra tables). Env vars `REMINDERS_ENABLED`, `REMINDERS_POLL_INTERVAL`, `REMINDERS_LATE_AFTER`, `REMINDERS_BATCH_SIZE` (kill switch/config from Gate 2) | Gaps the log left open; consistent with existing rules | PM |
| 2026-10-20 | T4b: branch `feature/t4-recurring` from `main` (T4a already merged as `09805b3`). The repeat rule **moves to the copy** on completion: the Done task becomes non-repeating, so reopening and completing it again never creates a copy (AC30), and "Stop repeating" always applies to the live task | Clearest behaviour, least state | User |
| 2026-10-20 | T4b PM calls: monthly/yearly keep a stored anchor (Jan 31 → Feb 28 → Mar 31; Feb 29 returns in leap years); weekly = next selected weekday strictly after the old due date; AC29 "today" = the completer's account zone; a copy is made only on the not-Done → Done change (checkbox or status) in the service layer, never on other edits of a Done task; copy creation is DB-atomic (AC32); copied reminders reuse T4a fire-time rules (past → SKIPPED), only for current members; `repeat` on PUT follows T4a (missing/null = unchanged, `NEVER` clears); clearing `dueDate` clears the rule | Gaps in AC27–AC32; consistent with T4a rules | PM |
| 2026-10-20 | T4b contract 0.15.0 agreed in round 1 (frontend ACCEPT). `Task.repeat: RepeatRule|null` (always present); `RepeatRule {frequency: DAILY|WEEKLY|MONTHLY|YEARLY, weekdays: Weekday[]}` (`weekdays` Monday first, `[]` unless WEEKLY; anchor not exposed). `PUT /api/tasks/{id}` `repeat` = RepeatRule or `"NEVER"` (missing/null unchanged). PUT and PATCH return `TaskResult` = Task + `nextTask: Task|null` (the copy; null otherwise and for the AC32 loser). Errors on `repeat`: `REQUIRED` (no frequency / weekly without weekdays), `INVALID_REPEAT`, `REQUIRES_DUE_DATE`; wrong JSON type `MALFORMED_REQUEST`; duplicate weekdays collapsed. Copy on not-Done → Done from PATCH (checkbox) and PUT; post-update values; `repeat: "NEVER"` + complete → no copy; copy `addedBy` = completer; original keeps its (non-firing) reminders. POST takes no `repeat`. A rule may be set on a Done task (no copy until reopened and completed). AC32 via `SELECT … FOR UPDATE` on the source row; no source-link column | Matches frontend needs; additive | PM |
| 2026-10-20 | Gate approved: V13 (`tasks.repeat_frequency` VARCHAR(8) bin collation, `repeat_weekdays` TINYINT bitmask MON=1..SUN=64, `repeat_anchor` DATE; CHECKs on allowed frequency and rule shape incl. `due_date IS NOT NULL`; no index, no backfill) | Needed by AC27–AC31; additive, reversible | User |
| 2026-10-20 | T4b backend build calls: V13 WEEKLY branch adds `repeat_weekdays IS NOT NULL` (MySQL NULL logic; enforces the approved "1..127 iff WEEKLY", nothing new). Every task write now reads the row `FOR UPDATE` (not only completion) so the AC32 loser sees DONE and a stale write can't restore the rule; lock order unchanged. `TaskService` uses the injected `Clock`. Validation: wrong nested JSON types → `MALFORMED_REQUEST`; missing/blank frequency → `REQUIRED`; any string but exact `"NEVER"` → `INVALID_REPEAT`; unknown keys in `repeat` ignored; `REQUIRES_DUE_DATE` only for a well-formed rule. Re-sending the same rule + dueDate keeps the anchor. The copy goes to the final list after tags/move are applied. A late copy may be dated today; its reminder may already be past (SKIPPED) | AC32 correctness; consistent with T4a | PM |
| 2026-10-20 | T4b verified: AC27–AC33 PASS (32 new e2e, 96/96 with repeat-each=3; 24+6 race rounds, one copy, no 5xx). Full e2e 377: 354 pass, 22 env-gated skips, 1 flaky unrelated failure (`email-auth.spec.ts:289` hangs on `ctx.close()`, passed in the first full run; re-run 5/5 PASS on both `main` and `feature/t4-recurring`, so a load/teardown flake, not a T4b regression). Backend 1393 pass, frontend lint/960 unit/build pass. Existing e2e shape checks updated for `repeat`/`nextTask` (additive) | DoD | PM |
