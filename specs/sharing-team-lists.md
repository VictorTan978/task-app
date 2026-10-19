# Feature: Sharing — team lists (T3)

**Status:** APPROVED 2026-10-17 — T3a DONE (merged 2026-10-19, `11aae94`), T3b in progress on `feature/list-sharing`. **Depends on:** `specs/tasks.md`, `specs/tasks-priority-status-tags.md`, `specs/tasks-search-sort-pagination.md` (all DONE), the write-race fix (`a0aac6e`, on `main`). **Supersedes:** the `specs/auth.md` decision "personal task lists only, no sharing in v1" (2026-10-08).

**Built in two parts (Q20).** Each part has its own branch, contract version, migration, tests and merge:

| Part | What | Branch | Contract | Migration |
|---|---|---|---|---|
| **T3a: Lists** | Lists, list picker, per-list tags, moving tasks. Nothing shared yet. | `feature/sharing-team-lists` (exists) | 0.12.0 | V10 |
| **T3b: Sharing** | Invitations, members, roles, "Added by", access by membership | `feature/list-sharing` | 0.13.0 | V11 |

## Why
Today every task belongs to one person. People who run a household or a small project together want to see and work on the same tasks, instead of copying them between accounts.

## User story
As a signed-in user, I want to keep my tasks in several lists and share a list with other people, so that a team can work from one list instead of everyone keeping their own copy.

---

## Part A — T3a: Lists (not shared yet)

### Lists
- [ ] AC1: Given any user (existing, newly registered with email, or newly signed up with Google), then they have exactly one personal list named "My tasks" holding all their existing tasks. They can rename it, but it can't be deleted or shared (Q16). The database guarantees one personal list per user.
- [ ] AC2: Given a signed-in user, when they create a list with a name (trimmed, 1–100 characters), then they own it and it starts empty. They can own at most 50 lists including "My tasks" (Q13, Q17). Creating a 51st shows a clear error and nothing is created.
- [ ] AC3: Given a list I own, when I rename it, then the new name shows in the list picker and the list header.
- [ ] AC4: Given a list I own that isn't "My tasks", when I choose Delete, then a confirm dialog says how many tasks will be deleted (e.g. "Delete "Home" and its 12 tasks?"). Confirming deletes the list, its tasks and its tags, and I land on "My tasks". Cancelling changes nothing. "My tasks" has no Delete option.

### Switching lists
- [ ] AC5: Given I'm signed in, then a list picker shows all my lists, "My tasks" first, then the rest by name: a sidebar on desktop, a dropdown at the top on phone width. Choosing a list opens `/lists/<id>`. `/tasks` keeps working and shows "My tasks", and existing `/tasks?filter=…&tag=…&q=…&sort=…` links still work.
- [ ] AC6: Given a list is open, then quick-add, tabs, tag filter, search, sort and "Load more" (as in `specs/tasks-search-sort-pagination.md`) all work inside that list only. Switching lists starts from the first page with the search, tag, tab and sort reset to their defaults. There is no "all lists" view (Q9).
- [ ] AC7: Given a list id that doesn't exist or belongs to another user, when I open `/lists/<id>`, then I see a "List not found" message with a link to "My tasks". The API answers 404, never 403 (Q14).

### Tags belong to the list
- [ ] AC8: Given two lists, then each list has its own tags: a tag created in "Home" is not suggested or filterable in "Work". The same name can exist in both. The 200-tag limit applies per list (Q7).
- [ ] AC9: Given a user's tags from before this feature, then after the migration they are all on that user's "My tasks" with the same names and the same tasks. Nothing is lost or duplicated.

### Moving tasks
- [ ] AC10: Given a task and the edit dialog, when I pick another of my lists and save, then the task moves there (it leaves the current list view and the total adjusts). Its tags come along: any that don't exist in the target list are created there, and the source list keeps its own tag only if other tasks still use it (Q10). A tag whose name already exists in the target (ignoring case and accents) uses the target's spelling. In the picker and after a delete, "My tasks" is `/tasks`.
- [ ] AC11: Given the target list would go over 200 tags because of the move, then the move is refused with the existing tag-limit error and nothing changes (Q19).

