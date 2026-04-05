# Technical Analysis: Split unit and integration/E2E tests into separate Mill modules

**Issue:** CC-23
**Created:** 2026-04-05
**Status:** Draft

## Problem Statement

Unit tests and integration/E2E tests currently live in the same `test` submodule for each of the three top-level modules (`core`, `direct`, `effectful`). This means `./mill __.test` runs everything -- both fast, pure-logic unit tests and slow tests that spawn processes, hit the real CLI, or manipulate the environment. There is no way to run only the fast tests as a pre-push gate while deferring slow tests to CI.

Splitting them enables two distinct test targets: `__.test` for fast feedback during development, and `__.itest` for the full integration/E2E suite in CI.

## Proposed Solution

### High-Level Approach

Add an `itest` submodule alongside each existing `test` submodule in the Mill build. Move integration and E2E test files from `test` to `itest`, keeping unit tests in `test`. The `itest` modules reuse the same test framework (Munit) and the same dependency structure as `test`, but are separate compilation targets that can be invoked independently.

The test helper utilities currently in `direct/test/src/.../internal/testing/` (MockCliScript, SessionMockCliScript, TestAssumptions, TestConstants, MockLogger) are used by both unit tests (e.g., `effectful/test/.../SessionTest.scala` imports `SessionMockCliScript`) and integration tests. These helpers must remain accessible from both `test` and `itest` modules, which means `itest` modules will declare `moduleDeps` on the corresponding `test` module (and `effectful.itest` will also depend on `direct.test`, mirroring the existing `effectful.test` dependency).

### Why This Approach

Mill natively supports multiple test submodules. Adding `itest` as a peer to `test` is the idiomatic Mill pattern for this split. No build plugin or custom task is needed -- just `object itest extends ScalaTests with TestModule.Munit` inside each module. The `__.test` glob naturally excludes `itest`, and `__.itest` targets only integration tests.

## Architecture Design

This issue does not follow the typical domain/application/infrastructure/presentation layering. The work decomposes into two implementation layers: build and test structure changes, then CI updates.

### Layer 1: Build Modules and Test File Moves

**Components:**
- `core.itest`, `direct.itest`, `effectful.itest` module definitions in `build.mill`
- Moved test files in `{module}/itest/src/` directories
- Split `ProcessManagerTest.scala` files (unit portions stay in `test`, process-spawning portions move to `itest`)

**Files to move to `core/itest/src/`:**
- `SDKUserMessageRoundTripTest.scala` (spawns bash processes for stdin/stdout piping)
- `SDKUserMessageE2ETest.scala` (requires real Claude CLI)

**Files to move to `direct/itest/src/`:**
- `ClaudeCodeIntegrationTest.scala`
- `ClaudeCodeStreamingTest.scala`
- `SessionIntegrationTest.scala`
- `SessionErrorIntegrationTest.scala`
- `SessionE2ETest.scala`
- `EnvironmentTest.scala` (environment manipulation)
- Process-spawning test cases extracted from `ProcessManagerTest.scala` → `ProcessManagerIntegrationTest.scala`

**Files to move to `effectful/itest/src/`:**
- `ClaudeCodeIntegrationTest.scala`
- `SessionIntegrationTest.scala`
- `SessionErrorIntegrationTest.scala`
- `SessionE2ETest.scala`
- `EnvironmentValidationTest.scala`
- `EnvironmentInheritanceTest.scala`
- `EnvironmentSecurityTest.scala`
- Process-spawning test cases extracted from `ProcessManagerTest.scala` → `ProcessManagerIntegrationTest.scala`

**Responsibilities:**
- Define `itest` as `ScalaTests with TestModule.Munit` inside each top-level module
- Wire `moduleDeps` so itest modules can access test helpers (itest depends on test)
- Wire `effectful.itest` to also depend on `direct.test` (for `SessionMockCliScript`, `TestAssumptions`)
- Split `ProcessManagerTest.scala` in both direct and effectful: pure-logic tests stay, process-spawning tests move to `ProcessManagerIntegrationTest.scala`
- Test helpers in `direct/test/src/.../internal/testing/` remain in `test`
- Ensure `itest` modules are NOT included in publish targets
- Verify all tests compile and pass in their new locations
- Update CLAUDE.md, ARCHITECTURE.md, and README with new `__.itest` command

**Estimated Effort:** 3-6 hours
**Complexity:** Moderate (ProcessManagerTest splitting requires reading each test case)

---

### Layer 2: CI Pipeline Updates

**Components:**
- Updated `.github/workflows/publish.yml`

**Responsibilities:**
- Add `./mill __.itest` step to CI pipeline (after unit tests, before publish)
- Ensure itest failures are reported correctly

**Estimated Effort:** 0.5-1 hours
**Complexity:** Straightforward

---

## Technical Decisions

### Patterns

- Mill nested test module pattern: `object itest extends ScalaTests with TestModule.Munit` inside each top-level module
- `itest` depends on `test` via `moduleDeps` to access shared test helpers without duplication

