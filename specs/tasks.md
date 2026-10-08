# Feature: Task management

**Status:** DONE 2026-10-08 (approved 2026-10-08; V2 migration approved and applied). **Depends on:** `specs/auth.md` (must be DONE first)

## Why
Signed-in users need a simple personal to-do list: capture tasks quickly, track what is done, and keep an eye on due dates.

## User story
As a signed-in user, I want to add, edit, complete and delete my own tasks so that I can keep track of what I need to do.

## Acceptance criteria

### Create
- [x] AC1: Given I am on `/tasks`, when I type a title in the add box at the top and press Enter or click "Add", then the task appears in the list as not done, and the add box clears.
- [x] AC2: Given I am on `/tasks`, when I try to add a title that is empty or only spaces, then nothing is created. A title longer than 200 characters is rejected with "Title must be 200 characters or fewer".

### List and filter
- [x] AC3: Given I have no tasks, when I open `/tasks`, then I see "No tasks yet — add your first one above".
- [x] AC4: Given I have done and not-done tasks, when I view the "All" filter, then not-done tasks appear first and done tasks after them, each group newest first.
- [x] AC5: Given I have tasks, when I pick "Active" or "Done", then only matching tasks are shown and the URL becomes `/tasks?filter=active` or `?filter=done`. Refreshing the page keeps the filter. When "Done" has no tasks, I see "Nothing done yet".
- [x] AC6: Given a task has a due date, then the list shows that date. Given a not-done task has a due date before today (browser local date), then it shows a red "Overdue" label. Done tasks never show "Overdue".

### Complete
- [x] AC7: Given a task is not done, when I tick its checkbox, then it is marked done, shown struck through, and moves into the done group. Unticking reverses this. The change persists after a refresh.

### Edit
- [x] AC8: Given a task exists, when I click it or its "Edit" button, then a dialog opens with title, description, due date and done. When I change fields and click "Save", then the dialog closes and the list shows the changes, which persist after a refresh.
- [x] AC9: Given the edit dialog is open, when I enter an empty title, a title over 200 characters or a description over 2,000 characters, then "Save" is rejected and an error appears under the field. When I click "Cancel", nothing changes.
- [x] AC10: Due date is optional and date-only. A past date is accepted, and I can clear a due date.

### Delete
- [x] AC11: Given a task exists, when I click "Delete", then a confirm dialog asks "Delete '<title>'?". On "Delete" the task is removed permanently, also after a refresh. On "Cancel" nothing changes.

### Ownership
- [x] AC12: Given users A and B each have tasks, when A views `/tasks`, then A sees only A's tasks.
- [x] AC13: Given task 42 belongs to user B, when user A calls `GET`, `PUT`, `PATCH` or `DELETE /api/tasks/42`, then the API returns 404 and B's task is unchanged.

### States
- [x] AC14: While the list is loading, a loading indicator is shown. While a create, edit, toggle or delete is in progress, its control is disabled.
- [x] AC15: When an API call fails (e.g. the server is down), an error banner appears with a "Retry" button, and the list stays as it was. No browser console errors occur in normal use.

## Out of scope
- Priority, multi-state status (todo / in progress / done), tags or labels
- Text search, custom sorting, pagination
- Sharing tasks or team lists
- Due times, reminders, notifications, recurring tasks
- Undo, soft delete, trash
- Drag-and-drop reordering
- Bulk actions (e.g. "clear all done")

## UI notes
- `/tasks` (signed in only): header from auth, an add box at the top, All / Active / Done filter tabs, then the list.
- Each row: a done checkbox, the title (struck through when done), a due date and "Overdue" label if relevant, and "Edit" and "Delete" buttons.
- Edit: a modal dialog with title, description (textarea), due date (date picker), done, and "Save" / "Cancel".
- Plain SCSS with no UI library.

## Constraints
- Task fields: `id`, `title` (required, trimmed, 1–200 characters), `description` (optional, at most 2,000 characters), `done` (boolean, default false), `dueDate` (optional, date only), `createdAt`, `updatedAt`. The owner is the signed-in user and is never sent by the client.
- Proposed API (the backend finalizes it in `contracts/openapi.yaml`): `GET /api/tasks?filter=all|active|done`, `POST /api/tasks`, `GET /api/tasks/{id}`, `PUT /api/tasks/{id}`, `PATCH /api/tasks/{id}` (toggle done), `DELETE /api/tasks/{id}`.
- Every task endpoint requires a signed-in user (401 otherwise). Another user's task returns 404.
- Errors use RFC 7807 `ProblemDetail`.

