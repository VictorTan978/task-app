---
description: Build a new feature end-to-end with the backend, frontend, and tester agents
argument-hint: <feature-name> (matches specs/<feature-name>.md)
---
Feature: $ARGUMENTS

You are the PM. Follow CLAUDE.md. Run this pipeline and announce each phase in one line as you start it.

**Phase 0: Spec check**
Read `specs/$ARGUMENTS.md`. If it doesn't exist or has no acceptance criteria, draft it from `specs/_TEMPLATE.md` using what I've described, list any open questions, and **wait for my approval or edits** before going further. If it exists, flag anything ambiguous before going further.

**Phase 1: Plan**
Summarise: what UI is needed, what API is probably needed, and whether any approval gate (DB migration, auth, payments, new dependency, deleting/renaming anything) is likely. Show me this plan and **wait for my OK**.

**Phase 2: Contract negotiation (max 2 rounds)**
1. `frontend-dev` in NEEDS mode → UI plan + data needs.
2. `backend-dev` in CONTRACT mode with the spec and those needs → updates `contracts/openapi.yaml`.
3. `frontend-dev` in REVIEW mode → ACCEPT or PUSHBACK.
4. On PUSHBACK, send it to `backend-dev` once more. Still not agreed → you decide, or ask me.
Log the agreed contract in the spec's Decisions log.

**Phase 3: Approval gates**
If any agent reported something needing approval, stop and ask me using `AskUserQuestion`. Give: what, why, affected files, risks/side effects, and reversible?. Offer 2–4 options with the recommended one first, marked **(Recommended)**, with pros and cons for each and why it's recommended. Continue only on my explicit choice.

**Phase 4: Build**
`backend-dev` in BUILD mode, then `frontend-dev` in BUILD mode with the backend's "Notes for frontend". Both must report green checks.

**Phase 5: Test (max 3 fix rounds)**
`tester` with the spec path. On FAIL, send each bug to the side it points to, then re-test. After 3 rounds still failing → stop and report to me.

**Phase 6: Code review (max 3 fix rounds)**
Once `tester` passes and the branch is committed, run the `code-review` skill at `high` level on this branch. Don't use `--fix`: send each finding to the side it points to, then re-run `tester` and `code-review`. If a finding isn't worth fixing, put it in the report with the reason. After 3 rounds with important findings still open → stop and report to me.

**Phase 7: Report**
Update the spec's Decisions log and give me the Final report from CLAUDE.md.