### Technology Choices

- **Build Tool**: Mill 1.1.2 (already in use)
- **Test Framework**: Munit (same as existing tests)
- **No new dependencies**: itest modules use the same mvnDeps as their sibling test modules

### Integration Points

- `effectful.itest` depends on `direct.test` for `SessionMockCliScript` and `TestAssumptions`
- `effectful.itest` depends on `effectful.test` for `MockScriptResource` (if any itest needs it)
- All `itest` modules depend on their parent module's main source (automatic via Mill)

### Test Classification Criteria

A test belongs in `itest` if it does any of the following:
1. Spawns a real subprocess (e.g., `os.proc(...).spawn()`, `ProcessBuilder`)
2. Requires the Claude Code CLI to be installed
3. Manipulates environment variables or system properties
4. Reads/writes to the real filesystem in ways beyond temp directories
5. Takes more than a few seconds to execute due to external dependencies

A test belongs in `test` if it:
1. Tests pure logic with no external dependencies
2. Uses only in-memory mocks/stubs
3. Runs in milliseconds

## Technical Decisions (Resolved)

### ProcessManagerTest Split Strategy

**Decision:** Split into two files. Pure-logic unit tests stay in `ProcessManagerTest.scala` under `test`, process-spawning tests move to `ProcessManagerIntegrationTest.scala` under `itest`. This maximizes unit test coverage for fast feedback.

### SDKUserMessageRoundTripTest Classification

**Decision:** Integration test. The test spawns bash subprocesses (`ProcessBuilder`) to simulate CLI stdin/stdout piping. The process spawning is essential to the test's purpose (validating the JSON format works through a real process boundary). Moves to `core/itest`.

## Total Estimates

**Per-Layer Breakdown:**
- Layer 1 (Build + file moves): 3-6 hours
- Layer 2 (CI updates): 0.5-1 hours

**Total Range:** 3.5 - 7 hours

**Confidence:** High

**Reasoning:**
- The work is primarily file moves and build configuration, not new logic
- Mill's module system handles nested test modules natively
- The dependency structure is already well understood from the existing `effectful.test -> direct.test` pattern
- Main risk is the ProcessManagerTest split decision, which is bounded

## Testing Strategy

**Layer 1 verification:**
- `./mill __.compile` succeeds (all modules including itest)
- `./mill __.test` runs ONLY unit tests (no process spawning)
- `./mill __.itest` runs ONLY integration/E2E tests
- Each module's test and itest can be run individually
- Test helpers accessible from itest via `moduleDeps`
- `SessionMockCliScript` from `direct.test` accessible from `effectful.itest`

**Layer 2 verification:**
- CI workflow runs both `__.test` and `__.itest` steps
- itest failures are reported correctly

**Regression Coverage:**
- Total test count across test + itest equals the original test count
- No test lost or duplicated

## Deployment Considerations

### Database Changes
None.

### Configuration Changes
- `build.mill` updated with itest module definitions
- `.github/workflows/publish.yml` updated with itest step

### Rollout Strategy
This is a build-only change with no runtime impact. Merge to main and all downstream consumers are unaffected (itest modules are not published).

### Rollback Plan
Revert the commit. Tests move back to their original locations.

## Dependencies

### Prerequisites
- None (classification decisions are resolved)

### Layer Dependencies
- Layer 1 must complete before Layer 2 (CI needs functional itest modules)

### External Blockers
None.

## Risks & Mitigations

### Risk 1: Test helper accessibility from itest modules
**Likelihood:** Low
**Impact:** Medium
**Mitigation:** Wire `moduleDeps` from itest to test, following the existing pattern of `effectful.test -> direct.test`. Mill supports this natively.

### Risk 2: Circular or incorrect module dependency
**Likelihood:** Low
**Impact:** High (build fails)
**Mitigation:** The dependency graph is strictly acyclic: itest depends on test, never the reverse. Verify with `./mill resolve __.itest` and `./mill __.compile`.

### Risk 3: Tests accidentally excluded or duplicated
**Likelihood:** Low
**Impact:** Medium
**Mitigation:** Compare test counts before and after. Run `./mill __.test` and `./mill __.itest` and verify the total matches the original `./mill __.test` count.

---

## Implementation Sequence

1. **Layer 1: Build + file moves** — Add itest modules to build.mill, move/split all test files, update docs, verify compilation and test counts
2. **Layer 2: CI updates** — Add `__.itest` step to GitHub Actions workflow

## Documentation Requirements

- [ ] Code documentation (update PURPOSE comments in build.mill if needed)
- [ ] Architecture documentation (update ARCHITECTURE.md testing strategy section)
- [ ] Update CLAUDE.md build commands to document `__.itest`
- [ ] Update README if it mentions test commands

---

**Analysis Status:** Ready for Review

**Next Steps:**
1. Run **wf-create-tasks** with the issue ID
2. Run **wf-implement** for layer-by-layer implementation
