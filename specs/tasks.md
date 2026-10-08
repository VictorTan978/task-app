# Feature: Task management

**Status:** APPROVED 2026-10-08 (the V2 migration still needs its own approval). **Depends on:** `specs/auth.md` (must be DONE first)

## Why
Signed-in users need a simple personal to-do list: capture tasks quickly, track what is done, and keep an eye on due dates.

## User story
As a signed-in user, I want to add, edit, complete and delete my own tasks so that I can keep track of what I need to do.

## Acceptance criteria

### Create
- [ ] AC1: Given I am on `/tasks`, when I type a title in the add box at the top and press Enter or click "Add", then the task appears in the list as not done, and the add box clears.
- [ ] AC2: Given I am on `/tasks`, when I try to add a title that is empty or only spaces, then nothing is created. A title longer than 200 characters is rejected with "Title must be 200 characters or fewer".

### List and filter
- [ ] AC3: Given I have no tasks, when I open `/tasks`, then I see "No tasks yet — add your first one above".
- [ ] AC4: Given I have done and not-done tasks, when I view the "All" filter, then not-done tasks appear first and done tasks after them, each group newest first.
- [ ] AC5: Given I have tasks, when I pick "Active" or "Done", then only matching tasks are shown and the URL becomes `/tasks?filter=active` or `?filter=done`. Refreshing the page keeps the filter. When "Done" has no tasks, I see "Nothing done yet".
- [ ] AC6: Given a task has a due date, then the list shows that date. Given a not-done task has a due date before today (browser local date), then it shows a red "Overdue" label. Done tasks never show "Overdue".

### Complete
- [ ] AC7: Given a task is not done, when I tick its checkbox, then it is marked done, shown struck through, and moves into the done group. Unticking reverses this. The change persists after a refresh.

### Edit
- [ ] AC8: Given a task exists, when I click it or its "Edit" button, then a dialog opens with title, description, due date and done. When I change fields and click "Save", then the dialog closes and the list shows the changes, which persist after a refresh.
- [ ] AC9: Given the edit dialog is open, when I enter an empty title, a title over 200 characters or a description over 2,000 characters, then "Save" is rejected and an error appears under the field. When I click "Cancel", nothing changes.
- [ ] AC10: Due date is optional and date-only. A past date is accepted, and I can clear a due date.

### Delete
- [ ] AC11: Given a task exists, when I click "Delete", then a confirm dialog asks "Delete '<title>'?". On "Delete" the task is removed permanently, also after a refresh. On "Cancel" nothing changes.

### Ownership
- [ ] AC12: Given users A and B each have tasks, when A views `/tasks`, then A sees only A's tasks.
- [ ] AC13: Given task 42 belongs to user B, when user A calls `GET`, `PUT`, `PATCH` or `DELETE /api/tasks/42`, then the API returns 404 and B's task is unchanged.

### States
- [ ] AC14: While the list is loading, a loading indicator is shown. While a create, edit, toggle or delete is in progress, its control is disabled.
- [ ] AC15: When an API call fails (e.g. the server is down), an error banner appears with a "Retry" button, and the list stays as it was. No browser console errors occur in normal use.

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
