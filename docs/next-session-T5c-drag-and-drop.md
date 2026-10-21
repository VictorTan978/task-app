# Next session: T5c — Drag and drop ("My order")

Written 2026-10-21, after T5b was merged. Do the e2e flake fix first (`docs/next-session-e2e-flakes.md`). Then start a new Claude Code session in `/Users/victor/task-app` and paste the prompt below.

## Prompt to paste

```
Read docs/next-session-T5c-drag-and-drop.md and CLAUDE.md. You are the PM.
Create feature/t5c-dnd from main. The spec is already approved: specs/undo-trash-dnd-bulk.md Part C
(AC23–AC28) plus its Decisions log. Go to contract (0.18.0) → build → test. Before building, ask me
the two gates (migration V15, dependency @angular/cdk) in the CLAUDE.md options format.
```

---

## State on `main` (`5aaf6d2`)

| Part | Status |
|---|---|
| T5a trash + undo (contract 0.16.0, V14) | DONE, merged (`cdc2746`) |
| T5b bulk actions (contract 0.17.0, no migration) | DONE, merged (`ca4546c`) |
| **T5c drag and drop** (contract 0.18.0, V15) | **next** |

**Baseline:**
- backend: 1503 tests;
- frontend: lint clean, 1136 unit tests;
- initial bundle: 514.49 kB, against a **520 kB warning budget**;
- e2e: 486 tests, 22 skips. Make sure the flake fix is in first.

## What is already decided (spec Part C + Decisions log)

- **Q10, order:** "My order" is a new `sort=manual` option, **shared by the whole list**, with one position column. New tasks go to the top. Tasks without a position fall back to newest-first.
- **Q11, library:** **`@angular/cdk` drag-drop.** This is a dependency gate: ask before adding it. It must load **only with the tasks route** and never in the initial bundle.
  - The budget has only about 5.5 kB of headroom, so use `@defer` or lazy loading the way T5a and T5b did (the undo logic, the bulk bar).
- **When drag is allowed:**
  - Drag works only in "My order" with filter "All", no tag and no search. Otherwise the handles are hidden and a hint is shown.
  - Done tasks stay in their own group, and you can drag only within a group.
- **Keyboard (AC25):** Space picks a row up, the arrow keys move it, Space drops it and Esc cancels. Each move is announced. The row menu also has "Move up" and "Move down".
- **Paging (AC26):** dragging works within the loaded tasks. "Load more" continues without repeating or skipping tasks.
- **Sidebar (AC27):** dragging a task onto another list in the list picker moves it with the existing move rules and an Undo toast. The Trash entry is not a drop target.
- **Contract sketch (AC28):**
  - `PUT /api/tasks/{id}/position` with `{ afterId | null }`;
  - a cursor version bump, so old cursors give `INVALID_CURSOR` once;
  - on a failed save, the row snaps back and a message is shown.

## Things the build must know

- **Gates:** migration V15 (a position column plus an index for the manual sort) and `@angular/cdk`. Ask before either one.
- **Undo:** reordering is not in the undo list (Q5). Moving to another list through the sidebar is, and it reuses the T5a move undo (`PATCH` with `listId` + the old tags).
- **Trashed tasks:** they are excluded from every query. A restored task keeps its position, or goes to the top; decide which in the contract.
- **Concurrency:** use the existing lock order (users → lists ascending → memberships → tasks by id). Two members reordering at the same time must never cause a 500 or a deadlock. Last write wins.
- **Out of scope:** private order per user, dragging between Done and not-done, dragging several tasks at once, dragging in the Trash.
- **e2e:** the always-present undo toast (`role=status`) covers the bottom of the screen for 8 s. Close it before dragging near the bottom. Follow the "E2E run setup" memory rules: only the 8081/4201 stack, never pkill by pattern.