## Approvals needed before build
- Migration `V2__create_tasks.sql`: a `tasks` table with an `owner_id` foreign key to `users`, `ON DELETE CASCADE`.

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-08 | v1 fields: title, description, done, due date, timestamps | Covers core to-do needs; priority, status and tags deferred | User |
| 2026-10-08 | v1 actions: create, list, edit, delete, toggle done, filter All/Active/Done | Search and pagination unnecessary for small personal lists | User |
| 2026-10-08 | Another user's task returns 404, not 403 | Doesn't reveal that the task exists | User |
| 2026-10-08 | Title 1–200, description ≤ 2,000, past due dates allowed, red "Overdue" label | Lets users log missed deadlines; clear visual cue | User |
| 2026-10-08 | Quick-add box plus edit dialog | Fast capture, simple full editing | User |
| 2026-10-08 | Confirm dialog, then hard delete | Simple; prevents accidental loss | User |
| 2026-10-08 | Not-done first, then done, each newest first; filter kept in the URL | Active work stays on top; filter survives refresh | User |
| 2026-10-08 | Plain SCSS, no UI library | Enough for three screens; avoids a new dependency | User |
| 2026-10-08 | Spec approved | — | User |
| 2026-10-08 | Q1: "Add" is disabled while the title is blank (no message); no `maxlength`, so a title over 200 characters shows the error; length is checked after trimming | Blank needs no message; the length error must be reachable | User |
| 2026-10-08 | Q2: "Newest first" means by `createdAt` descending (then `id`); ticking a task doesn't reorder it within its group | There is no `completedAt` field | User |
| 2026-10-08 | Q3: In "Active", a ticked task disappears once the server confirms; an empty Active view shows "Nothing left to do"; an invalid `?filter` is treated as "All" | Fills gaps in AC5 | User |
| 2026-10-08 | Q4: `PATCH /api/tasks/{id}` sets `{done}` instead of flipping it | Safe to repeat; two tabs can't undo each other's tick | User |
| 2026-10-08 | Q5: No optimistic updates; "Retry" re-runs the request that failed (or reloads the list if the list load failed) | Matches AC15: the list stays as it was on failure | User |
| 2026-10-08 | Q6: Description is trimmed, blank is stored as `null`, and the 2,000 limit is checked after trimming | Consistent with title handling | User |
| 2026-10-08 | Q7: Clicking the title opens the edit dialog; the checkbox and buttons don't | Avoids opening the dialog by accident | User |
| 2026-10-08 | Plan approved | — | User |
| 2026-10-08 | Description error: "Description must be 2,000 characters or fewer"; title/description trimmed with Java `strip()`, length = UTF-16 units. Note: `strip()` keeps U+00A0/U+FEFF, which JS `trim()` removes; accepted as harmless (server field errors are shown) | Near-identical counting on client and server | PM |
| 2026-10-08 | Only the busy row is disabled while its request runs; other rows stay usable | AC14 covers the busy control only | PM |
| 2026-10-08 | Failed save/delete inside a dialog: dialog stays open with its own error banner + "Retry" (re-runs the request); user input is kept | A modal hides the page banner; still meets AC15 | PM |
| 2026-10-08 | Contract v0.2.0 agreed (round 1, frontend ACCEPT): GET/POST `/api/tasks`, GET/PUT/PATCH/DELETE `/api/tasks/{id}`; bare `Task[]` sorted done asc, createdAt desc, id desc; unknown filter → all; PATCH `{done}` sets; PUT null clears description/dueDate; 404 `NOT_FOUND` for missing/other user's task; invalid dueDate → 400 `MALFORMED_REQUEST`; FieldError `field` enum extended with title/description/done (additive) | Covers all frontend needs; additive only | PM |
| 2026-10-08 | Frontend uses its own task field-error type (reusing `Problem`/`readProblem`); no edits under `core/auth` or `features/auth` | Keeps auth code untouched, avoiding the auth approval gate | PM |
| 2026-10-08 | 404 on toggle/edit/delete: show "This task no longer exists", close any dialog and reload the list; no Retry for 404 | Retrying a 404 would only fail again | PM |
| 2026-10-08 | Migration `V2__create_tasks.sql` approved (tasks table, owner_id FK ON DELETE CASCADE, index on owner_id, done, created_at DESC, id DESC) | Storage for tasks | User |
| 2026-10-08 | Backend built (95 tests green); V2 applied to local DB on backend restart | — | PM |
| 2026-10-08 | Frontend: "All" tab is plain `/tasks` (no `?filter=all`); an invalid `?filter` shows All without rewriting the URL; the row checkbox shows the click while its request runs (disabled), but strike-through/move happen only after the server confirms, and it reverts on failure | Meets AC5/AC7/Q5; keeps Playwright `check()` usable | PM |
| 2026-10-08 | Tester: AC1–AC15 all PASS in round 1 (e2e 41/41, `frontend/e2e/tasks.spec.ts`); backend 95 tests, frontend lint/130 unit tests/build green; no console errors | Definition of Done met | PM |
