# Implementation Log: Publish to Sonatype with separate ox and cats-effect artifacts

Issue: CC-12

This log tracks the evolution of implementation across phases.

---

## Phase 1: Build Infrastructure (2026-03-31)

**Layer:** Build Infrastructure

**What was built:**
- `build.mill` — Mill multi-module build with three modules (core, direct, effectful)
- `.mill-version` — Mill 1.1.2 version pin
- `.scalafmt.conf` — Scalafmt config excluding Mill `out/` directory
- `mill` — Mill launcher script for local execution

**Key decisions:**
- Scala 3.3.7 LTS (via `IWScalaVersions.scala3LTSVersion`) for downstream compatibility
- `PublishModule` directly (not `IWPublishModule`) — Sonatype config deferred to Phase 03
- `mill-iw-support` 0.1.4-SNAPSHOT published locally as prerequisite
- Mill `//|` YAML header directive must precede PURPOSE comments (Mill requirement)

**Dependencies on other layers:**
- None — this is the foundation layer

**Testing:**
- Verification via `mill resolve _`, `mill show *.moduleDeps`, `mill show *.ivyDeps`
- Compilation deferred to Phase 02 (sources not yet in Mill layout)

**Code review:**
- Iterations: 1
- Review file: review-phase-01-20260331-104159.md
- Result: Pass (0 critical, 2 warnings accepted as Mill conventions)

**Files changed:**
```
M  .gitignore
A  .mill-version
A  .scalafmt.conf
A  build.mill
A  mill
```

---
