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

**Purpose:** Define WHAT components each layer needs, not HOW they're implemented.

This issue does not follow the typical domain/application/infrastructure/presentation layering. Instead, the work decomposes into five implementation layers aligned to the build structure and CI pipeline.

### Build Definition Layer

**Components:**
- `core.itest` module definition in `build.mill`
- `direct.itest` module definition in `build.mill`
- `effectful.itest` module definition in `build.mill`

**Responsibilities:**
- Define `itest` as `ScalaTests with TestModule.Munit` inside each top-level module
- Wire correct `moduleDeps` so itest modules can access test helpers and main source
- Wire correct `mvnDeps` (same test dependencies as corresponding `test` module)
- Ensure `itest` modules are NOT included in publish targets

**Estimated Effort:** 1-2 hours
**Complexity:** Straightforward

---

### Core itest Layer

**Components:**
- `core/itest/src/` directory with moved test files
- Files to move:
  - `SDKUserMessageRoundTripTest.scala` (property-based roundtrip, spawns processes)
  - `SDKUserMessageE2ETest.scala` (requires real Claude CLI)

**Responsibilities:**
- Move files preserving package structure
- Verify moved tests compile and pass in new location
- Verify remaining unit tests in `core/test` still compile and pass

**Estimated Effort:** 0.5-1 hours
**Complexity:** Straightforward

---

### Direct itest Layer

**Components:**
- `direct/itest/src/` directory with moved test files
- Files to move:
  - `ClaudeCodeIntegrationTest.scala`
  - `ClaudeCodeStreamingTest.scala`
  - `SessionIntegrationTest.scala`
  - `SessionErrorIntegrationTest.scala`
  - `SessionE2ETest.scala`
  - `EnvironmentTest.scala` (environment manipulation)
- Files to evaluate:
  - `ProcessManagerTest.scala` (described as "mixed" -- may need splitting)

**Responsibilities:**
- Move files preserving package structure
- Determine whether `ProcessManagerTest.scala` should be split (unit portions stay, process-spawning portions move) or moved wholesale
- Ensure test helpers in `direct/test/src/.../internal/testing/` remain in `test` (they are dependencies for both `test` and `itest`)
- Verify all tests compile and pass in their new locations

**Estimated Effort:** 1-2 hours
**Complexity:** Moderate (ProcessManagerTest split decision)

---

### Effectful itest Layer

**Components:**
- `effectful/itest/src/` directory with moved test files
- Files to move:
  - `ClaudeCodeIntegrationTest.scala`
  - `SessionIntegrationTest.scala`
  - `SessionErrorIntegrationTest.scala`
  - `SessionE2ETest.scala`
  - `EnvironmentValidationTest.scala`
  - `EnvironmentInheritanceTest.scala`
  - `EnvironmentSecurityTest.scala`
- Files to evaluate:
  - `ProcessManagerTest.scala` (same mixed concern as direct)

**Responsibilities:**
- Move files preserving package structure
- Wire `effectful.itest` moduleDeps to include `direct.test` (for `SessionMockCliScript`, `TestAssumptions`)
- Determine ProcessManagerTest split strategy (should match decision made for direct)
- Verify all tests compile and pass

**Estimated Effort:** 1-2 hours
**Complexity:** Moderate (cross-module test helper dependency)

---

### CI and Workflow Layer

**Components:**
- Updated `.github/workflows/publish.yml`
- Potential new CI workflow for integration tests (or updated existing)

**Responsibilities:**
- Add `./mill __.itest` step to CI pipeline (after unit tests, before publish)
- Decide whether itest failures should block publish
- Optionally add a separate CI workflow or job for integration tests on PRs

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

## Technical Risks & Uncertainties

### CLARIFY: ProcessManagerTest Split Strategy

Both `direct` and `effectful` have `ProcessManagerTest.scala` files described as "mixed" -- containing both unit test cases and cases that spawn real processes.

**Questions to answer:**
1. Should we split these files into unit and integration portions, or move them wholesale to itest?
2. If splitting, what is the dividing line for which test cases stay vs. move?
3. Should the split produce two files (e.g., `ProcessManagerTest.scala` in test and `ProcessManagerIntegrationTest.scala` in itest)?

