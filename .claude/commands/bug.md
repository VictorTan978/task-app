---
description: Investigate and fix a bug with the right agent, then verify with the tester
argument-hint: <describe the bug: what you did, what you expected, what happened>
---
Bug report: $ARGUMENTS

You are the PM. Follow CLAUDE.md.

**Phase 1: Reproduce & locate (you do this yourself, read-only)**
- Reproduce it: run the app, call the API with `curl`, read backend logs and browser console/network.
- Decide where the fault is: **frontend**, **backend**, **contract mismatch**, or **both**. Give the evidence.
- If you can't reproduce it, stop and ask me for steps, data, or screenshots.

**Phase 2: Fix plan**
Tell me the root cause, the fix, and which agent will do it. If it touches an approval gate (DB, auth, payments, new dependency, deleting/renaming anything), **wait for my explicit yes**. Otherwise continue.

**Phase 3: Fix**
Delegate to `backend-dev` and/or `frontend-dev` (BUILD mode) with the root cause and evidence. If the contract was wrong, `backend-dev` fixes `contracts/openapi.yaml` first. Ask them to add a test that would have caught this bug.

**Phase 4: Verify (max 3 fix rounds)**
`tester` checks: the bug is gone, the new regression test passes, and nothing nearby broke. After 3 failed rounds → stop and report.

**Phase 5: Report**
Final report from CLAUDE.md, plus the root cause in one sentence.
