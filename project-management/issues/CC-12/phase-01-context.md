# Phase 01: Build Infrastructure

## Goals

Create a Mill multi-module build that replaces the current Scala CLI build. The build defines three modules (`core`, `direct`, `effectful`) with correct dependency declarations, compiles successfully (even if sources aren't yet in Mill layout), and is ready for source reorganization in Phase 02.

## Scope

### In Scope
- `build.mill` with three sub-modules extending `IWScalaModule` and `PublishModule`
- `.mill-version` file (Mill 1.1.2)
- Per-module dependency declarations (only deps each module actually needs)
- Shared `pomSettings` and `publishVersion` definitions
- Module dependency graph: `direct -> core`, `effectful -> core`
- Scala version pinned to 3.3.7 LTS (override `IWScalaVersions` default)
- Test module configuration per sub-module (munit)

### Out of Scope
- Moving source files (Phase 02)
- Sonatype/Maven Central publishing configuration (Phase 03)
- GPG signing setup (Phase 03)
- Verification of dependency trees (Phase 04)

## Dependencies

### Prior Phases
None — this is the first phase.

### External Dependencies
- `mill-iw-support` plugin (0.1.4-SNAPSHOT) must be accessible. It provides `IWScalaModule` (compiler options, scalafmt, SemanticDB) and `IWScalaVersions` (version constants).
- Mill 1.1.2 must be installed.

## Approach

### Build Structure

Create `build.mill` in the project root following the pattern from `scalatags-web-awesome`:

```
build.mill          — root build definition
.mill-version       — "1.1.2"
```

The build defines:

1. **Shared trait** with common settings:
   - `scalaVersion` = `scala3LTSVersion` (3.3.7 from `IWScalaVersions`)
   - `pomSettings` with org `works.iterative`, Apache-2.0 license, GitHub VCS
   - `publishVersion` (initially "0.1.0-SNAPSHOT")

2. **`core` module** — shared types, CLI arg builder, JSON parsing:
   - Dependencies: circe-core, circe-parser
   - No effect-system deps
   - Test deps: munit, scalacheck, munit-scalacheck

3. **`direct` module** — Ox-based synchronous API:
   - Module dep: `core`
   - Dependencies: os-lib, ox, slf4j-api
   - Test deps: munit, logback-classic (test-scoped)

4. **`effectful` module** — cats-effect/fs2 async API:
   - Module dep: `core`
   - Dependencies: cats-effect, fs2-core, fs2-io, log4cats-slf4j
   - Test deps: munit, munit-cats-effect, log4cats-testing, logback-classic (test-scoped)

### Key Decisions (from analysis.md)

- **Scala 3.3.7 LTS**: Override `IWScalaVersions.scala3Version` (3.8.1) with `scala3LTSVersion` (3.3.7). Both Ox 1.0.4 and cats-effect 3.7.0 are built against 3.3.7. LTS maximizes downstream compatibility.
- **PublishModule directly**: Do NOT use `IWPublishModule` (defaults to e-BS Nexus with signing disabled). Each module extends Mill's built-in `PublishModule`. Sonatype configuration comes in Phase 03.
- **No IWMillVersions**: Dependency versions are managed directly in `build.mill` since this project has its own specific version needs.

### Source Layout

At this phase, sources remain in their current Scala CLI layout. The build file defines the module structure but sources won't compile until Phase 02 moves them into Mill's conventional directories (`core/src/`, `direct/src/`, `effectful/src/`). This is intentional — the build infrastructure must exist before sources can be reorganized.

The build should use Mill's default source layout:
- `{module}/src/` for main sources
- `{module}/test/src/` for test sources
- `{module}/resources/` for resources

## Files to Create/Modify

### New Files
- `build.mill` — root build definition with three sub-modules
- `.mill-version` — Mill version file ("1.1.2")

### Files to Keep (not modified this phase)
- `project.scala` — kept until Phase 02 confirms Mill build works
- `publish-conf.scala` — kept until Phase 03

## Testing Strategy

### Verification Steps
1. `mill resolve _` — verify Mill recognizes the build file and modules
2. `mill core.ivyDeps` — verify core dependencies are correct
3. `mill direct.ivyDeps` — verify direct dependencies include os-lib, ox
4. `mill effectful.ivyDeps` — verify effectful dependencies include cats-effect, fs2
5. `mill show core.moduleDeps` — verify no upstream deps
6. `mill show direct.moduleDeps` — verify depends on core
7. `mill show effectful.moduleDeps` — verify depends on core

Note: `mill core.compile` etc. will fail until Phase 02 moves sources into place. That's expected.

## Acceptance Criteria

- [ ] `build.mill` exists with `core`, `direct`, and `effectful` modules
- [ ] `.mill-version` contains "1.1.2"
- [ ] Each module extends `IWScalaModule` and `PublishModule`
- [ ] `scalaVersion` is 3.3.7 across all modules
- [ ] Module dependency graph is correct (direct→core, effectful→core)
- [ ] Per-module dependencies match the analysis (no cross-contamination)
- [ ] `mill resolve _` succeeds
- [ ] `project.scala` and `publish-conf.scala` are preserved (not deleted)
