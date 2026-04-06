---
generated_from: 7b75aaeb8edfa223c2924e06100a7394182df69d
generated_at: 2026-04-06T07:46:51Z
branch: CC-23
issue_id: CC-23
phase: "1-2 (complete)"
files_analyzed:
  - build.mill
  - .github/workflows/publish.yml
  - core/itest/src/works/iterative/claude/core/model/SDKUserMessageE2ETest.scala
  - core/itest/src/works/iterative/claude/core/model/SDKUserMessageRoundTripTest.scala
  - direct/itest/src/works/iterative/claude/direct/ClaudeCodeIntegrationTest.scala
  - direct/itest/src/works/iterative/claude/direct/ClaudeCodeStreamingTest.scala
  - direct/itest/src/works/iterative/claude/direct/SessionE2ETest.scala
  - direct/itest/src/works/iterative/claude/direct/SessionErrorIntegrationTest.scala
  - direct/itest/src/works/iterative/claude/direct/SessionIntegrationTest.scala
  - direct/itest/src/works/iterative/claude/direct/internal/cli/EnvironmentTest.scala
  - direct/itest/src/works/iterative/claude/direct/internal/cli/ProcessManagerTest.scala
  - effectful/itest/src/works/iterative/claude/ClaudeCodeIntegrationTest.scala
  - effectful/itest/src/works/iterative/claude/ClaudeCodeLoggingTest.scala
  - effectful/itest/src/works/iterative/claude/effectful/SessionE2ETest.scala
  - effectful/itest/src/works/iterative/claude/effectful/SessionErrorIntegrationTest.scala
  - effectful/itest/src/works/iterative/claude/effectful/SessionIntegrationTest.scala
  - effectful/itest/src/works/iterative/claude/effectful/internal/cli/EnvironmentInheritanceTest.scala
  - effectful/itest/src/works/iterative/claude/effectful/internal/cli/EnvironmentSecurityTest.scala
  - effectful/itest/src/works/iterative/claude/effectful/internal/cli/EnvironmentValidationTest.scala
  - effectful/itest/src/works/iterative/claude/effectful/internal/cli/ProcessManagerIntegrationTest.scala
  - effectful/test/src/works/iterative/claude/effectful/internal/cli/ProcessManagerTest.scala
  - ARCHITECTURE.md
  - CLAUDE.md
  - README.md
---

# Review Packet: CC-23 - Split unit and integration/E2E tests into separate Mill modules

## Goals

This branch separates fast unit tests from slow integration/E2E tests by introducing `itest` submodules in the Mill build, enabling developers to run `./mill __.test` for quick feedback during development while deferring `./mill __.itest` (process-spawning, CLI-dependent, and environment-manipulating tests) to CI.

Key objectives:

- Add `itest` Mill submodules alongside existing `test` modules for `core`, `direct`, and `effectful`
- Move 18 integration/E2E test files from `test/src` to `itest/src` via `git mv`
- Split `effectful/ProcessManagerTest.scala` into unit tests (stays in `test`) and integration tests (moves to `itest`)
- Move `direct/ProcessManagerTest.scala` entirely to `itest` (all tests spawn processes)
- Wire `moduleDeps` so `itest` modules access shared test helpers in `test` without duplication
- Add `./mill __.itest` step to the GitHub Actions publish workflow between unit tests and publish
- Update `CLAUDE.md`, `ARCHITECTURE.md`, and `README.md` with the new commands

## Scenarios

- [ ] `./mill __.test` runs only unit tests with no subprocess-spawning or CLI dependency
- [ ] `./mill __.itest` runs all integration and E2E tests
- [ ] `./mill __.compile` succeeds for all modules including `itest`
- [ ] Individual module targets work: `./mill core.itest`, `./mill direct.itest`, `./mill effectful.itest`
- [ ] Test helpers in `direct/test/src/.../internal/testing/` are accessible from all `itest` modules
- [ ] `effectful.itest` can use `SessionMockCliScript` and `TestAssumptions` from `direct.test`
- [ ] `itest` modules are not included in publish artifacts
- [ ] CI publish workflow runs unit tests then integration tests then publishes
- [ ] itest failures block the publish step
- [ ] Total test count across `test` + `itest` equals the original count (no tests lost or duplicated)

## Entry Points

