# Feature: Undo, trash, bulk actions, drag and drop (T5)

**Status:** APPROVED 2026-10-21. **T5a DONE** 2026-10-21 (AC1–AC17 PASS, merged to `main`).  **T5b DONE** 2026-10-21 (AC18–AC22 PASS, merged to `main`). **T5c DONE** 2026-10-21 (AC23–AC28 PASS, on `feature/t5c-dnd`, not merged). **Depends on:** `specs/tasks.md`, `specs/tasks-priority-status-tags.md`, `specs/tasks-search-sort-pagination.md`, `specs/sharing-team-lists.md`, `specs/due-times-reminders.md` (all DONE, on `main`).

**Approving this spec does not approve the gates.** Each migration (V14, V15), the change to `DELETE /api/tasks/{id}`, and any new dependency (`@angular/cdk`) gets its own approval question at build time.

**Proposed split (Q1).** Each part has its own branch, contract version, migration, tests and merge:

| Part | What | Branch | Contract | Migration |
|---|---|---|---|---|
| **T5a: Trash + undo** | Deleting moves a task to the trash. Trash page with restore, permanent delete, "Empty trash", purge after 30 days. An "Undo" toast for delete, tick, move and edit | `feature/t5a-trash-undo` (created) | 0.16.0 | V14 |
| **T5b: Bulk actions** | Select many tasks and complete, reopen, set status or priority, add or remove a tag, move, or delete them in one go (with undo) | `feature/t5b-bulk` | 0.17.0 | none |
| **T5c: Drag and drop** | A shared "My order" sort with drag to reorder (keyboard too), and drag a task onto a list in the sidebar to move it | `feature/t5c-dnd` | 0.18.0 | V15 |

## Why
Deleting a task today is permanent: one wrong click loses it. Cleaning up many tasks takes one click per task, and you can't put tasks in your own order.

## User story
As a signed-in user, I want to undo mistakes, get deleted tasks back, change many tasks at once and drag tasks into my own order, so that tidying up my lists is fast and safe.

---

## Part A — T5a: Trash + undo