### Account deletion
- [ ] AC12: Given I delete my account (myself, or an admin deletes it), then all my lists, tasks and tags are deleted, as today.

### Concurrency
- [ ] AC13: Given parallel writes (tester runs a stress script, as in T1/T2), then these never return 500 and never leave broken data: tag writes and moves in the same list; a move between lists A→B racing a move B→A (no deadlock left unretried); a task edit or move racing a delete of its list (404, not 500); two "create list" calls racing at the 50-list limit (never more than 50). Tag writes are serialized per list, not per user.

### Privacy, compatibility, performance
- [ ] AC14: Given users A and B, then A never sees B's lists, tasks or tags in any response. Admins still can't see any list or task (A5 AC10).
- [ ] AC15: Every existing AC in `specs/tasks.md`, `specs/tasks-priority-status-tags.md` and `specs/tasks-search-sort-pagination.md` still passes on "My tasks". API calls without a list parameter keep working and use "My tasks" (Q15). A page cursor from one list is rejected in another list (existing `INVALID_CURSOR`).
- [ ] AC16: Given about 10,000 tasks in one list, each sort, search and tab returns the first page in under 300 ms on the dev machine (same bar as T2).

### States and errors (T3a)
- [ ] AC17: The picker, create, rename, delete and move actions show loading and error states with Retry where it fits. Name validation errors appear next to the field. No browser console errors in normal use.

---

## Part B — T3b: Sharing

### Invitations
- [ ] AC18: Given a list I own (not "My tasks"), when I invite an email address, then the screen always says "Invitation sent" and shows the address as **pending**, whether or not an account exists for it. An email is sent only if an active (not disabled) account exists. Nothing on screen or in the API shows the difference (Q2, Q18, finding 4).
- [ ] AC19: Given a list, then it can have at most 20 members (including the owner) and 20 pending invitations. Inviting someone who is already a member or already invited shows a clear message and creates nothing new. Invites are rate-limited per user (existing 429 behaviour) (Q13).
- [ ] AC20: Given a pending invitation, then the owner can cancel it, and it expires by itself after 14 days. A cancelled or expired invitation can't be accepted (Q18).
- [ ] AC21: Given I have pending invitations, then an "Invitations" spot in the header shows a count. It lists who invited me and to which list, with Accept and Decline. Accept makes me a Member and the list appears in my picker. Decline removes the invitation. The emailed link opens this page (after sign-in if needed).
- [ ] AC22: Given an invitation whose list was deleted, cancelled or expired in the meantime, when I click Accept, then I see "This invitation is no longer valid" and nothing is added.

### Roles
- [ ] AC23: Given I'm a Member of a list, then I can add, edit, tick, move (into and out of lists I belong to) and delete any task in it and use its tags, but I can't rename or delete the list, invite, cancel invitations or remove members (Q3).
- [ ] AC24: Given I'm the Owner, then I can do everything a Member can, plus rename, delete, invite, cancel invitations and remove members.
- [ ] AC25: Given I'm a Member, when I choose "Leave list" and confirm, then I lose access and the list leaves my picker. The tasks I added stay in the list. The Owner has no "Leave", only Delete (no ownership transfer) (Q4).
- [ ] AC26: Given I'm the Owner, when I remove a member, then their next request for that list or its tasks gets 404 and the list leaves their picker. Their tasks stay (Q4).

### Who did what
- [ ] AC27: Given a list with 2 or more members, then each task shows "Added by <display name>". If that person's account was deleted, it shows "Added by a deleted user". On a list with one member nothing extra is shown. No assignee (Q8).

### Members, privacy, account deletion
- [ ] AC28: Given a list's members panel, then members see each other's display name, email and role (Owner/Member), and the owner also sees pending invitations. This information appears only inside that list (Q14).
- [ ] AC29: Given I'm not a member (or was removed), then anything about that list or its tasks answers 404, never 403. Admins still can't see any list or task (A5 AC10).
- [ ] AC30: Given a Member deletes their account (or an admin deletes it), then their memberships and pending invitations are removed, **but the tasks they added to other people's lists stay** ("Added by a deleted user"), and so do those lists' tags. Lists they own are deleted, and their members lose them. The delete-account warning says this (Q6, finding 1).