| File | Method/Class | Why Start Here |
|------|--------------|----------------|
| `build.mill` | `object itest` (inside `core`, `direct`, `effectful`) | Defines the new itest modules and their `moduleDeps`; the structural core of this change |
| `.github/workflows/publish.yml` | `Run integration tests` step | Sole Phase 2 change; verifies step ordering and itest failure gating |
| `effectful/test/src/.../cli/ProcessManagerTest.scala` | `ProcessManagerTest` | The only file that was modified rather than moved; unit tests were retained, process-spawning tests extracted |
| `effectful/itest/src/.../cli/ProcessManagerIntegrationTest.scala` | `ProcessManagerIntegrationTest` | New file; the extracted process-spawning tests that were split from `ProcessManagerTest` |

## Diagrams

### Module Dependency Graph

```
core
  ├── test
  └── itest  ──depends-on──> core.test

direct  ──depends-on──> core
  ├── test
  └── itest  ──depends-on──> direct.test

effectful  ──depends-on──> core
  ├── test   ──depends-on──> direct.test
  └── itest  ──depends-on──> effectful.test, direct.test
```

The `itest → test` dependency gives integration tests access to shared test helpers (mock CLI scripts, `TestAssumptions`, `TestConstants`, `MockLogger`) without duplicating them.

### CI Step Ordering

```
checkout → setup-java → coursier-cache → __.test → __.itest → publishAll
```

The sequential ordering of GitHub Actions steps means `__.itest` failure prevents `publishAll` from running.

### Test Classification

```
test/ (unit - fast, no external dependencies)
  core/test       - model parsing, JSON round-trips (pure logic)
  direct/test     - environment builder, pure SessionState logic
  effectful/test  - configureProcessBuilder logic (sync, no real processes)

itest/ (integration/E2E - slow, external dependencies allowed)
  core/itest      - SDKUserMessage round-trip via real bash subprocess
                    SDKUserMessage E2E via real Claude CLI
  direct/itest    - ClaudeCode integration, streaming, session lifecycle,
                    session error handling, E2E, environment manipulation,
                    ProcessManager (all 21 tests spawn mock CLI processes)
  effectful/itest - ClaudeCode integration, logging, session lifecycle,
                    session error handling, E2E, environment inheritance/
                    validation/security, ProcessManager integration
```

## Test Summary

### Unit Tests (`./mill __.test`)

| Module | Test File | Tests | Type |
|--------|-----------|-------|------|
| `effectful.test` | `ProcessManagerTest` | 7 (`configureProcessBuilder` variants) | Unit |
| `direct.test` | Existing unit tests | (unchanged) | Unit |
| `core.test` | Existing unit tests | (unchanged) | Unit |

Noteworthy: `effectful/ProcessManagerTest.scala` was changed from `CatsEffectSuite` to `FunSuite` as part of the split, since the retained tests are now synchronous-only and no longer need cats-effect test support.

### Integration Tests (`./mill __.itest`)

**core.itest**

| Test File | Selected Tests |
|-----------|----------------|
| `SDKUserMessageRoundTripTest` | Round-trip: encode SDKUserMessage, mock CLI echoes response, parse it |
| `SDKUserMessageE2ETest` | E2E via real Claude CLI (skipped when CLI unavailable) |

**direct.itest**

| Test File | Selected Tests |
|-----------|----------------|
| `ClaudeCodeIntegrationTest` | Complete workflow with mock CLI; working directory handling; real CLI discovery |
| `ClaudeCodeStreamingTest` | Streaming output handling |
| `SessionIntegrationTest` | Full single-turn lifecycle; correct JSON on stdin; session ID extraction; KeepAlive/StreamEvent emission; two-turn lifecycle; stdin capture across turns |
| `SessionErrorIntegrationTest` | Process crash mid-turn; crash between turns; SessionProcessDied propagation |
| `SessionE2ETest` | Real CLI single turn; two-turn context preservation; valid session ID after first turn |
| `EnvironmentTest` | Environment variable name/value special characters; isolation; leakage prevention |
| `ProcessManagerTest` (moved entirely) | 21 tests: process execution, stderr capture, working directory, env vars, failure handling, JSON error handling, lifecycle logging |

**effectful.itest**

| Test File | Selected Tests |
|-----------|----------------|
| `ClaudeCodeIntegrationTest` | Simple prompt returns assistant message; queryResult; querySync; error cases (malformed JSON, timeout, invalid working dir); option construction; all message types |
| `ClaudeCodeLoggingTest` | Logs initiation, configuration validation, and completion |
| `SessionIntegrationTest` | Single-turn lifecycle; resource cleanup (normal and exception); correct JSON on stdin; session ID from init message; two-turn lifecycle; stdin capture; turns with different message counts; factory method |
| `SessionErrorIntegrationTest` | Process crash mid-turn; crash between turns; error propagation; resource cleanup after crash |
| `SessionE2ETest` | Real CLI single turn; two-turn context; session ID validity |
| `EnvironmentInheritanceTest` | inheritEnv flag behavior; custom env vars with/without inheritance; PATH verification |
| `EnvironmentSecurityTest` | Error messages do not leak env var values in ProcessExecutionError/ProcessTimeoutError |
| `EnvironmentValidationTest` | Empty and invalid env var names; validation behavior |
| `ProcessManagerIntegrationTest` | executeProcess: simple process; logs process start; logs completion with exit codes |

