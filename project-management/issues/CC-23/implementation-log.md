# Implementation Log: Split unit and integration/E2E tests into separate Mill modules

Issue: CC-23

This log tracks the evolution of implementation across phases.

---

## Phase 1: Build modules and test file moves (2026-04-05)

**Layer:** Build / Test Infrastructure

**What was built:**
- `build.mill` — Added `itest` submodules (ScalaTests with TestModule.Munit) inside core, direct, and effectful
- 18 test files moved from `test/src` to `itest/src` directories via `git mv`
- `effectful/itest/.../ProcessManagerIntegrationTest.scala` — New file with 3 `executeProcess` integration tests extracted from ProcessManagerTest
- `effectful/test/.../ProcessManagerTest.scala` — Reduced to 7 pure `configureProcessBuilder` unit tests, changed from CatsEffectSuite to FunSuite

**Key decisions:**
- `itest` modules depend on sibling `test` module via `moduleDeps` for access to shared test helpers
- `effectful.itest` also depends on `direct.test` (mirrors existing `effectful.test` dependency)
- direct `ProcessManagerTest` moved entirely (all tests spawn processes); effectful split into unit/integration
- `mvnDeps` explicitly listed in each itest module for build file readability

**Dependencies on other layers:**
- None (first phase)

**Testing:**
- Unit tests (`./mill __.test`): All pass, no process-spawning tests remain
- Integration tests (`./mill __.itest`): All pass in isolation; one pre-existing flaky test in direct.itest (zombie process count assertion) when run in parallel

**Code review:**
- Iterations: 1
- Review file: review-phase-01-20260405.md
- No critical issues introduced; pre-existing test quality issues noted for future improvement
- One fix applied: CatsEffectSuite → FunSuite for effectful ProcessManagerTest (now sync-only)

**Documentation updated:**
- CLAUDE.md — Added `./mill __.itest` commands
- ARCHITECTURE.md — Added test classification criteria and itest documentation
- README.md — Added integration test commands

**Files changed:**
```
M  build.mill
M  CLAUDE.md
M  ARCHITECTURE.md
M  README.md
R  18 test files from test/src → itest/src
A  effectful/itest/.../ProcessManagerIntegrationTest.scala
M  effectful/test/.../ProcessManagerTest.scala
```

---