### Editing together
- [ ] AC31: Given two members edit the same task at about the same time, then the last save wins with no error. If one deletes it while the other saves, the saver gets 404 / "Task not found" (Q11).
- [ ] AC32: Given another member changes the list, then I see it after a reload or "Load more". No live updates (Q12).

### Concurrency (T3b)
- [ ] AC33: Given parallel load (tester stress script), then these never return 500 and the limits never go over: tag writes from two different members in the same list; accept racing cancel or expiry; two invite accepts racing at the 20-member limit; removing a member while they write; deleting a list while members write.

### States and errors (T3b)
- [ ] AC34: Invitations, members and leave/remove actions show loading, empty ("No pending invitations") and error states. No browser console errors in normal use.

---

## Out of scope (both parts)
- Ownership transfer, a read-only "Viewer" role, an assignee and an "Assigned to me" filter
- Conflict warnings / version checks (409), live updates (SSE/WebSocket)
- An "all lists" view, searching across lists
- Inviting people who have no account (signing them up from the invite)
- Sharing "My tasks"
- Dropping `tasks.done`, `tasks.owner_id` or `tags.owner_id` (later, separately approved migrations)

## UI notes
- **List picker:** left sidebar on desktop (list names, "My tasks" first, a member count on shared lists, "+ New list" at the bottom). On phone, a dropdown above the add box. A "⋯" menu on the list header holds Rename, Delete, Members (T3b) and Leave (T3b).
- **Edit dialog:** a "List" select (only my lists) for moving the task.
- **Invitations (T3b):** a header icon with a count badge that opens `/invitations`.
- **Members panel (T3b):** member rows (name, email, role, Remove for the owner), an invite-by-email form and pending invites with Cancel.
- Plain SCSS, no UI library, no new dependency.

## Constraints
- **T3a — migration V10 (approval gate; exact SQL shown first):** `lists` table (owner, name, personal flag, one personal list per user enforced by the database), a "My tasks" for every existing user, `tasks.list_id` (backfilled, then NOT NULL), `tags.list_id` (backfilled) with uniqueness on `(list_id, name)` instead of `(owner_id, name)`, and indexes on `list_id` matching today's owner indexes. The old `owner_id` columns stay (expand-then-contract).
- **T3a — authorization change (approval gate):** task and tag access goes through "the list belongs to me" instead of "the task belongs to me". Still 404 for anything not mine.
- **T3b — migration V11 (approval gate):** `list_members`, `list_invitations`, and `tasks.owner_id` changed to nullable `ON DELETE SET NULL` (it now means "added by"). Deleting a member must never delete a list's tasks or tags.
- **T3b — authorization change (approval gate):** access becomes "I'm a member of the task's list", with owner-only actions.
- Tag writes lock per list. A move locks both lists in a fixed order (lower id first) and is retried on deadlock (`DeadlockRetry`).
- Both sign-up paths (`AuthService` register, `GoogleAccountService`) create "My tasks" in the same transaction as the user.
- RFC 7807 errors, CSRF and rate-limit rules apply to every new endpoint. No new dependency (email already exists).
- Admins can't see lists or tasks (A5 AC10).

## Questions answered (2026-10-17: "yes" to all)
1. Share whole **lists**, not single tasks. 2. Invite by **email of an existing account**, accept/decline. 3. Roles **Owner and Member**. 4. Members can leave, the owner can remove; **the owner can't leave**. 5. **Owner-only delete with a task count**; "My tasks" can't be deleted. 6. Account deletion removes owned lists and memberships; **shown in the warning**. 7. **Tags belong to the list**, 200 per list. 8. **"Added by" only**, assignee later. 9. **List picker + `/lists/<id>`**, `/tasks` = "My tasks". 10. **Move tasks between your lists**, tags created in the target. 11. **Last write wins**. 12. **No live updates**. 13. **50 lists, 20 members, 20 pending invites, invite rate limit**. 14. **Name + email visible inside the list only; 404 never 403; admins see nothing**. 15. **`/api/lists`, members, invitations; `listId` on tasks; `list` parameter defaults to "My tasks"**. 16. **"My tasks" can't be shared** (renamable, not deletable). 17. **The 50-list limit counts owned lists only**. 18. **Owner can cancel invites; 14-day expiry; no email to disabled accounts, same answer on screen**. 19. **A move that would go over 200 tags is refused, nothing changes**. 20. **Split into T3a (lists) and T3b (sharing)**.