**Options:**
- **Option A**: Move entire file to itest. Simpler, but loses fast feedback on pure-logic portions.
- **Option B**: Split into two files. More precise, but requires reading each test case to classify.
- **Option C**: Keep in test, tag integration tests with Munit tags and filter at runtime. Avoids file moves but doesn't give separate Mill targets.

**Impact:** Affects the test count in each module and the granularity of the fast test suite.

---

### CLARIFY: SDKUserMessageRoundTripTest Classification

The issue lists `SDKUserMessageRoundTripTest` as integration, but it is a property-based roundtrip test that may or may not spawn processes. If it only tests serialization/deserialization roundtripping (encode then decode), it could be a unit test.

**Questions to answer:**
1. Does this test spawn subprocesses or only test pure encode/decode logic?
2. If it spawns processes, is that essential to the test or incidental?

**Options:**
- **Option A**: Move to itest as stated in the issue (safe default).
- **Option B**: Keep in test if it turns out to be pure logic.

**Impact:** Minor -- one file, but sets precedent for classification rigor.

---

## Total Estimates

**Per-Layer Breakdown:**
- Build Definition Layer: 1-2 hours
- Core itest Layer: 0.5-1 hours
- Direct itest Layer: 1-2 hours
- Effectful itest Layer: 1-2 hours
- CI and Workflow Layer: 0.5-1 hours

**Total Range:** 4 - 8 hours

**Confidence:** High

**Reasoning:**
- The work is primarily file moves and build configuration, not new logic
- Mill's module system handles nested test modules natively
- The dependency structure is already well understood from the existing `effectful.test -> direct.test` pattern
- Main risk is the ProcessManagerTest split decision, which is bounded

## Testing Strategy

### Per-Layer Testing

**Build Definition Layer:**
- Verify `./mill __.compile` succeeds (all modules including itest)
- Verify `./mill __.test` runs ONLY unit tests
- Verify `./mill __.itest` runs ONLY integration/E2E tests
- Verify `./mill core.itest` / `./mill direct.itest` / `./mill effectful.itest` work individually

**Core itest Layer:**
- Run `./mill core.test` -- all remaining unit tests pass
- Run `./mill core.itest` -- moved tests pass

**Direct itest Layer:**
- Run `./mill direct.test` -- all remaining unit tests pass
- Run `./mill direct.itest` -- moved tests pass
- Verify test helpers are accessible from itest

**Effectful itest Layer:**
- Run `./mill effectful.test` -- all remaining unit tests pass
- Run `./mill effectful.itest` -- moved tests pass
- Verify `SessionMockCliScript` from `direct.test` is accessible

**CI and Workflow Layer:**
- Verify publish workflow runs both test and itest steps
- Verify itest failures are reported correctly

**Regression Coverage:**
- Total test count across test + itest should equal the original test count
- No test should be lost or duplicated in the move

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
- Clear classification of every test file as unit or integration/E2E
- Decision on ProcessManagerTest split strategy

### Layer Dependencies
- Build Definition Layer must be done first (itest modules must exist before files can be moved)
- Core, Direct, and Effectful itest layers can be done in parallel after Build Definition
- CI layer can be done last or in parallel with test moves

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

**Recommended Layer Order:**

1. **Build Definition Layer** - Must be first: creates the itest module targets that files will be moved into
2. **Core itest Layer** - Simplest module, fewest files to move, validates the pattern works
3. **Direct itest Layer** - More files, includes the ProcessManagerTest decision, test helpers stay in test
4. **Effectful itest Layer** - Mirrors direct, adds cross-module dependency verification
5. **CI and Workflow Layer** - Last: updates CI to use the new test targets

**Ordering Rationale:**
- Build definition must precede any file moves
- Core first as a proof-of-concept (only 2 files to move)
- Direct before effectful because effectful.itest depends on direct.test
- CI last because it depends on all itest modules being functional

## Documentation Requirements

- [ ] Code documentation (update PURPOSE comments in build.mill if needed)
- [ ] Architecture documentation (update ARCHITECTURE.md testing strategy section)
- [ ] Update CLAUDE.md build commands to document `__.itest`
- [ ] Update README if it mentions test commands

---

**Analysis Status:** Ready for Review

**Next Steps:**
1. Resolve CLARIFY markers (ProcessManagerTest split, SDKUserMessageRoundTripTest classification)
2. Run **wf-create-tasks** with the issue ID
3. Run **wf-implement** for layer-by-layer implementation
