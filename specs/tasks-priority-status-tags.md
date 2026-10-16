# Feature: Tasks — priority, status and tags

**Status:** APPROVED 2026-10-16 (all 14 recommendations accepted). **Depends on:** `specs/tasks.md`, `specs/roles-admin.md` (both DONE). **Branch:** `feature/tasks-priority-status-tags`

## Why
A flat list of "done / not done" tasks gets hard to use once it grows: users can't tell what matters most, what they have already started, or which tasks belong together. Priority, a three-step status and personal tags fix that without changing how the list works today.

## User story
As a signed-in user, I want to give my tasks a priority, a status (To do / In progress / Done) and my own tags, and filter the list by status and tag, so that I can see what matters and what I'm working on at a glance.

## Fields added to a task

| Field | Values | Default | Notes |
|---|---|---|---|
| `priority` | `NONE`, `LOW`, `MEDIUM`, `HIGH` | `NONE` | Shown on the row as a small coloured badge: Low (grey), Medium (amber), High (red, a different shade from "Overdue"). Nothing for None. |
| `status` | `TODO`, `IN_PROGRESS`, `DONE` | `TODO` | Shown as "To do", "In progress", "Done". Only "In progress" gets a badge on the row; Done keeps the strike-through. |
| `done` | boolean | — | Kept, **read-only mirror** of `status == DONE` (expand-then-contract). `PATCH {done}` still works. |
| `tags` | list of tag names, 0–10 | `[]` | Per-user, private. 1–30 characters after trimming. Unique per user ignoring case and accents. |

## Acceptance criteria

### Create
- [ ] AC1: Given I am on `/tasks`, when I add a task with the quick-add box, then it is created with no priority, status "To do" and no tags (the quick-add box is unchanged: title only).

### Priority
- [ ] AC2: Given a task exists, when I open its edit dialog, pick "High" in the Priority field and click "Save", then the row shows a red "High" badge, which persists after a refresh. Picking "Low" or "Medium" shows a grey "Low" / amber "Medium" badge. Picking "None" removes the badge.

### Status
- [ ] AC3: Given a task is "To do", when I open its edit dialog, set Status to "In progress" and save, then the row shows an "In progress" badge, the checkbox stays unticked, and this persists after a refresh.
- [ ] AC4: Given a task is "To do" or "In progress", when I tick its checkbox, then its status becomes "Done" (struck through, moved into the done group, no "In progress" badge). Given a task is "Done", when I untick it, then its status becomes "To do".
- [ ] AC5: Given a task is "Done", when I set its status to "To do" or "In progress" in the edit dialog, then it is no longer struck through and moves back into the not-done group. Setting status to "Done" in the dialog has the same result as ticking the checkbox.
- [ ] AC6: Given an "In progress" task has a due date before today, then it shows "Overdue". A "Done" task never shows "Overdue".

### Filters
- [ ] AC7: Given I have tasks in all three statuses, when I pick the tabs All / Active / In progress / Done, then: All shows everything; Active shows "To do" and "In progress"; In progress shows only "In progress"; Done shows only "Done". The URL becomes `/tasks`, `?filter=active`, `?filter=in_progress`, `?filter=done`, and a refresh keeps the tab. An empty In progress tab shows "Nothing in progress". (Existing empty messages for All, Active and Done stay.)
- [ ] AC8: Given tasks in "To do" and "In progress", when I view All or Active, then the order is unchanged from today: not-done first (To do and In progress mixed), then done, each group newest first. Priority does not change the order.

### Tags
- [ ] AC9: Given the edit dialog is open, when I type a tag name and press Enter or comma, then it appears as a chip in the Tags field; clicking a chip's "×" removes it. After "Save", the row shows the tags as chips, and they persist after a refresh.
- [ ] AC10: Given I already used the tag "Work" on another task, when I type "wo" in the Tags field, then "Work" is suggested and picking it adds it. When I type "work" or "wörk" and press Enter, then the existing "Work" chip is used (no duplicate tag is created, and the existing spelling is kept).
- [ ] AC11: Given the edit dialog is open, when I add a tag that is already on the task (ignoring case and accents), then no second chip is added. When I try to add an 11th tag, then it is rejected with "A task can have at most 10 tags". A tag over 30 characters is rejected with "Tags must be 30 characters or fewer". A blank tag is ignored.
- [ ] AC12: Given tasks with tags, when I click the tag "Work" on a row, then the list shows only tasks tagged "Work" within the current tab, the URL gets `?tag=Work` (combined with `filter`), and a "Tag: Work ×" bar appears above the list. Clicking "×" clears the tag filter. A refresh keeps it. A tag filter with no matching tasks shows "No tasks with this tag".
- [ ] AC13: Given the tag "Old" is used by only one task, when I remove it from that task (or delete the task), then "Old" is no longer suggested.