## Files Changed

### Summary

| Category | Count |
|----------|-------|
| Build configuration | 1 |
| CI workflow | 1 |
| Test files moved to `itest/` | 18 |
| Test file split (unit part retained, integration part extracted) | 2 |
| Documentation | 3 |
| Project management | 9 |

### Build

- `build.mill` — Added `object itest extends ScalaTests with TestModule.Munit` inside `core`, `direct`, and `effectful`; each with explicit `moduleDeps` wiring to sibling `test` module; `effectful.itest` also depends on `direct.test`

### CI

- `.github/workflows/publish.yml` — Added `Run integration tests: ./mill __.itest` step between `Run tests` and `Publish to Sonatype Central`

### Test Files (Moved to `itest/`)

<details>
<summary>core/itest</summary>

- `core/itest/src/works/iterative/claude/core/model/SDKUserMessageE2ETest.scala`
- `core/itest/src/works/iterative/claude/core/model/SDKUserMessageRoundTripTest.scala`

</details>

<details>
<summary>direct/itest</summary>

- `direct/itest/src/works/iterative/claude/direct/ClaudeCodeIntegrationTest.scala`
- `direct/itest/src/works/iterative/claude/direct/ClaudeCodeStreamingTest.scala`
- `direct/itest/src/works/iterative/claude/direct/SessionE2ETest.scala`
- `direct/itest/src/works/iterative/claude/direct/SessionErrorIntegrationTest.scala`
- `direct/itest/src/works/iterative/claude/direct/SessionIntegrationTest.scala`
- `direct/itest/src/works/iterative/claude/direct/internal/cli/EnvironmentTest.scala`
- `direct/itest/src/works/iterative/claude/direct/internal/cli/ProcessManagerTest.scala` (moved entirely)

</details>

<details>
<summary>effectful/itest</summary>

- `effectful/itest/src/works/iterative/claude/ClaudeCodeIntegrationTest.scala`
- `effectful/itest/src/works/iterative/claude/ClaudeCodeLoggingTest.scala`
- `effectful/itest/src/works/iterative/claude/effectful/SessionE2ETest.scala`
- `effectful/itest/src/works/iterative/claude/effectful/SessionErrorIntegrationTest.scala`
- `effectful/itest/src/works/iterative/claude/effectful/SessionIntegrationTest.scala`
- `effectful/itest/src/works/iterative/claude/effectful/internal/cli/EnvironmentInheritanceTest.scala`
- `effectful/itest/src/works/iterative/claude/effectful/internal/cli/EnvironmentSecurityTest.scala`
- `effectful/itest/src/works/iterative/claude/effectful/internal/cli/EnvironmentValidationTest.scala`
- `effectful/itest/src/works/iterative/claude/effectful/internal/cli/ProcessManagerIntegrationTest.scala` (new — extracted from ProcessManagerTest)

</details>

### Test Files (Modified in `test/`)

- `effectful/test/src/works/iterative/claude/effectful/internal/cli/ProcessManagerTest.scala` — Reduced from ~10 tests to 7 pure `configureProcessBuilder` unit tests; changed from `CatsEffectSuite` to `FunSuite`

### Documentation

- `ARCHITECTURE.md` — Updated testing strategy section with itest classification criteria
- `CLAUDE.md` — Added `./mill __.itest` and per-module itest commands
- `README.md` — Added integration test commands

### Project Management

- `project-management/issues/CC-23/analysis.md`
- `project-management/issues/CC-23/implementation-log.md`
- `project-management/issues/CC-23/phase-01-context.md`
- `project-management/issues/CC-23/phase-01-tasks.md`
- `project-management/issues/CC-23/phase-02-context.md`
- `project-management/issues/CC-23/phase-02-tasks.md`
- `project-management/issues/CC-23/review-phase-01-20260405.md`
- `project-management/issues/CC-23/review-phase-02-20260405.md`
- `project-management/issues/CC-23/review-state.json`
- `project-management/issues/CC-23/tasks.md`