## Approvals needed
- ~~Spec approval~~ — approved 2026-10-17.
- T3a: migration V10 and the access change (exact SQL and changes shown before the build).
- T3b: migration V11 and the access change (shown when T3b starts).
- No new dependency expected.

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-17 | Q1–Q20 recommendations accepted, including the split into T3a (lists) and T3b (sharing) | User answered "yes for 1-20" after the PM's review of the draft against the code | User |
| 2026-10-17 | Spec changes from the review: member tasks survive account deletion (`tasks.owner_id` → nullable `ON DELETE SET NULL`, in V11); tags get `list_id` and `(list_id, name)` uniqueness, so a member's account deletion never deletes list tags; tag lock per list, moves lock both lists lowest id first; invites never reveal whether an account exists; new indexes on `list_id`; both sign-up paths create "My tasks" | Found in the code: `fk_tasks_owner` / `fk_tags_owner` are `ON DELETE CASCADE` (V2, V9), `TagService.lockOwner` is per user, and the project never reveals whether an account exists (forgot-password, login, Google AC8) | PM |
| 2026-10-17 | Write-race fix `a0aac6e` was merged to `main` after only the backend suite and task e2e. A full rerun (backend, lint, unit, build, full e2e) was started after the fact | Project rule: a full green rerun before merging; the PM skipped it | User asked for the rerun |
| 2026-10-17 | Spec approved (T3a and T3b as written, including the PM-picked details: list names 1–100 characters, the 50-list limit includes "My tasks", switching lists clears tag/search/tab, picker shows "My tasks" first then by name). V10/V11 and both access changes remain separate gates | User said "yes" | User |
| 2026-10-17 | Full rerun on `main` @ `a0aac6e`: backend 887, lint, unit 690, build PASS; e2e 239 passed / 1 failed / 15 skipped. The failure (`tasks-priority-status-tags.spec.ts:1114` hard-codes `localhost:4200`) and the parallel flake in `security-db-users.spec.ts:113` are test bugs, not `a0aac6e`; fixed first on `fix/e2e-base-url` | So T3a's own full rerun can be clean | User |
| 2026-10-17 | Contract 0.12.0 agreed in round 1. Lists: `GET /api/lists` (`TaskList {id, name, personal}`, "My tasks" first then by name in `utf8mb4_0900_ai_ci`, then id; unpaged), `GET /api/lists/{id}` (adds `taskCount`, all statuses; the delete dialog reads it on open), `POST` (201), `PATCH` rename, `DELETE` (204). `Task.listId` always present; `?list=` on `GET /api/tasks` and `GET /api/tags` (missing/empty = "My tasks"; anything not mine, including non-numeric, → 404 `LIST_NOT_FOUND`); optional `listId` on `POST /api/tasks` and `PUT /api/tasks/{id}` (move, no separate endpoint). Codes: `LIST_NOT_FOUND` 404 (task 404 stays `NOT_FOUND`), `LIST_LIMIT` 409, `PERSONAL_LIST` 409, name `REQUIRED`/`TOO_LONG`; `TAG_LIMIT` reused for moves. Cursor fingerprint includes the resolved list id (version 2) | Frontend needs and backend proposal matched | PM |
| 2026-10-17 | PM calls in round 1: `taskCount` only on `GET /api/lists/{id}`, not on the picker list (frontend doesn't need it there; backend flagged its cost at 50 lists × 10k tasks). No `role`/`memberCount` yet: they come in 0.13.0 (T3b), additive. Duplicate list names allowed. List names follow the task-title rules (same trimming and length counting as titles, 1–100). `TAG_LIMIT` message becomes "A list can have at most 200 tags"; the frontend names the target list itself on a move. Tag suggestions in the edit dialog stay the current list's during a move. Switching lists resets sort too. `/tasks` is the canonical "My tasks" URL. No rate limit on creating lists (the 50 limit caps it) | Keep T3a minimal; settle the 9 open points from the frontend and 8 from the backend | PM |
| 2026-10-17 | Known one-off effects at deploy: all 0.11.0 page cursors give `INVALID_CURSOR` once (frontend restarts at page 1); `GET /api/tasks`/`/api/tags` without `list` return only "My tasks" (same as today until a second list exists) | Accepted, only our frontend calls the API | PM |
| 2026-10-17 | Gates 1–3 approved: migration V10 (exact SQL in `docs/next-session-T3a-lists.md`), access through "the list is mine" with the lock order users → lists (lowest id) → rows, and both sign-up paths create "My tasks" in the user's transaction (not lazily) | User asked why not create "My tasks" lazily; PM explained the picker/rename need a real list row and lazy creation adds a first-request race; user said "yes" | User |
| 2026-10-17 | Contract 0.12.0 written into `contracts/openapi.yaml`. Backend-dev details accepted: check order body 400 → 404 → 409 (someone else's "My tasks" on DELETE → 404); `?list=` is a string parsed by hand (only empty = "My tasks"; blank, 0, negative, non-integer → 404); wrong-typed `listId` in a body → 400 `MALFORMED_REQUEST`, 0/negative → 404; same `listId` on PUT is not a move; texts "List not found", "You can have at most 50 lists", "Your personal list can't be deleted", "Name is required", "Name must be 100 characters or fewer"; names trimmed/counted like titles; `GET /api/lists` never empty | Fills gaps without changing anything agreed | PM |
| 2026-10-19 | Frontend built. Accepted: the page heading is the list's name ("My tasks" on `/tasks`, was "Your tasks"; 10 e2e assertions updated) so a rename shows in the header (AC3); route stream read once per `NavigationEnd` instead of combining `paramMap`/`queryParamMap`; list header and route helper extracted (`tasks-page.ts` still ~573 lines); Delete disabled until the task count loads; move to a deleted list keeps the dialog open with "This list no longer exists. Choose another list." Bundle 508.9 kB vs 500 kB warning budget left for the user | Within the approved plan; the heading change is what AC3 needs | PM |
| 2026-10-19 | Backend built (V10 byte-identical to the approved SQL; `./mvnw clean test` 1040/1040). Deviation from the Gate 2 lock plan accepted: PATCH, and PUT without `tags`/`listId`, now take users FOR SHARE → list FOR SHARE → re-read the task (same lock order) instead of running unlocked. A cross-user cursor test now expects `INVALID_CURSOR` (each user's "My tasks" is a different list). Tag owner taken from the list row | The new stress test showed the unlocked path deadlocking with list/account delete on the `list_id` index → 500. Shared locks keep concurrent edits non-blocking; cost ~3 small queries per edit. Access rule unchanged | PM |
| 2026-10-19 | Tester round 1: AC13 FAIL — list rename racing an account delete → 500 (unretried deadlock on `ix_lists_owner_name`). Fix round 1 (`f221dd6`): rename takes users FOR SHARE first and runs in `DeadlockRetry`; race test added. 29 e2e drift fixes (`listId` in Task keys, picker's extra heading/status) are test-only (`080c07d`) | Same lock order as every other list write | PM |
| 2026-10-19 | Full green rerun on `f221dd6`: backend 1052/1052, lint PASS, unit 784/784, build PASS (bundle warning 508.87 kB / 500 kB), e2e 276 = 260 passed + 0 failed + 16 skipped, perf worst 28.1 ms, AC13 stress 150 rounds 0 × 500. AC1–AC17 PASS. V10 ran on the dev DB (pre-V10 dump kept in the session scratchpad) | Definition of Done met; waiting for "merge" | PM |
| 2026-10-19 | T3a merged to `main` (`11aae94`, fast-forward). T3b on `feature/list-sharing` | User said "merge" / "start T3b" | User |
| 2026-10-19 | T3b contract 0.13.0 agreed in round 1 (backend proposal + frontend needs matched). `TaskList` + `role` (`OWNER`/`MEMBER`), `memberCount`; `GET /api/lists` = every list I'm a member of, same order, `maxItems: 50` dropped (50 counts owned only). `Task.addedBy` = `{displayName}` or null (deleted account); frontend shows it when `memberCount >= 2`; former members keep their name. New: `GET /api/lists/{id}/members`, `DELETE …/members/{userId}`, `POST …/leave`, `GET/POST …/invitations`, `DELETE …/invitations/{id}`, `GET /api/invitations`, `GET /api/invitations/count`, `POST /api/invitations/{id}/accept` (200 `TaskList`), `…/decline`. Codes: `OWNER_ONLY` 403 (members only; non-members always 404), `OWNER_CANNOT_LEAVE`, `ALREADY_MEMBER`, `ALREADY_INVITED`, `MEMBER_LIMIT`, `INVITATION_LIMIT` 409, `MEMBER_NOT_FOUND`, `INVITATION_NOT_FOUND` 404 ("This invitation is no longer valid"). Invitee matched by email at read/accept, verified emails only. Invitee sees the owner's display name, not their email. Re-invite of an expired invite refreshes it. A full list (20 members) refuses new invites. Email link = plain `/invitations`, no token. Invite email goes to any active account (verified or not); list name in the body only | PM picks: `addedBy` without user id (less exposure); two calls for the members panel (members + invitations) are fine; 403 to members is allowed because they already know the list exists (AC29 covers non-members) | PM |
| 2026-10-19 | T3b gates 1–6 approved: (1) V11 (`list_members` with owner backfill, `list_invitations` with `expires_at`, `tasks.owner_id` nullable `ON DELETE SET NULL`; no `role` column, owner = `lists.owner_id`); (2) access by membership, owner-only actions, lock order users (caller + list owners, ascending) → lists → membership → rows; (3) account deletion locks affected lists, deletes invites to the email, runs in `DeadlockRetry` (self and admin); (4) invitation email + `RATE_LIMIT_INVITES_PER_USER_PER_HOUR` (30) + silent `RATE_LIMIT_INVITE_EMAILS_PER_ACCOUNT_PER_HOUR` (5); (5) invitations visible/acceptable only with a verified email; (6) frontend remembers only `/invitations` across sign-in (password and Google) | Shown as gates with what/why/files/risks/reversible/alternative; user said "yes" | User |
| 2026-10-19 | Contract details accepted: decline of an expired invite → 404; a member removing themselves → 403 `OWNER_ONLY` (use leave); invite 409s have no `errors` array; invite 201 has no `Location` | Simpler for the frontend; no single-invitation endpoint | PM |
| 2026-10-19 | T3b frontend built (`49b7802`; unit 882/882). The redirect lives only in `authGuard` (all sign-in paths pass through it); Remove member uses an inline confirm. Bundle 548.6 kB vs 500 kB warning budget, left as a suggestion (lazy-load `/invitations`, `/admin`) | Smallest change that meets Gate 6 | PM |
| 2026-10-19 | T3b backend built (V11 = approved SQL; `./mvnw clean test` 1179/1179). Deviations accepted: task create reads the list before locking (plain read); self-delete starts with users FOR UPDATE, so the BCrypt check runs under that lock; decline also takes the list lock; `InvitationService` uses the Clock bean | Same lock order; makes every invitation write ordered against accept/cancel and lets tests move time | PM |
| 2026-10-19 | Tester round 1: AC34 FAIL — phone header overflowed (the new Invitations link pushed Log out off-screen; 5 e2e). Fix round 1 (`9c6fdf3`, `app-header.scss` only): header wraps at ≤640 px, greeting truncates with an ellipsis. e2e drift fixes test-only (`264ee04`); `lists.spec.ts` AC9 `owner_id` check narrowed to personal lists (it now means "added by") | Layout only; no behaviour change | PM |
| 2026-10-19 | Full green rerun on `9c6fdf3`: backend 1179/1179, lint PASS, unit 882/882, build PASS (bundle warning 549.06 kB / 500 kB), e2e 304 = 286 passed + 0 failed + 18 skipped, stress 150 rounds (sharing 5/5, lists 6/6) no 5xx, perf worst 34.1 ms. AC18–AC34 PASS. V11 ran on the dev DB (pre-V11 dump in the session scratchpad) | Definition of Done met; waiting for "merge" | PM |