### Ownership and privacy
- [ ] AC14: Given users A and B both have a tag "Work", then each only sees and gets suggestions for their own tags and tasks. A can't filter, read or attach B's tags. Admins still can't see any user's tasks or tags (A5 AC10 stays true).

### Compatibility
- [ ] AC15: Given an existing task from before this feature, then after the migration it has priority None, no tags, and status "Done" if it was done, otherwise "To do".
- [ ] AC16: Given a client sends `PUT /api/tasks/{id}` without `priority`, `status` or `tags`, then those fields are left unchanged. Given a client sends `PATCH {done: true}`, then status becomes Done; `{done: false}` makes it To do.

### States
- [ ] AC17: While saving priority, status or tags, the dialog's controls are disabled; on failure the dialog stays open with an error banner and "Retry", and my input is kept. Server field errors (e.g. too many tags) appear under the right field. No browser console errors in normal use.
- [ ] AC18: Every existing AC in `specs/tasks.md` (AC1–AC15) still passes, with "done" read as "status Done" and the dialog's "Done" checkbox replaced by the Status field.

## Out of scope
- Sorting by priority or status, text search, pagination (T2)
- Tag colours, a tag management page, renaming or deleting a tag everywhere
- `#tag` or `!high` syntax in the quick-add box
- Filtering by priority, or by more than one tag at once
- Sharing tags or tasks between users (T3)
- Dropping the `done` column (later, separately approved migration)

## UI notes
- **Row:** checkbox, title (struck through when Done), then badges in this order: priority, "In progress", due date / "Overdue", tag chips (clickable), then "Edit" and "Delete". On phone width the badges and chips wrap under the title.
- **Edit dialog:** title, description, Priority (select: None / Low / Medium / High), Status (select: To do / In progress / Done — replaces the "Done" checkbox), due date, Tags (chip input with suggestions), "Save" / "Cancel".
- **Tabs:** All / Active / In progress / Done. Tag filter bar ("Tag: Work ×") shown between the tabs and the list when `?tag=` is set.
- Chip input is built by hand (no new dependency), keyboard-usable: Enter/comma adds, Backspace on an empty input removes the last chip, arrow keys + Enter pick a suggestion.
- Plain SCSS, no UI library.

## Constraints
- **Migration V9 (approval gate):** add `tasks.priority` and `tasks.status` (NOT NULL with defaults + CHECK), backfill `status` from `done`, add a `tags` table (`owner_id` FK → users `ON DELETE CASCADE`, unique per owner on the name) and a `task_tags` join table (FKs → tasks and tags, `ON DELETE CASCADE`), adjust the index for the new filters. Keep `done` (expand-then-contract). Backend proposes the exact SQL before writing it.
- **API (additive only):** `Task` gains `priority`, `status`, `tags` (array of names). `GET /api/tasks` gains `filter=in_progress` and `tag=<name>`. A way to list the user's own tags for suggestions (e.g. `GET /api/tags`). Nothing renamed or removed. Backend finalizes it in `contracts/openapi.yaml` (next version 0.10.0).
- `done` and `status` must never disagree. While both exist the backend writes both on every change.
- All existing tasks rules stay: owner is the signed-in user, another user's task → 404, errors are RFC 7807 `ProblemDetail`, no optimistic updates.
- No auth or security config changes. No new dependencies.

## Open questions (my recommendation in **bold** — answer e.g. "1. yes 2. …")

