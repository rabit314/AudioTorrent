# Shared multi-AI agent entry point

This is an existing Android project, **Rivulet**, with app source in `app/`. `AudioTorrent` is the combined workspace/archive name.

**Every agent must start with `AI/README.md`**, followed by `AI/PROJECT.md`, `AI/ARCHITECTURE.md`, `AI/TASKS.md`, `AI/HANDOFF.md`, relevant activity logs, and the assigned plan. Check `PLAN.md` for the original roadmap. Source code takes precedence over unverified status claims in documentation.

## Roles and collaboration

- Declare the agent, exact model/version (or `unknown`), provider, coding harness, session role, task ID and timestamp with timezone.
- **Planner/researcher:** inspect and document; do not modify application code when the assignment is planning-only.
- **Implementer:** follow the approved plan version, verify it against the source, and document deviations; flag major design changes for user approval.
- **Reviewer/tester:** independently evaluate changes, document tests actually run and residual risks; don't mark untested behavior as verified.
- An agent can take different roles on different sessions. Respect active task ownership and use separate branches/worktrees for concurrent code modifications.

## Mandatory attribution

Record meaningful contributions under `AI/ACTIVITY_LOGS/` with role, specific work, changed files, plan version (if any), tests/verification, unresolved issues, handoff, and a signature identifying the agent/model. Signatures are **self-reported attribution**, not cryptographic proof. Update `AI/TASKS.md` and `AI/HANDOFF.md` appropriately.

## Safety

Do not commit or distribute signing keys, keystore passwords, API tokens, or local machine settings. Do not claim successful builds, Android runtime tests, or code modifications unless they actually occurred.
