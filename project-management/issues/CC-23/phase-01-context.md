# Phase 1: Build modules and test file moves

## Goals

Add `itest` submodules alongside each existing `test` submodule in the Mill build. Move integration and E2E test files from `test` to `itest`. After this phase, `./mill __.test` runs only fast unit tests and `./mill __.itest` runs only integration/E2E tests.

## Scope

### In Scope

- Add `object itest extends ScalaTests with TestModule.Munit` inside `core`, `direct`, and `effectful` modules in `build.mill`
- Wire `moduleDeps` so `itest` depends on `test` (for shared test helpers)
- Wire `effectful.itest` to also depend on `direct.test` (mirroring existing `effectful.test` dependency)
- Move integration/E2E test files from `test/src` to `itest/src` directories
- Split `effectful` `ProcessManagerTest.scala` (unit tests stay, process-spawning tests move)
- Move entire `direct` `ProcessManagerTest.scala` to itest (all tests spawn processes)
- Update CLAUDE.md, ARCHITECTURE.md, and README.md with `__.itest` command documentation
- Verify compilation succeeds and all tests pass in their new locations

### Out of Scope

- CI pipeline changes (Phase 2)
- Changes to test logic or test helpers
- New test framework dependencies

## Dependencies

- No prior phases required
- Test helpers in `direct/test/src/.../internal/testing/` remain in `test` and are accessed from `itest` via `moduleDeps`

## Approach

### 1. Build Configuration (`build.mill`)

Add `itest` module to each top-level module. Pattern:

```scala
object itest extends ScalaTests with TestModule.Munit {
  def moduleDeps = super.moduleDeps ++ Seq(outer.test)
  def mvnDeps = // same as sibling test module
}
```

For `effectful.itest`:
```scala
def moduleDeps = super.moduleDeps ++ Seq(effectful.test, direct.test)
```

Ensure `itest` modules are NOT included in publish targets (Mill handles this by default since `ScalaTests` are not `PublishModule`).

### 2. File Moves

**Files to move to `core/itest/src/`:**
- `SDKUserMessageRoundTripTest.scala` (spawns bash processes via ProcessBuilder)
- `SDKUserMessageE2ETest.scala` (requires real Claude CLI)

All other core tests are pure unit tests and stay in `test`.

**Files to move to `direct/itest/src/`:**
- `ClaudeCodeIntegrationTest.scala`
- `ClaudeCodeStreamingTest.scala`
- `SessionIntegrationTest.scala`
- `SessionErrorIntegrationTest.scala`
- `SessionE2ETest.scala`
- `EnvironmentTest.scala` (environment manipulation)
- `ProcessManagerTest.scala` → move entirely (all 21 tests spawn processes via MockCliScript)

**Files to move to `effectful/itest/src/`:**
- `ClaudeCodeIntegrationTest.scala` (from `effectful/test/src/works/iterative/claude/`)
- `ClaudeCodeLoggingTest.scala` (from `effectful/test/src/works/iterative/claude/` — spawns mock subprocess)
- `SessionIntegrationTest.scala`
- `SessionErrorIntegrationTest.scala`
- `SessionE2ETest.scala`
- `EnvironmentValidationTest.scala`
- `EnvironmentInheritanceTest.scala`
- `EnvironmentSecurityTest.scala`

### 3. ProcessManagerTest Split (effectful only)

The effectful `ProcessManagerTest.scala` needs splitting:

**Stay in `test` (unit tests — pure `configureProcessBuilder` logic):**
- `configureProcessBuilder creates ProcessBuilder successfully`
- `configureProcessBuilder sets working directory when provided`
- `configureProcessBuilder uses no working directory when not specified`
- `configureProcessBuilder sets environment variables when specified`
- `configureProcessBuilder inherits environment when inheritEnvironment is true`
- `configureProcessBuilder sets inheritEnv to false when specified`
- `configureProcessBuilder defaults to inheritEnv true when not specified`

**Move to `itest` as `ProcessManagerIntegrationTest.scala`:**
- `executeProcess can execute simple process`
- `executeProcess logs process start with command`
- `executeProcess logs process completion with exit codes`

The direct `ProcessManagerTest.scala` does NOT need splitting — all tests use `supervised` + `MockCliScript` process spawning, so the entire file moves to `itest`.

### 4. Test Resources

- `direct/test/resources/` (logback.xml, bin/ mock scripts) stays in `test` — accessible to `itest` via `moduleDeps`
- `effectful/test/resources/logback.xml` stays in `test`
- If itest modules need their own logback config, copy to `itest/resources/`

### 5. Documentation Updates

- `CLAUDE.md`: Add `./mill __.itest` to build commands
- `ARCHITECTURE.md`: Update testing strategy section
- `README.md`: Add itest command if test commands are mentioned

## Files to Modify

- `build.mill` — add itest module definitions
- ~20 test files to move (git mv)
- 1 test file to split (effectful ProcessManagerTest)
- `CLAUDE.md`, `ARCHITECTURE.md`, `README.md` — documentation

## Testing Strategy

### Verification Steps

1. `./mill __.compile` — all modules including itest compile
2. `./mill __.test` — runs ONLY unit tests (fast, no process spawning)
3. `./mill __.itest` — runs ONLY integration/E2E tests
4. Individual module tests: `./mill core.test`, `./mill direct.itest`, etc.
5. Test count: total across `test + itest` equals original test count

### Test Classification Criteria

A test belongs in `itest` if it:
1. Spawns a real subprocess (`os.proc().spawn()`, `ProcessBuilder`)
2. Requires the Claude Code CLI to be installed
3. Manipulates environment variables or system properties
4. Takes more than a few seconds due to external dependencies

## Acceptance Criteria

- [ ] `itest` modules defined in `build.mill` for core, direct, effectful
- [ ] All integration/E2E test files moved to `itest/src` directories
- [ ] effectful ProcessManagerTest split into unit (test) and integration (itest) parts
- [ ] direct ProcessManagerTest moved entirely to itest
- [ ] `./mill __.compile` succeeds
- [ ] `./mill __.test` passes (unit tests only)
- [ ] `./mill __.itest` passes (integration tests only)
- [ ] Documentation updated (CLAUDE.md, ARCHITECTURE.md, README.md)
- [ ] No test lost or duplicated