1. **Priority values?** **None (default) / Low / Medium / High**, a small coloured badge on the row (nothing for None), set in the edit dialog.
2. **Status values?** **To do / In progress / Done.** The checkbox keeps working: ticking → Done (also from In progress), unticking → To do. Status is set in the edit dialog, replacing its "Done" checkbox. "In progress" shows a badge on the row.
3. **What happens to `done`?** **Expand-then-contract:** add `status`, backfill from `done`, keep `done` in the API as a read-only mirror so `PATCH {done}` and old clients keep working. `PATCH` also accepts `{status}`. Dropping the column is a later, separately approved migration.
4. **Filters?** **Tabs All / Active / In progress / Done** (`?filter=in_progress`). Active = not Done. "Overdue" still means not Done and past due.
5. **List order?** **Unchanged in T1** (not-done first, newest first; priority doesn't reorder). Sorting belongs to T2.
6. **What are tags?** **Free-text, private per user:** 1–30 characters, trimmed, inner spaces collapsed to one, unique per user ignoring case and accents, max 10 per task. Chip input in the edit dialog with suggestions from your own tags. No colours.
7. **Tag filter?** **Yes, simple:** click a tag on a row → `?tag=<name>` combined with the tab, a "Tag: Work ×" bar to clear it. One tag at a time.
8. **Managing tags?** **No page in T1:** a tag disappears automatically when no task uses it. No rename/delete-everywhere.
9. **Quick-add box?** **Unchanged:** title only; new tasks are None / To do / no tags.
10. **PUT and the new fields?** **Optional in `PUT`; missing = left unchanged** (an old client can't wipe them). Sent `tags: []` clears them. Backend confirms during the contract.
11. *(new)* **Which characters are allowed in a tag?** **Anything except a comma** (comma is the "add" key) and control characters. Emoji and accents are fine.
12. *(new)* **Which spelling wins for "Work" vs "work"?** **The first spelling you used is kept** for that tag; typing another case/accent variant reuses it. (Changing a tag's spelling = remove it everywhere, then add it again — or rename later.)
13. *(new)* **Cap on how many different tags a user can have?** **Yes, 200 per user**, error "You can have at most 200 tags" — keeps the suggestion list and the database bounded. Alternative: no cap in T1.
14. *(new)* **Show the priority badge on Done tasks?** **Yes, but faded** like the rest of a done row, so the information isn't lost.

## Approvals needed before build
- **Migration V9** (schema change) — exact SQL shown to you before it is written.
- API change is additive only (no rename/removal), so no gate expected there. No auth/security change, no new dependency expected.

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-16 | Q1–Q14: all recommendations accepted (priority None/Low/Medium/High; status To do/In progress/Done with checkbox → Done/To do; `done` kept as read-only mirror, PATCH accepts `{done}` or `{status}`; tabs All/Active/In progress/Done; order unchanged; tags private, 1–30 chars, collapsed spaces, unique ignoring case+accents, ≤10/task, no comma/control chars, first spelling kept, ≤200 tags/user; one-tag filter `?tag=`; no tag page, unused tags vanish; quick-add unchanged; PUT new fields optional = unchanged, `tags: []` clears; faded priority badge on Done) | User answered "yes" to all | User |
| 2026-10-16 | Spec approved | — | User |
| 2026-10-16 | Contract 0.10.0 (round 1): Task gains `priority`, `status`, `tags` (sorted ignoring case/accents); `done` read-only mirror. POST ignores new fields. PUT: `done` now optional; new fields missing/null = unchanged; `tags` replaces, `[]` clears; `done`+`status` disagreeing → 400 `STATUS_CONFLICT`; `done:false` alone only turns DONE into TODO (IN_PROGRESS kept). PATCH `{done}` and/or `{status}`, neither → 400 `REQUIRED`. GET `filter=in_progress`, `tag=` (ignore case/accents, unknown/invalid → `[]`, never 400). New `GET /api/tags`. Tag errors on field `tags`: `REQUIRED` (blank rejected), `INVALID_CHARACTERS`, `TOO_LONG`, `TOO_MANY`, `TAG_LIMIT` (alone, nothing saved). Bad enum → `MALFORMED_REQUEST`. Unused tags deleted in the same transaction. Unicode spaces (e.g. U+00A0) count as spaces | Additive; old clients keep working; matches frontend needs | PM |
| 2026-10-16 | PM decision: `GET /api/tags` returns objects `[{ "name": "Work" }]`, not `string[]` | Both agents noted objects can grow (colours, counts) without a breaking change later | PM |
| 2026-10-16 | Frontend sends `PATCH {status}` for the checkbox and `status` (never `done`) in PUT; drops blank tags before sending; the client-side tag key (NFD, strip marks, lowercase) is only a hint, the server's collation decides and the UI shows the names the server returns | Avoids depending on the `done` mirror that will be dropped | PM |
| 2026-10-16 | Tag bar shows the stored spelling (from loaded tasks / tags list), falling back to the raw `?tag=` value; with a tag filter set, the empty message is "No tasks with this tag" on every tab; a task that stops matching tab/tag after save disappears (same as tabs today) | Fills gaps in AC12 | PM |
| 2026-10-16 | Frontend uses its own task error types; no edits under `core/auth` (as in 2026-10-08) | Keeps clear of the auth approval gate | PM |
| 2026-10-16 | Backend locks the user's row (`SELECT … FOR UPDATE`) in transactions that change tags | Prevents races on new/unused tags; makes the 200 cap exact; read-only on `users`, no auth code change | PM |
| 2026-10-16 | Migration V9 approved as proposed, including `ck_tasks_status_matches_done` (priority/status columns + strict CHECKs, backfill, index `ix_tasks_owner_status_created`, `tags` and `task_tags` tables; `done` kept) | Storage for AC2–AC15 | User |
