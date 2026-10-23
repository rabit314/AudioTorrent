# Architectural decisions and established facts

Do not conflate an **observed source fact** with a new approved design decision. Record deliberate design decisions separately with date, role, evidence, alternatives and trade-offs.

## OBS-001 — Existing Android stack (observed, not newly approved)

- The provided project uses Android, Kotlin and Jetpack Compose (`app/build.gradle.kts`).
- It contains libtorrent4j native libraries and torrent integration, a local HTTP streaming bridge, and Media3 ExoPlayer code.
- Root `PLAN.md` documents the proposed v1 direction; not every planned component is established as implemented.

## DEC-001 — Preserve original roadmap and application code during merge

**Owner:** ChatGPT (GPT-6) as package integrator  
**Reason:** Prevent an old empty-project scaffold from overwriting actual user work.
**Effect:** Root `PLAN.md` and Android sources remain intact; AI docs point to observed source and identify unverified features.

## DEC-002 — Keep private signing credentials out of collaboration ZIP

**Owner:** ChatGPT (GPT-6) as package integrator  
**Reason:** AI and cross-model archives may be widely copied, and keystores/passwords should not be shared.
**Effect:** Excluded `keystore/`, `keystore.properties` and `local.properties` from combined ZIP. Signed release builds require separately restored private credentials.

Future technical decisions should be proposed, reviewed and approved in normal project workflow.
