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

## Phase 2: Source Reorganization (2026-03-31)

**Layer:** Source Reorganization

**What was built:**
- Moved all source files from flat Scala CLI layout into Mill multi-module directories
- `core/src/`, `direct/src/`, `effectful/src/` — production sources per module
- `{module}/test/src/` — test sources per module
- `effectful/test/resources/bin/` — mock CLI scripts for integration tests
- `direct/test/resources/logback.xml`, `effectful/test/resources/logback.xml` — test logging config
- `MockScriptResource.scala` — utility to extract mock scripts from classpath resources in Mill's forked test sandbox

**Key decisions:**
- Deleted `ClaudeCode.scala` (unused backward-compat facade) and `model.scala` (duplicate types)
- Deleted `project.scala` and `publish-conf.scala` (Scala CLI configs superseded by `build.mill`)
- Created `MockScriptResource` to resolve hardcoded `./test/bin/` paths that break in Mill's forked test runner
- Fixed `FileSystemOpsTest` to use `/etc/hosts` instead of project-relative paths

**Phase 1 gaps fixed:**
- `os-lib` missing from `effectful` module (used by `FileSystemOps.scala`)
- `munit-cats-effect` missing from `core.test` (used by `CLIArgumentBuilderTest`)
- `scalacheck`/`munit-scalacheck` missing from `direct.test`
- `effectful.test` module dependency on `direct.test` (for `TestAssumptions`)

**Dependencies on other layers:**
- Phase 1 (Build Infrastructure): `build.mill` module structure

**Testing:**
- All tests pass: `mill core.test`, `mill direct.test`, `mill effectful.test`
- Pre-existing flaky test: `direct/ProcessManagerTest` zombie process count assertions (unrelated)

**Code review:**
- Iterations: 1
- Findings: 3 critical (MockScriptResource safety, test guard), 5 warnings
- All critical issues fixed in follow-up commit

**Files changed:**
```
M  .gitignore
M  build.mill
R  works/iterative/claude/core/* → core/src/works/iterative/claude/core/*
R  works/iterative/claude/direct/* → direct/src/works/iterative/claude/direct/*
R  works/iterative/claude/effectful/* → effectful/src/works/iterative/claude/effectful/*
R  test/works/iterative/claude/* → {module}/test/src/works/iterative/claude/*
R  test/bin/* → effectful/test/resources/bin/*
R  src/main/resources/logback.xml → effectful/test/resources/logback.xml
A  direct/test/resources/logback.xml
A  effectful/test/resources/logback.xml
A  effectful/test/src/.../testing/MockScriptResource.scala
D  works/iterative/claude/ClaudeCode.scala
D  works/iterative/claude/model.scala
D  project.scala
D  publish-conf.scala
```

---

## Phase 3: Publishing Configuration (2026-04-02)

**Layer:** Publishing Configuration

**What was built:**
- `build.mill` — Added `artifactName` override in `SharedModule` to prefix `claude-code-query-` to each module name; bumped `publishVersion` from `0.1.0-SNAPSHOT` to `0.1.0`
- `.github/workflows/publish.yml` — GitHub Actions workflow for automated publishing to Sonatype Central on `v*` tag push

**Key decisions:**
- `artifactName` override in `SharedModule` (shared prefix) rather than per-module overrides
- Hardcoded version `0.1.0` rather than dynamic tag-based versioning (simplicity for first release)
- GitHub Actions pinned to commit SHAs for supply chain security
- Workflow uses minimal permissions (`contents: read`)

**Dependencies on other layers:**
- Phase 1 (Build Infrastructure): `SharedModule` with `PublishModule` mixin
- Phase 2 (Source Reorganization): All sources in Mill layout, tests passing

**Testing:**
- All 397 tests pass after changes
- `publishLocal` verified: artifacts at `works.iterative:claude-code-query-{core,direct,effectful}_3:0.1.0`
- POM dependency isolation confirmed: core has only circe, direct has no cats-effect, effectful has no ox

**Code review:**
- Iterations: 1
- Review file: review-phase-03-20260402.md
- Result: Pass (0 critical, 5 warnings — security hardening and diagnostic context addressed)

**Bug fix:**
- `MockScriptResource.scala` — Fixed pre-existing compilation issue with `Files.copy` return type and restored diagnostic error context

**Files changed:**
```
A  .github/workflows/publish.yml
M  build.mill
M  effectful/test/src/works/iterative/claude/effectful/internal/testing/MockScriptResource.scala
```

---
