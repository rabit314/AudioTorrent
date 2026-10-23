# AudioTorrent — Multi-Agent Collaboration Protocol

**This is the canonical coordination document.** It is designed for sequential or concurrent work by Codex, Claude Code, Gemini CLI, OpenCode/DeepSeek, ChatGPT, or other agents. Agents can be assigned different roles; a model does **not** permanently own a role.

## 1. Session startup (all agents)

1. Identify your **agent name**, **model/version** (say `unknown` if unavailable), **provider**, **harness/tool**, **role**, **task ID**, and time with timezone (prefer `Asia/Dhaka`, ISO 8601 `+06:00`).
2. Read `AI/PROJECT.md` (goal/scope), `AI/TASKS.md` (status/ownership), `AI/ARCHITECTURE.md`, `AI/DECISIONS.md`, `AI/BUGS.md`, `AI/PROGRESS.md`, and `AI/HANDOFF.md` as relevant.
3. Read relevant plans/reviews/test reports/activity logs, especially recent logs for your task.
4. Inspect the **actual repository** and `git status`. Documents may be outdated. Check dependencies and concurrent changes before working.
5. Confirm that the task is available, has needed inputs, and that your assigned role permits the intended actions.

## 2. Roles and permitted outputs

| Role | Expected outputs | Default restriction |
| --- | --- | --- |
| **Planner** | detailed, testable plan in `AI/PLANS/`, analysis of existing code, risks and alternatives | no production code edits |
| **Implementer** | code changes following a specified plan version, tests, implementation report | do not silently redesign approved architecture |
| **Reviewer** | findings in `AI/REVIEWS/` tied to plan/code/commit evidence | no code edits unless explicitly assigned |
| **Tester** | results in `AI/TEST_REPORTS/`, reproduction steps, evidence | no feature rewrites by default |
| **Debugger** | diagnosis, targeted fixes (if authorized), regression checks | keep scope to identified bug |
| **Researcher** | sourced notes, comparisons, unknowns, recommendations | don't treat ideas as approved decisions |
| **Documenter** | accurate project docs and traceable changes | do not fabricate implementation or tests |

One session may hold multiple explicitly assigned roles. **Finishing a planning task does not mean the feature was implemented.** Finishing implementation does not mean it was verified.

## 3. Planner → implementer → reviewer → tester

- **Planner:** Create `AI/PLANS/PLAN-NNN_short-name.md`, with objective, constraints, current-state evidence, component/file changes, ordered steps, interfaces/data flow, edge cases, dependencies, rollback approach, security/legal considerations, tests, and acceptance criteria. Save a version (`v1.0` etc.) and `DRAFT` status; only set `APPROVED` when the user/project owner authorizes it. Link its task in `AI/TASKS.md`.
- **Implementer:** Use the **exact approved plan ID/version**. Recheck assumptions against source code. Record each implemented step, changed files, commits, tests, deviations, and gaps. Significant deviations require user approval or a documented plan revision. If the plan is blocked, report that rather than invent a solution or re-scope silently.
- **Reviewer:** Compare code/diff/commit with *requirements and plan*. Rate findings by severity, cite file paths/lines, distinguish direct evidence from suspicion, and flag missing acceptance criteria. Log review outcome without falsely marking verification.
- **Tester:** Execute available checks and record exact commands, environment, outputs, passes, failures, and items **not run**. Assign `VERIFIED` only to specific behaviors actually demonstrated by appropriate tests.

## 4. Task ownership & concurrency

- Track task IDs, role, owner, dependencies, branch/worktree, related plan version, deliverables, acceptance criteria, and status in `AI/TASKS.md`.
- Suggested task statuses: `TODO`, `IN_PROGRESS`, `BLOCKED`, `READY_FOR_REVIEW`, `COMPLETED`. Verification is a **separate field**: `NOT_TESTED`, `PARTIALLY_TESTED`, `VERIFIED`, `FAILED`, `UNKNOWN`.
- Coordinate concurrent work via **Git branches or worktrees**; don't overwrite uncommitted changes and don't edit another agent's active task/files without coordination.
- Treat `AI/HANDOFF.md` as the **latest operational snapshot**; treat `AI/ACTIVITY_LOGS/` as **append-only historical records**. Avoid replacing historical facts; append corrections with attribution.
- Git diffs, commits and test logs provide stronger evidence than agent self-reports. Do not claim logs are cryptographic signatures.

## 5. Mandatory attribution and timestamps

After each meaningful session, create a unique log file, e.g. `AI/ACTIVITY_LOGS/2026-10-23T132100+0600_chatgpt_TASK-004.md` (avoid filename collisions; add a session suffix if needed). Copy `AI/ACTIVITY_LOGS/_TEMPLATE.md` and complete all relevant fields.

Required fields: **Agent / Model / Provider / Harness / Role / Task / Started / Ended (or in progress) / Work actually done / Deliverables & paths / Files changed / Tests + results / Unknowns & limits / Git refs (if available) / Next steps / Signature**.

**Example signature format:**

> Signed by: ChatGPT — GPT-6 (OpenAI)  
> Role: Reviewer  
> Recorded at: 2026-10-23T13:21:00+06:00

Only use a signature matching **your actual identity** and actual timestamp. Do not backdate. This is provenance documentation, not proof of which model ran. Never claim someone else's work or invent test execution.

## 6. Documentation update rules

- Planning: update plan, task, activity log, and handoff; decisions only when accepted.
- Implementation: update task, activity log, handoff, progress, architecture if changed, bugs if found, and plan deviations.
- Review/testing: write review/test report, update task verification and bugs, activity log, handoff.
- Keep `AI/PROGRESS.md` a concise, factual milestone history. Don't erase earlier authors' contributions.
- Use references such as `TASK-001`, `PLAN-001 v1.0`, `REVIEW-001`, `TEST-001` throughout so they can be traced.

## 7. Security, legal scope, and safety

Never store API keys, cookies, credentials, or personal data in logs. Do not commit `.env` secrets. Get the owner's authorization before destructive actions, publishing/deploying, changing product scope or architecture, or accessing external accounts. Follow licensing and copyright rules; develop and test torrent features only with legally shareable or authorized media. Treat untrusted torrent metadata and files as hostile input.

## 8. Session exit checklist

1. State what was **planned**, **implemented**, **tested**, **verified**, **blocked**, and **not done** distinctly.
2. Save attributed activity log (including actual timestamps).
3. Update task status/verification and relevant documents.
4. Update `AI/HANDOFF.md` with next actionable steps and command(s) to resume.
5. If a task needs follow-up, leave it open and say why. Don't mark whole-project completion on a role-only success.
