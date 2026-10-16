# Feature: Tasks — search, sort and pagination

**Status:** DONE 2026-10-16 (approved 2026-10-16; no migration needed). **Depends on:** `specs/tasks.md`, `specs/tasks-priority-status-tags.md` (both DONE). **Branch:** `feature/tasks-search-sort-pagination`

## Why
Once a user has hundreds of tasks, the list loads everything at once, can only be read in one order, and the only way to find a task is to scroll. Search, a choice of sort order and loading the list in pages keep it fast and usable as it grows.

## User story
As a signed-in user, I want to search my tasks, choose how the list is sorted, and have long lists load in pages, so that I can find the task I need quickly and the page stays fast.

## Acceptance criteria

### Search
- [x] AC1: Given I have tasks titled "Buy milk" and "Call Bob", and a task whose description contains "milk", when I type "MILK" in the search box, then after a short pause (about 300 ms, or at once on Enter) only the two tasks mentioning "milk" are shown, and the URL gets `?q=MILK`. A refresh keeps the search and the text in the box.
- [x] AC2: Given a task titled "Café meeting", when I search "cafe", then it is found (search ignores case and accents). Searching "50%" or "a_b" matches those characters literally (no wildcards).
- [x] AC3: Given a search is active, when I switch tab (All / Active / In progress / Done) or click a tag, then the search stays and combines with them (`?filter=…&tag=…&q=…`). When I click the "×" in the search box (or empty it), then the search is cleared, `q` leaves the URL and the full list for the tab comes back.
- [x] AC4: Given a search matches nothing, then I see `No tasks match "<text>"` (instead of the tab's usual empty message). A search of only spaces counts as no search. The box accepts at most 100 characters.

### Sort
- [x] AC5: Given I have tasks, when I open the "Sort" select, then I can pick Newest (default), Oldest, Due date, Priority and Title A–Z; the list re-orders and the URL gets `?sort=oldest|due|priority|title` (Newest = no `sort` in the URL). A refresh keeps it; an unknown `?sort=` value shows Newest.
- [x] AC6: Given tasks with and without due dates, when I sort by Due date, then the soonest date comes first and tasks with no date come last. Priority sorts High, Medium, Low, None. Title A–Z ignores case and accents. Tasks that tie are ordered newest first.
- [x] AC7: Given the All tab with done and not-done tasks, when any sort is chosen, then not-done tasks still come first and done tasks after them, each group in the chosen order.
- [x] AC8: Given a sort, search, tag and tab are all set, then they all apply together, and each survives a refresh and the browser Back button.

### Pagination
- [x] AC9: Given I have 120 tasks in the current view, when I open `/tasks`, then the first 50 are shown with "Showing 50 of 120" and a "Load more" button. Clicking it appends the next 50 ("Showing 100 of 120"); after the last page the button disappears and the count reads "120 tasks". With 50 or fewer tasks there is no button, only "N tasks".
- [x] AC10: Given I have loaded two pages, when I tick, edit or delete a task, then the loaded list is updated in place (no full reload, scroll position kept), the "of N" total adjusts, and a task that no longer matches the tab, tag or search disappears (as today). Clicking "Load more" afterwards shows no task twice and skips none.
- [x] AC11: Given the sort is Newest, when I add a task with the quick-add box, then it appears at the top and the total goes up by one. With any other sort, or when a search/tag/Done tab is active, the task is created and the total adjusts, and it appears only if it falls inside what is already loaded (otherwise a short "Task added" message is shown).
- [x] AC12: Given I change the tab, tag, search or sort, then the list starts again from the first page.

### States and errors
- [x] AC13: While the first page loads, the list shows its loading state; while "Load more" runs, the button is disabled and shows that it is loading. If loading a page fails, an error with "Retry" appears and the items already loaded stay on screen. No browser console errors in normal use.

### Privacy and compatibility
- [x] AC14: Given users A and B, then search, sort and pages only ever include the signed-in user's own tasks. Admins still can't see any user's tasks (A5 AC10 stays true).
- [x] AC15: Every existing AC in `specs/tasks.md` (AC1–AC15) and `specs/tasks-priority-status-tags.md` (AC1–AC18) still passes.
- [x] AC16: Given about 10,000 tasks for one user, each sort/search/tab combination returns the first page in under 300 ms on the dev machine (backend measures with query plans).

## Out of scope
- Searching tags (they have their own filter), search highlighting, search syntax (`tag:`, quotes, `-word`)
- Full-text / relevance ranking, fuzzy matching
- Page numbers, infinite scroll on its own (the button is the trigger), a choice of page size
- Remembering the sort choice across visits (it lives in the URL only)
- Sorting by status or by last updated; ascending/descending toggle per option
- Dropping the `done` column (later, separately approved migration)

## UI notes
- **Toolbar** between the add box and the tabs: search box (magnifier icon, placeholder "Search tasks", "×" clear button when not empty) and a "Sort" select on the right. On phone width the search box takes the full row and the Sort select goes under it.
- Below the list: "Showing X of Y" and a centred "Load more" button when more exist; "N tasks" when everything is loaded.
- Typing in the search box replaces the URL entry (no new Back step per keystroke); changing sort/tab/tag adds one.
- Plain SCSS, no UI library, no new dependency.

## Constraints
- **API (breaking → approval gate):** `GET /api/tasks` gains `q`, `sort` and paging parameters and returns a page object instead of a bare array (shape decided by the backend in the contract, next version **0.11.0**). Only our own frontend uses it; it is switched over in the same branch.
- Unknown `filter`/`sort` → default, never 400 (as today). `q` over 100 characters → backend decides 400 vs truncation in the contract.
- Search uses `LIKE '%…%'` with `%`, `_`, `\` escaped, on the existing `utf8mb4_0900_ai_ci` collation. No FULLTEXT.
- **Migration V10 only if needed** (new indexes) → approval gate; backend shows the exact SQL and the query plans first.
- All existing tasks rules stay: owner = signed-in user, other user's task → 404, RFC 7807 `ProblemDetail` errors, no optimistic updates.
- No auth or security config changes. No new dependencies.

## Open questions (my recommendation in **bold** — answer e.g. "1. yes 2. …", or just "yes" for all)

1. **What does search look at?** **Title and description** (not tags — they have their own filter). Case- and accent-insensitive "contains", `?q=` in the URL, combined with tab and tag. Max 100 characters; blank = no search.
2. **How is search done in the database?** **Plain `LIKE '%…%'`** with `%`, `_`, `\` escaped, using the existing collation. Simple and matches parts of words; fine for personal lists. (MySQL FULLTEXT can't match inside words and needs a heavier migration.)
3. **When does the list search?** **As you type, 300 ms after the last key, or at once on Enter;** "×" clears. Empty result: `No tasks match "<text>"`.
4. **Sort options?** **A "Sort" select: Newest (default, today's order), Oldest, Due date (soonest first, no date last), Priority (High → None), Title A–Z.** `?sort=` in the URL; unknown → Newest. Ties: newest first, then id.
5. **Done tasks still at the bottom when sorting?** **Yes, in All:** not-done group first, each group sorted by the chosen option, so finished work never mixes in.
6. **Pagination style?** **"Load more" button, 50 per page**, with "Showing 50 of 312". Simpler on phone than page numbers and keeps the list in one place.
7. **API shape for pages?** **A page object `{ items, total, nextCursor }`** (breaking change to `GET /api/tasks` → approval gate; only our frontend uses it). Alternative: keep the bare array and send the total in an `X-Total-Count` header with `limit`/`offset` — non-breaking but less clear. Backend proposes cursor vs offset in the contract (**cursor preferred**: no duplicates or gaps when tasks are added or ticked while paging).
8. **Loaded pages after an edit, tick, add or delete?** **Update in place as today**, total adjusts, no full reload. A quick-added task appears at the top only when the sort is Newest (see Q11).
9. **Indexes?** **Backend checks query plans for each sort with ~10k tasks per user and proposes only the indexes really needed** (V10 → approval gate). No schema change if plans are fine.
10. **Drop the `done` column now?** **No** — separate small migration later (first move the index and sort onto `status`).
11. *(new)* **Quick-add when the new task doesn't belong in what's on screen** (sort isn't Newest, a search/tag filter is active, or the Done tab)? **Create it, adjust the total, and show it only if it matches the view and sorts inside the loaded items; otherwise show a short "Task added" message** so the user knows it worked. Alternative: always reset to the first page after adding.
12. *(new)* **A ticked task in All when the done group isn't loaded yet?** **It leaves the not-done group; it shows in the done group only if that part is already loaded** — otherwise it appears later via "Load more" (no duplicates, no gaps — AC10).
13. *(new)* **Show the count when everything is loaded?** **Yes, but simpler: "12 tasks"** when there's no more to load; "Showing 50 of 312" only when there is.
14. *(new)* **Back button and search typing?** **Typing replaces the URL entry** (Back doesn't step through every keystroke); sort/tab/tag changes add a history entry.
15. *(new)* **Performance target?** **First page under 300 ms with ~10k tasks per user** on the dev machine (AC16). Just a sanity bar, not a load test.

## Approvals needed before build
- **API change:** `GET /api/tasks` returns a page object instead of a bare array (breaking) — if Q7 is accepted.
- **Migration V10** only if new indexes are needed — exact SQL and query plans shown to you first.
- No auth/security change, no new dependency expected.

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-16 | Q1–Q15: all recommendations accepted (search title+description, ignore case/accents, `?q=`, max 100, blank = none; `LIKE '%…%'` with `%` `_` `\` escaped, no FULLTEXT; search 300 ms after typing or on Enter, "×" clears; sorts Newest/Oldest/Due date/Priority/Title A–Z, `?sort=`, ties newest then id; done group stays last in All; "Load more", 50 per page; page object `{ items, total, nextCursor }`, cursor preferred; in-place updates; indexes only if plans need them (V10 gate); keep `done`; quick-add outside the view → "Task added"; ticked task appears via "Load more" if done group not loaded; "N tasks" when all loaded; search typing replaces the URL entry; first page < 300 ms at ~10k tasks) | User answered "yes" to all after asking what escaping and FULLTEXT meant | User |
| 2026-10-16 | Spec approved, including the breaking change to `GET /api/tasks` (page object `{ items, total, nextCursor }`, contract 0.11.0). V10 still a separate gate | User said "yes" at the approval gate | User |
| 2026-10-16 | Contract 0.11.0 agreed in round 1: `GET /api/tasks` → `TaskPage { items (≤50), total, nextCursor }` (all always present); params `filter`, `tag` (unchanged; invalid tag → empty page), `q` (trimmed, blank = none, `%` `_` `\` literal), `sort` (`newest`/`oldest`/`due`/`priority`/`title`, unknown → newest), `cursor`. Page size fixed at 50, no `limit`. `nextCursor` null on the last page (fetch 51). `total` recounted on every page | Both agents' needs matched | PM |
| 2026-10-16 | Cursor: keyset, self-contained (sort-key values of the last item + hash of filter/sort/tag/q), base64url without padding, unsigned (owner always from the session). Invalid, tampered or mismatched cursor → 400 `VALIDATION_FAILED` field `cursor` code `INVALID_CURSOR`; the frontend restarts from page 1 | A silent restart would duplicate items (AC10); signing would need a new secret (security config) for no gain | PM |
| 2026-10-16 | `q` over 100 characters → 400 `VALIDATION_FAILED` field `q` code `TOO_LONG` (not truncated); the box caps at 100 | Truncating would silently search for different text | PM |
| 2026-10-16 | Fills spec gaps: Oldest = `createdAt` ASC, `id` ASC (exact reverse of Newest); `due`/`priority`/`title` ties → `createdAt` DESC, `id` DESC; title ties follow the collation ("cafe" = "Café"); search + tag with no results shows `No tasks match "<text>"` | "Ties newest first" doesn't make sense for Oldest; the rest settles details the spec left open | PM |
| 2026-10-16 | AC10 detail: the client de-duplicates by `id` when a page arrives, and keeps a task whose title position is uncertain rather than dropping it. A task moved before the cursor from another tab/device appears only after the list restarts | Keyset can't see changes made elsewhere; a wrong drop would skip a task, a wrong keep only misplaces a row | PM |
| 2026-10-16 | Indexes: backend expects no V10 (existing owner indexes + top-N sort); it verifies with EXPLAIN ANALYZE on ~10k tasks during the build | V10 stays an approval gate if the plans miss 300 ms | PM |
| 2026-10-16 | No V10: worst first page 24 ms (backend) / 34 ms (tester) at 10k tasks per user with existing indexes | Under the 300 ms target | PM |
| 2026-10-16 | Tester: AC1–AC16 PASS; full e2e 223 passed, 2 failed (email-auth:400, security-logging:36 — timing-flaky under parallel load, pass alone, not T2), 15 env-gated skipped. Pre-existing race (PUT/PATCH racing DELETE on the same task → 500 `ObjectOptimisticLockingFailureException`, 12/300 tries) left out of T2 as a follow-up | Not caused by T2; no unrequested fixes | PM |
