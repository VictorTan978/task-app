# Next session: fix the intermittent e2e failures

Written 2026-10-21, after T5b was merged. Start a new Claude Code session in `/Users/victor/task-app` and paste the prompt below. Do this before T5c (`docs/next-session-T5c-drag-and-drop.md`), so that T5c starts from a green suite.

## Prompt to paste

```
Read docs/next-session-e2e-flakes.md and CLAUDE.md. You are the PM. Treat this as a /bug:
the full e2e suite is not reliably green under parallel load. Create fix/e2e-flakes from main,
locate the cause of each flaky test (test bug vs app bug) before delegating, fix, and have tester
prove it with repeated runs. Ask me (CLAUDE.md options format) before any app-code change that
touches auth/security.
```

---

## State on `main` (`5aaf6d2`)

- T5a (trash + undo) and T5b (bulk actions) are DONE and merged. The contract is at 0.17.0. The next migration is V15.
- Baseline:
  - backend: 1503 tests;
  - frontend: lint clean, 1136 unit tests, initial bundle 514.49 kB (warning budget 520 kB);
  - e2e: 486 tests, 22 env-gated skips.
- The suite has not had a fully green full run since T5b. Three runs had 1, 3 and 1 failures, a different test each time, and each one passes when run alone.

## The flaky tests (evidence from the T5b tester, 2026-10-21)

| Test | Symptom | Evidence | Likely kind |
|---|---|---|---|
| `tasks-priority-status-tags.spec.ts:673` (T1 Tags AC10) | Chip shows "work", expected "Work" | Also fails on `main` before T5b: 2 of 24 runs (`--repeat-each=24 --workers=8`). The test types "work" and presses Enter before the suggestions have loaded, so the chip keeps the typed spelling. | Test timing. It could also be an app race: should Enter wait for the suggestions, or should the server normalise the name to an existing tag? Decide which. |
| `google-sign-in.spec.ts:815` (AC8) and `:1085` (AC13) | Console-error count: expected 1, got 3 (all 401 lines) | Passed 24 of 24 on `main` and alone on the branch | Unknown, probably under load |
| `google-sign-in.spec.ts:1304` | Expected 302, got 403 on a start call that sent a valid `_csrf` | Passed 24 of 24 on `main` | Unknown: CSRF token or session race under load? |
| `google-sign-in.spec.ts:1379` (AC19) | A 403 check failed | Not repeated on `main` | Unknown |
| `password-fields.spec.ts:119` | Failed once | Passed 24 of 24 on `main` | Unknown |
| `email-auth.spec.ts:289` | Hangs on `ctx.close()` (an older, known flake) | Did not fail in the T5b runs | Known |

T5b changed no auth, Google, password, security or tag-input code. The only auth-adjacent file it touched is `auth.models.ts` (+2 lines). The likeliest trigger is the extra load from 50 new tests running in parallel. Questions to answer:
- Do the 401 and 403 failures come from shared state between workers, such as rate-limit buckets per IP, shared accounts or session cookies?
- Do they come from a real race in the CSRF or session handling?

## How to run e2e (the tester must follow this)

See the memory note "E2E run setup" for the full list of rules:
- **Ports:** use only an own stack on 8081/4201. The user's servers on 8080/4200 may be running and must not be touched. Never use pkill by pattern; stop only the recorded PIDs.
- **Rate limits:** raise them with the exact env variable names.
- **Reminder polling:** set `REMINDERS_POLL_INTERVAL=2s`.
- **Logs:** set `E2E_BACKEND_LOG` and `BACKEND_LOG`.
- **Proof:** use repeated runs (`--repeat-each=N --workers=8`) for each flaky test, plus 3 consecutive green full runs. Check that passed + failed + skipped adds up to 486.