### Trash (soft delete, Q2)
- [x] AC1: Given a task in one of my lists, when I delete it (the row's delete action, after the existing confirm dialog), then it disappears from the list, from search results, from `total` and from the tag filter. It appears in the Trash and an "Undo" toast is shown (AC9). For T5a the confirm dialog stays. Q6 asks whether to remove it now that undo exists.
- [x] AC2: Given the sidebar, then a "Trash" entry opens `/trash`. It lists the trashed tasks of **every list I am a member of**, newest-deleted first. Each row shows the title, the list name, who deleted it and when ("Deleted by Ana · 2 days ago"), and how many days are left before it is purged. A list filter narrows it to one list. Paging works like the task list: 50 per page and "Load more" (Q3).
- [x] AC3: Given a trashed task, when I click "Restore", then it goes back to its original list exactly as it was: same tags, priority, status, due date and time, repeat rule, `addedBy` and `createdAt`. My own reminder is active again if its fire time is still in the future, the same as reopening a task. It leaves the Trash.
- [x] AC4: Given a trashed task, when I click "Delete forever" and confirm, then it is permanently deleted. Tags that no task in its list uses any more are deleted. "Empty trash" (with a confirm dialog naming the count) does this for every trashed task I can see, or only for the filtered list when the filter is set.
- [x] AC5: Given a task that has been in the trash for 30 days, then it is permanently deleted automatically, with the same tag cleanup. It happens within an hour of the 30 days passing, using the existing scheduler (no new dependency) (Q4).
- [x] AC6: Shared lists: given a shared list, then every member sees the same trashed tasks of that list, and **any member** can restore them, delete them forever or empty them. This matches "any member can delete any task" (Q3).
- [x] AC7: Lost access: if I leave a list or am removed from it, its trashed tasks disappear from my Trash, and any action on them gives 404. If a list is deleted, its trashed tasks are deleted with it (no trash for lists). If an account is deleted, its trashed tasks in other people's lists stay, shown as "Deleted by a deleted user".
- [x] AC8: While trashed, a task:
  - fires no reminder for anyone (AC3 covers what happens on restore);
  - creates no repeat copy;
  - can't be opened, edited, ticked or moved: `GET`/`PUT /api/tasks/{id}` give 404 `NOT_FOUND`, the same as a deleted task today;
  - still keeps its tags in use, so the tag-cleanup rule doesn't delete them before it is purged;
  - keeps its notifications in the bell. Clicking one shows "This task is in the trash" with a "Restore" button instead of opening it.

### Undo (Q5)
- [x] AC9: Given I just did one of these actions, then a toast appears at the bottom for **8 seconds** with a short message and an "Undo" button:
  - delete ("Task moved to trash");
  - tick or untick ("Task completed" / "Task reopened");
  - move to another list ("Moved to Work");
  - save in the edit dialog ("Task updated").

  Clicking "Undo" puts the task back the way it was before that action, and the toast says "Undone". Only the latest action can be undone: a new action replaces the toast. The toast closes when I go to another page, and nothing can be undone after a reload. It is announced to screen readers (`aria-live="polite"`), stays open while hovered or focused, and has a close button.
- [x] AC10: Undo of a delete is the same as Restore (AC3).
- [x] AC11: Undo of completing a **repeating** task reopens it, puts the repeat rule back on it and **permanently deletes the copy** that the completion created. The copy is deleted only if no one has changed it since. If someone has, the original is reopened without the rule, the copy is kept, and the toast says "Undone: the next copy was already changed, so it was kept" (Q7).
- [x] AC12: Undo of a move moves the task back to its old list with its old tag names. Tags are created in the old list again if they were cleaned up, following the existing move rules. If I am no longer a member of the old list, the undo fails (AC14).
- [x] AC13: Shared lists: undo is last-write-wins, like all edits. If a teammate changed the task after my action, my undo overwrites only the fields my action changed. For an edit, that means the fields I changed in the dialog (Q8).
- [x] AC14: Error case: if the undo can't be applied, the toast says "Couldn't undo: the task was deleted / you no longer have access", with no retry, and the list reloads. "The task was deleted" means permanently deleted, or trashed by someone else when the undo is not a delete undo. The API answers 404, never 500.

### Contract, compatibility, states
- [x] AC15: Contract 0.16.0:
  - `DELETE /api/tasks/{id}` now **moves the task to the trash** (still 204; 404 if it is not in one of my lists or is already trashed).
  - New endpoints:
    - `GET /api/trash` (`list`, `cursor`), returning `TrashPage { items, nextCursor, total }`. Each item is a `Task` plus `deletedAt`, `deletedBy`, `purgeAt`.
    - `POST /api/trash/{id}/restore` returns 200 with the `Task`.
    - `DELETE /api/trash/{id}` (delete forever) returns 204.
    - `POST /api/trash/empty` (`list` optional) returns 200 with `{ deleted: n }`.
  - Undo for tick, move and edit uses `PATCH /api/tasks/{id}` with only the fields the action changed (see the Decisions log). Undo of a repeating completion uses a new `POST /api/tasks/{id}/undo-complete` with `{ nextTaskId }`, which implements AC11 atomically.
  - All of these keep CSRF, sign-in and the 404-never-403 rule.
- [x] AC16: Given the Trash page, then it has a loading state, an empty state ("Trash is empty. Deleted tasks stay here for 30 days."), and an error state with "Retry". Restoring the last task shows the empty state.
- [x] AC17: No regressions: every existing e2e test passes. The delete test now expects the task in the Trash.

---

## Part B — T5b: Bulk actions

- [x] AC18: Given the task list, when I click "Select" (or tick the first row's checkbox), then every row shows a checkbox. The header shows "N selected", "Select all loaded" and "Cancel". Shift+click selects a range. Esc cancels. Selection covers only the tasks loaded on screen, never unloaded pages (Q9).
- [x] AC19: Given 1 or more selected tasks, then a bulk action bar offers:
  - Complete
  - Reopen
  - Set status
  - Set priority
  - Add tag
  - Remove tag
  - Move to list
  - Delete

  Applying one changes every selected task in **one request** and refreshes the list. The toast says "12 tasks completed" with "Undo", which reverses the whole batch (AC9 rules). Delete moves them all to the trash, and its confirm dialog names the count.
- [x] AC20: All-or-nothing: if any task can't be changed, nothing changes. For example: one was deleted by a teammate, I lost access, or the move would exceed the 200-tag limit. The message names the reason ("3 tasks are no longer available. Reload and try again."), and the selection is kept.
- [x] AC21: Bulk-completing repeating tasks creates one copy for each, exactly as single completion does. Undo follows AC11 for each task.
- [x] AC22: `POST /api/tasks/bulk` takes `{ ids: [...] (1–200, unique), action, value }` and returns `{ tasks: [...], nextTasks: [...] }`. Tasks may come from different lists only on the Trash page (restore / delete forever). On the task list they are all from the current list. More than 200 ids, or an unknown action, gives 400. Any id not in my lists gives 404 `NOT_FOUND`, never 403.

---

## Part C — T5c: Drag and drop

- [x] AC23: Given the sort menu, then a new option **"My order"** exists. It shows the list's tasks in an order **shared by all members of the list** (Q10). Tasks that never had a position (all existing tasks after V15) are ordered by the newest-first rule. A new task goes to the top.
- [x] AC24: Given "My order" with no filter other than "All", no tag and no search, when I drag a row (mouse or touch) and drop it, then it stays in its new place after a reload, and other members see the same order after their reload. Done tasks stay in their own group below the not-done ones, and dragging works inside a group only. With any other sort, filter, tag or search active, the drag handles are hidden, and a hint says "Switch to My order to drag" (Q11).
- [x] AC25: Keyboard and accessibility: when a row's drag handle has focus, Space picks the row up, Up and Down move it, Space drops it and Esc cancels. Each step is announced ("Moved to position 3 of 12"). "Move up" and "Move down" are also in the row menu.
- [x] AC26: Paging: dragging works within the loaded tasks. Dropping onto the last loaded row puts the task right after it, even if more pages exist. "Load more" continues from the new order without repeating or skipping tasks.
- [x] AC27: Given the sidebar lists, when I drag a task onto another list I'm a member of, then the task moves there using the existing move rules (tags, 200-tag limit, error messages), with an Undo toast (AC12). Dropping it on its own list, or on the Trash entry, does nothing, and the Trash entry is not a drop target.
- [x] AC28: Error case: if saving the new order fails, the row snaps back to its old place and a message says "Couldn't save the new order". Contract 0.18.0: `sort=manual` (cursor version bumped, so old cursors give `INVALID_CURSOR` once). The new endpoint is `PUT /api/tasks/{id}/position` with `{ afterId | null }`, where `null` means top of its group.

---

## Out of scope
- Undo after a reload, an undo history or redo. Undo for list actions (rename, delete list, leave, invitations) and for account or admin actions.
- A trash for lists, tags or notifications.
- Selecting "all N matching" across unloaded pages.
- Per-user private orders on shared lists, drag between groups (Done ↔ not done), dragging several tasks at once, and dragging in the Trash.
- Live updates of teammates' changes (still after reload, as today).

## UI notes
- Toast: a new shared component at the bottom centre, one at a time, styled like the existing dialogs. None exists yet.
- Trash: a sidebar entry under the lists with a trash icon. `/trash` uses the task-row look, with the actions read-only except Restore and Delete forever.
- Bulk bar: sticky at the top of the task list, replacing the header while a selection exists. Phone width: actions collapse into a "More" menu.
- Drag handle: a grip icon at the row's left, shown only in "My order".

## Constraints
- **Every task read must skip trashed tasks** (`backend-dev` must cover each one):
  - `GET /api/tasks` (page, `total`, `q`, `tag`, `filter`, cursor);
  - `GET/PUT/DELETE /api/tasks/{id}`;
  - `GET /api/tags` counts and the tag-cleanup rule (a trashed task still *uses* its tags; see AC8);
  - the reminder scheduler (`ReminderProcessor` / `ReminderService`);
  - recurrence (`RecurrenceService`);
  - notifications (`NotificationService`);
  - account and list deletion (`AccountService`, `ListService`);
  - `TaskSearchRepositoryImpl`.
- Purge and permanent delete keep the existing database cascades: reminders and task tags are deleted, and notifications keep a `NULL` task.
- Performance: the trash filter must use an index (`deleted_at` together with `list_id`) so task-list queries stay as fast as today.
- Frontend bundle: the initial bundle is 475.77 kB against a 500 kB budget. The toast is small. `@angular/cdk` (T5c) must load only with the tasks route, never in the initial bundle.
- Existing behaviour that must not change: single task create, edit and tick, sorting other than "My order", paging, sharing permissions, reminders and recurrence for tasks that aren't trashed.

## Open questions (PM recommendation in bold; all answered 2026-10-21, see the Decisions log)
1. **Split:** **T5a trash + undo, T5b bulk, T5c drag and drop**, each with its own branch, contract, migration and merge. Alternatively, one T5 (too big to test), or T5a = trash + undo + bulk.
2. **How delete works:** **soft delete** (`deleted_at` and `deleted_by` on `tasks`, migration V14), so both the trash and the undo work. The alternative is undo only, by delaying the real delete on the client for 8 s: no migration, but no trash, and the task is lost if the tab closes.
3. **Trash scope on shared lists:** **one Trash page for all my lists, where any member can restore or delete forever.** Alternative: only the person who deleted a task sees it in their Trash.
4. **Retention:** **30 days, then an automatic purge**, plus "Empty trash". Alternative: keep until emptied by hand, which makes the table grow.
5. **What can be undone:** **delete, tick/untick, move, edit-dialog save** (and bulk in T5b). Alternative: delete only.
6. **Delete confirm dialog:** **keep it in T5a** (safe and unchanged) and decide after using undo. Alternative: remove it, since undo and trash make it redundant.
7. **Undoing a repeating completion:** **delete the copy unless someone has changed it; if someone has, keep it and reopen the original without the rule.** Alternatives: always delete the copy (can lose a teammate's edits), or offer no undo for repeating completions.
8. **Undo vs a teammate's newer edit:** **last-write-wins on the fields my action changed** (no conflict check, like all edits today). Alternative: refuse the undo if the task changed since (needs a version check).
9. **Bulk scope:** **loaded tasks only, max 200 per request, one list at a time.** Alternative: "select all N matching" across pages, which needs a filter-based endpoint.
10. **"My order" ownership:** **shared by the list** (one `position` column, everyone sees the same order). Alternative: a private order per user, which needs a new table and is more complex.
11. **Drag library (dependency gate, T5c):** **`@angular/cdk` drag-drop**, the official Angular library, with touch, keyboard-friendly and auto-scroll support, lazy-loaded with the tasks route. Alternative: native HTML5 drag and drop (no dependency, but poor touch support and manual keyboard and auto-scroll).

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-21 | Q1: split into T5a trash + undo, T5b bulk, T5c drag and drop | Small, separately testable parts; later parts reuse T5a | User |
| 2026-10-21 | Q2: soft delete (`deleted_at`, `deleted_by`, V14 still gated) | Only option that gives a real trash | User |
| 2026-10-21 | Q3: one Trash page for all my lists; any member restores or deletes forever | Matches "any member can delete any task" | User |
| 2026-10-21 | Q4: 30 days, then hourly auto-purge; plus "Empty trash" | Bounded table, familiar rule | User |
| 2026-10-21 | Q5: undo for delete, tick/untick, move, edit-dialog save (bulk in T5b) | Covers the common mistakes | User |
| 2026-10-21 | Q6: keep the delete confirm dialog in T5a | No behaviour change; revisit later | User |
| 2026-10-21 | Q7: undoing a repeating completion deletes the copy unless it was changed | Never loses a teammate's edit | User |
| 2026-10-21 | Q8: undo is last-write-wins on the fields my action changed | Matches today's editing; no version column | User |
| 2026-10-21 | Q9: bulk = loaded tasks only, max 200, one list at a time, all-or-nothing | Predictable; the user sees what changes | User |
| 2026-10-21 | Q10/Q11: shared "My order" per list, `@angular/cdk` drag-drop (dependency still gated, lazy-loaded) | Touch, keyboard and auto-scroll support; one position column | User |
| 2026-10-21 | Spec approved | All open questions settled | User |
| 2026-10-21 | Gate: migration V14 `V14__tasks_trash.sql` (`deleted_at`, `deleted_by` FK SET NULL, 3 indexes) approved | Minimal schema for soft delete; additive | User |
| 2026-10-21 | Gate: `DELETE /api/tasks/{id}` now moves to trash; permanent delete is `DELETE /api/trash/{id}` | Existing UI delete gets trash + undo | User |
| 2026-10-21 | Contract 0.16.0 round 1: `PATCH /api/tasks/{id}` takes any subset of task fields (PUT's null rules: null clears only description/dueDate/dueTime); undo of tick/move/edit uses PATCH with only the changed fields | AC13: PUT is a full replace and would revert teammates' edits | PM |
| 2026-10-21 | `undo-complete {nextTaskId, status?}` returns `nextTaskOutcome` DELETED/KEPT/GONE; "untouched" = same list, not trashed, has rule, TODO, `updatedAt == createdAt`; server restores the rule | AC11 atomically, never deletes an edited copy | PM |
| 2026-10-21 | Single `NOT_FOUND` for failed undo; UI shows "Couldn't undo: the task was deleted or you no longer have access" | No new error codes | PM |
| 2026-10-21 | `Notification.taskTrashed` (required boolean); `taskId`/`listId` kept while trashed | AC8 | PM |
| 2026-10-21 | Edit + move in one save toasts "Moved to <list>"; toast closes on path change only (not query changes); Trash page has "Back to tasks" | Frontend plan | PM |
| 2026-10-21 | T5a verified: AC1–AC17 PASS; AC5 purge via backend `TrashIntegrationTest` (30 days can't be waited in e2e) | tester report; e2e 414 passed / 22 skipped / 436 | tester |
| 2026-10-21 | Raise the initial-bundle warning budget from 500 kB to 520 kB (error stays at 1 MB), done in T5b | Bundle at 499.93 kB; T5b would cross it | User |
| 2026-10-21 | Contract 0.17.0: `POST /api/tasks/bulk {ids, action, value?}` (COMPLETE, REOPEN, SET_STATUS, SET_PRIORITY, ADD_TAG, REMOVE_TAG, MOVE, DELETE) returns `BulkResult {count, tasks: TaskResult[]}` (each `nextTask` paired; replaces AC22's separate `nextTasks`) | Undo needs task↔copy pairing | PM |
| 2026-10-21 | Undo endpoints: `POST /api/tasks/bulk-patch {items:[{id, patch}]}` (per-task old values), `POST /api/tasks/bulk-undo-complete {items:[{id, nextTaskId?, status?}]}` (no `nextTaskId` = plain reopen, so undoing a mixed COMPLETE is one atomic call), `POST /api/trash/restore {ids}` (may span lists). Tag undo stays relative (REMOVE_TAG/ADD_TAG on the affected ids) | One all-or-nothing request per undo; never overwrites teammates' tags (AC13) | PM |
| 2026-10-21 | Errors: 404 `NOT_FOUND` with `Problem.failedIds`; new field codes `MIXED_LISTS`, `DUPLICATE`; mixed lists refused on `bulk`/`bulk-patch` | AC20 message needs the count; one list at a time (Q9) | PM |
| 2026-10-21 | Out of T5b: Trash-page selection UI and bulk delete-forever (AC22's trash mention); "Empty trash" covers clearing | AC18–AC21 describe the task list only; can be added later with `POST /api/trash/delete {ids}` | PM |
| 2026-10-21 | AC18: selection mode is entered via the "Select" button only (not by ticking a row checkbox; the row's only checkbox is "Done", and a second always-on checkbox would break ~10 e2e locators). SET_STATUS DONE undoes via `bulk-undo-complete` (as COMPLETE) | Same capability, no regressions | PM |
| 2026-10-21 | T5b fix round 1: Esc in the bulk-delete confirm closed selection mode too; fixed (3b3e1dc) | tester bug 1 | PM |
| 2026-10-21 | T5b verified: AC18–AC22 PASS (50 e2e in `bulk-actions.spec.ts`). Full suite 486 tests, 22 skipped; 3 runs had 1/3/1 intermittent failures, each passing alone: `tasks-priority-status-tags.spec.ts:673` also flakes on main (2/24, types before suggestions load); `google-sign-in.spec.ts` 815/1085/1304/1379 and `password-fields.spec.ts:119` (CSRF/401 checks under load) passed 24/24 on main, untouched by T5b, root cause unknown | tester report | tester |
| 2026-10-21 | fix/e2e-flakes: AC18 Shift+click with an anchor always **selects** the anchor..clicked range, even when the clicked row is already selected (it used to apply the native toggle and deselect the range). The selection checkbox is now driven by the model, so it can't show unchecked while counted as selected | Spec text "Shift+click selects a range"; `bulk-actions.spec.ts:400` failed 6/16 under load | PM |
| 2026-10-21 | fix/e2e-flakes root causes (all test-side except the AC18 one above): tag AC10 typed before `GET /api/tags` returned; `xsrf()` helpers minted a token racing the app's boot `/api/auth/me` (app is fine: Angular reads the cookie at send time); `EMAIL_VERIFICATION_SENT` is async so its log order isn't fixed; sessions AC9 pressed Escape before `showModal()`; tests read response bodies Chromium had already dropped (now read immediately, or via `route.fetch` in mysql-regression). google-sign-in AC8/AC13, `password-fields:119`, `email-auth` ctx.close hang: not reproduced in 24× repeats after the fixes | PM investigation + tester runs | PM |
| 2026-10-21 | Gate: migration V15 approved: `tasks.position BIGINT NULL`, gapped (steps of 1024), index (list_id, deleted_at, position); the server renumbers a group in the same transaction when a gap runs out. NULL = never ordered, newest-first fallback | One write per drag, low lock contention on shared lists; additive | User |
| 2026-10-21 | Gate: dependency `@angular/cdk@^22.2.2` approved, loaded only with the tasks route; stop and report if the initial bundle crosses 520 kB | Touch, auto-scroll, connected drop lists for the sidebar (AC27) | User |
| 2026-10-21 | Contract 0.18.0 rules: `sort=manual` = per group (not-done, then done) position ASC, then NULL newest-first, tie-break id; allowed with filter/tag/q server-side. Create + recurrence copy → top of group; group changes (tick, status, bulk, undo-complete) keep the value (undo puts it back); list moves (PATCH/bulk MOVE/undo) → top of destination; trash restore keeps the value. A PUT in a group with NULLs first assigns gapped positions to the whole group | Every position write enumerated; one-row writes in the common case | PM |
| 2026-10-21 | `PUT /api/tasks/{id}/position {afterId\|null}` → 204; `position` not exposed; reorder does not touch `updatedAt` (keeps the T5a "untouched copy" rule). Lock list row FOR UPDATE, then tasks by id | No body needed; concurrency per lock order | PM |
| 2026-10-21 | `manual` cursor is anchored on the last row's task id (resolved at query time); the client skips already-loaded ids on "Load more"; missing anchor → `INVALID_CURSOR` | Survives renumbering; AC26 drop-after-last-row is deduped | PM |
| 2026-10-21 | `TasksPage` and the list picker are eager (initial bundle): CDK drag lives in a `@defer` block; AC27 sidebar drop works by hit-testing sidebar items (data attribute) on drag end, not by CDK drop lists in the picker. AC27 is available only in "My order" (the grip shows only there) | Keep CDK out of the initial bundle (520 kB budget) | PM |
| 2026-10-21 | Contract 0.18.0 drafted (f89d88d): cursor v4 for all sorts; `manual` cursor = anchor id + the `done` group it was issued in (group from the cursor, position/createdAt/id from the anchor row); field codes `afterId` `SELF`/`OTHER_LIST`/`OTHER_GROUP`; "done" = `status = DONE`, groups only with `filter=all` | Anchor-only would skip the rest of not-done rows if the anchor is ticked between pages | PM (backend-dev proposal) |
| 2026-10-21 | Bulk MOVE: the moved tasks keep their request (display) order at the top of the destination list | Predictable; the contract draft had the reverse order | PM |
| 2026-10-21 | Raise the initial-bundle warning budget from 520 kB to 540 kB (error stays at 1 MB). Initial bundle 532.71 kB: ~9 kB Angular core kept by CDK in a shared chunk (CDK itself is only in the deferred `task-sort-list` chunk), ~8 kB eager tasks-page code | The CDK core cost is unavoidable; deferring task rows would be a large refactor | User |
| 2026-10-21 | AC25: rows have no overflow menu, so "Move up"/"Move down" are buttons in the row's actions area (only when drag is allowed, disabled at a group's edge). CDK 22 has no keyboard reordering, so the keyboard mode is custom, announced via CDK `LiveAnnouncer`. In manual order with a non-All tab, tag or search the hint is "Show All with no tag or search to drag" | Same capability without a row-menu refactor | PM |
| 2026-10-21 | Client-side, a ticked task shows at the top of its new group until the next reload (the server keeps its value, so after a reload it may show elsewhere in the group). The sidebar drop sends `PATCH {listId, tags}` | Client doesn't know positions (not exposed); acceptable optimistic view | PM |
| 2026-10-21 | Backend built (08a8699, 476fa75, f939ed6): V15 index named `ix_tasks_list_position` (a `%deleted%` name broke the V14 index-count test; V15 was not applied anywhere yet). `position` is `updatable=false` in JPA, written only by native UPDATEs. Two simultaneous creates may tie on position (ordered newest-first; next drop renumbers). `./mvnw clean test`: 1583 tests, 0 failures | Keeps stale saves from writing old positions | PM |
| 2026-10-21 | T5c verified: AC23–AC28 PASS (24 e2e in `drag-and-drop.spec.ts`, 3 consecutive green runs). Full suite 511 tests: 488 passed, 1 failed, 22 skipped; the failure was `tasks-search-sort-pagination.spec.ts:659` expecting the old sort list (updated for "My order", passes alone). Real touch not emulated (CDK shares the pointer path). Backend 1583 tests, frontend lint + 1216 unit + build green, initial 532.71 kB | tester report + PM re-run | tester |
| 2026-10-21 | T5c re-verify: touch drag (CDP touch events, `hasTouch`) PASS 3/3; full suite 512 tests: 490 passed, 0 failed, 22 skipped (ebc2fb0) | AC24 "mouse or touch" verified; clean green total | tester |
