# PLAN-001 — Audit current Android app and produce an evidence-based implementation roadmap

**Version:** 0.1 (draft)  
**Author:** ChatGPT (GPT-6), documentation integration  
**Status:** DRAFT / unapproved  
**Assigned role:** PLANNER/REVIEWER  
**Implementation authorization:** None  
**Related tasks:** TASK-001, TASK-002, TASK-003

## Objective

Determine what already works in the user-supplied Android/Kotlin Rivulet app. Reconcile the original `PLAN.md` with actual implementation and build/test evidence before assigning new implementation work.

## Work steps

1. Read `PLAN.md`, `AGENTS.md`, and `AI/README.md`.
2. Inventory all `app/src/main` modules, dependencies, Android manifest declarations, and test code.
3. Build with the included Gradle wrapper using a compatible SDK and JDK 17; document exact environment, command and results.
4. Review torrent add/metadata/file priority/HTTP range/ExoPlayer integration against the code.
5. Test with legally shareable audio, including a multi-file torrent and seeking where feasible.
6. Map each roadmap feature to `IMPLEMENTED / PARTIAL / NOT_FOUND / UNVERIFIED` with code references and test evidence.
7. Identify bugs, security risks, Android lifecycle constraints, and missing implementation details.
8. Create distinct, testable plans for approved follow-up features; assign owners only after user approval.

## Expected deliverables

- A source-and-test-based capability matrix with file references.
- Clearly reproducible build and test report (or documented blockers).
- Prioritized task breakdown, risks and acceptance criteria.
- Updated `AI/ARCHITECTURE.md`, `AI/TASKS.md`, `AI/PROGRESS.md`, handoff and session activity log.

## Acceptance criteria

- Existing user code remains unchanged unless explicitly authorized.
- Every implemented feature claim has supporting source evidence; every working claim has runtime/test evidence.
- Another implementer can take an approved, versioned follow-up plan without needing earlier chat context.
