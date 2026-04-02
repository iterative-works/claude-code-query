---
generated_from: 387d05f887c85433bcd3b211cddb45bfe2b98753
generated_at: 2026-04-02T13:55:21Z
branch: CC-12
issue_id: CC-12
phase: "1-4 (complete)"
files_analyzed:
  - build.mill
  - ARCHITECTURE.md
  - README.md
  - .github/workflows/publish.yml
  - core/src/works/iterative/claude/core/CLIError.scala
  - core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala
  - core/src/works/iterative/claude/core/model/ContentBlock.scala
  - core/src/works/iterative/claude/core/model/Message.scala
  - core/src/works/iterative/claude/core/model/PermissionMode.scala
  - core/src/works/iterative/claude/core/model/QueryOptions.scala
  - core/src/works/iterative/claude/core/parsing/JsonParser.scala
  - direct/src/works/iterative/claude/direct/ClaudeCode.scala
  - direct/src/works/iterative/claude/direct/Logger.scala
  - effectful/src/works/iterative/claude/effectful/ClaudeCode.scala
  - effectful/test/src/works/iterative/claude/effectful/internal/testing/MockScriptResource.scala
---

# Review Packet: CC-12 — Publish to Sonatype with separate Ox and cats-effect artifacts

## Goals

This branch migrates the Claude Code Scala SDK from Scala CLI to Mill as the build tool, splitting the single flat artifact into three independently published modules (`core`, `direct`, `effectful`). The goal is to eliminate forced transitive dependencies: users who only need the direct-style Ox API no longer pull in cats-effect, and users of the effectful API no longer pull in Ox.

Key objectives:

- Replace `project.scala` / `publish-conf.scala` (Scala CLI) with `build.mill` defining three modules
- Organize all sources into Mill's conventional layout (`{module}/src/`, `{module}/test/src/`)
- Publish three separate artifacts to Maven Central via Sonatype Central
- Verify dependency isolation: no cross-contamination between modules
- Update ARCHITECTURE.md and README.md to document the new multi-module structure

## Scenarios

- [ ] `mill __.compile` succeeds for all three modules
- [ ] `mill __.test` passes all 397 tests
- [ ] `core` dependency tree contains only circe and its transitive deps (no ox, cats-effect, fs2)
- [ ] `direct` dependency tree contains core + os-lib + ox + slf4j, with no cats-effect, fs2, or log4cats
- [ ] `effectful` dependency tree contains core + os-lib + cats-effect + fs2 + log4cats, with no ox
- [ ] `mill __.publishLocal` succeeds and produces artifacts with coordinates `works.iterative:claude-code-query-{core,direct,effectful}_3:0.1.0`
- [ ] No Scala CLI config files (`project.scala`, `publish-conf.scala`) remain in the repository
- [ ] GitHub Actions publish workflow triggers on `v*` tags and maps secrets to the env var names Mill expects
- [ ] ARCHITECTURE.md documents the three-module layout, dependency graph, artifact coordinates, and build commands
- [ ] README.md shows Maven Central coordinates for `claude-code-query-direct` and `claude-code-query-effectful` (not a Scala CLI dep)

## Entry Points

| File | Class / Object | Why Start Here |
|------|----------------|----------------|
| `build.mill` | `SharedModule`, `core`, `direct`, `effectful` | Root of the entire change — defines module structure, dependency graph, artifact naming, and publish settings |
| `core/src/works/iterative/claude/core/model/QueryOptions.scala` | `QueryOptions` | Central configuration type shared by both API modules |
| `direct/src/works/iterative/claude/direct/ClaudeCode.scala` | `ClaudeCode` | Public entry point for the Ox-based synchronous API |
| `effectful/src/works/iterative/claude/effectful/ClaudeCode.scala` | `ClaudeCode` | Public entry point for the cats-effect/fs2 asynchronous API |
| `effectful/test/src/works/iterative/claude/effectful/internal/testing/MockScriptResource.scala` | `MockScriptResource` | Key new test utility that extracts shell mock scripts from classpath resources in Mill's forked test sandbox (the main non-trivial new code) |
| `.github/workflows/publish.yml` | — | Automates publishing to Sonatype Central on version tags |

## Diagrams

### Module Dependency Graph

```
direct  ──┐
           ├──▶  core
effectful ─┘
```

Both `direct` and `effectful` depend on `core`. The two API modules have no dependency on each other, ensuring complete isolation.

### Published Artifact Coordinates

| Module | Artifact |
|--------|----------|
| `core` | `works.iterative:claude-code-query-core_3:0.1.0` |
| `direct` | `works.iterative:claude-code-query-direct_3:0.1.0` |
| `effectful` | `works.iterative:claude-code-query-effectful_3:0.1.0` |

### Production Dependencies per Module

| Module | Runtime Dependencies |
|--------|----------------------|
| `core` | circe-core, circe-parser |
| `direct` | core, os-lib, ox, slf4j-api |
| `effectful` | core, os-lib, cats-effect, fs2-core, fs2-io, log4cats-slf4j |

### Data Flow

```
QueryOptions → CLIArgumentBuilder → ProcessManager → Claude Code CLI process
                                                          ├── stdout → JsonParser → Message stream
                                                          └── stderr → Error context
```

### High-Level Architecture

```
┌──────────────────────────────────────────┐
│            Public API Layer              │
├──────────────────┬───────────────────────┤
│   Direct API     │    Effectful API      │
│   (Ox-based)     │  (cats-effect/fs2)    │
└──────────────────┴───────────────────────┘
         │                      │
         └──────────┬───────────┘
                    │
        ┌──────────▼─────────────┐
        │     Core Module        │
        ├────────────────────────┤
        │  • Model Types         │
        │  • CLI Argument Builder│
        │  • JSON Parser         │
        │  • Error Types         │
        └────────────────────────┘
                    │
                    ▼
        Claude Code CLI subprocess
```

### GitHub Actions Publish Flow

```
git push tag v*
     │
     ▼
actions/checkout
     │
     ▼
Set up JDK 21 (Temurin)
     │
     ▼
./mill __.test  ──── fail → stop
     │
     ▼
./mill mill.javalib.SonatypeCentralPublishModule/publishAll
     │
     ▼
Sonatype Central → Maven Central
```

## Test Summary

### Core Module Tests (Unit)

| Test Class | Type | Focus |
|------------|------|-------|
| `JsonParserTest` | Unit | JSON line parsing for all message types; error/edge case handling |
| `CLIArgumentBuilderTest` | Unit | Mapping `QueryOptions` fields to CLI flags; multi-param combinations |

### Direct Module Tests (Unit + Integration)

| Test Class | Type | Focus |
|------------|------|-------|
| `ClaudeCodeTest` | Unit | Sync and async API surface via mock CLI script |
| `ClaudeCodeStreamingTest` | Unit | Streaming `Flow` behavior against mock CLI |
| `ClaudeCodeIntegrationTest` | Integration | End-to-end workflow, working directory, optional real CLI |
| `CLIDiscoveryTest` | Unit | Locating `claude` executable on PATH |
| `EnvironmentTest` | Unit (property) | Environment variable inheritance and overrides |
| `FileSystemOpsTest` | Unit | File system helpers |
| `ProcessManagerTest` | Unit (property) | Process builder configuration |
| `JsonParserTest` (direct) | Unit | JSON parsing in direct module context |

### Effectful Module Tests (Unit + Integration)

| Test Class | Type | Focus |
|------------|------|-------|
| `ClaudeCodeIntegrationTest` | Integration | End-to-end with mock Claude scripts via `MockScriptResource` |
| `ClaudeCodeLoggingTest` | Unit | log4cats logging of messages and events |
| `ErrorContextLoggingTest` | Unit | Error context included in log output |
| `LoggingSetupTest` | Unit | SLF4J logging setup and configuration |
| `CLIDiscoveryTest` | Unit | Effectful CLI discovery |
| `EnvironmentInheritanceTest` | Unit | Env var inheritance rules in cats-effect context |
| `EnvironmentSecurityTest` | Unit | Security filtering of sensitive env vars |
| `EnvironmentValidationTest` | Unit | Env var name/value validation |
| `FileSystemOpsTest` | Unit | File system operations in effectful context |
| `ProcessManagerTest` | Unit | cats-effect process management |

**Total: 397 tests — all passing**

Test infrastructure notes:
- `MockScriptResource` (effectful module) extracts shell mock scripts from JAR classpath resources into a temp directory for each test run, working around Mill's forked test sandbox not exposing source-tree paths.
- `MockCliScript` (direct module) provides a similar utility using an inline approach with property-based tests.
- `TestAssumptions` provides conditional skip logic for tests that require the real Claude CLI.
- Seven distinct mock scripts cover: normal output, all message types, bad JSON, env inspection, process failure, hang/timeout, and env inheritance.

## Files Changed

The full set of changed files relative to `main`:

### New Files (Build Infrastructure)

- `build.mill` — Three-module Mill build replacing Scala CLI configs
- `.mill-version` — Pins Mill to 1.1.2
- `.scalafmt.conf` — Scalafmt config (excludes `out/`)
- `mill` — Mill launcher wrapper script
- `.github/workflows/publish.yml` — CI publish workflow

### Reorganized Sources (Phase 2)

All production sources moved from the flat Scala CLI layout under `works/iterative/claude/` into Mill module directories:

```
core/src/works/iterative/claude/core/
  CLIError.scala
  cli/CLIArgumentBuilder.scala
  model/{ContentBlock,Message,PermissionMode,QueryOptions}.scala
  parsing/JsonParser.scala

direct/src/works/iterative/claude/direct/
  ClaudeCode.scala
  Logger.scala
  internal/cli/{CLIDiscovery,FileSystemOps,ProcessManager}.scala
  internal/parsing/JsonParser.scala
  package.scala

effectful/src/works/iterative/claude/effectful/
  ClaudeCode.scala
  internal/cli/{CLIDiscovery,FileSystemOps,ProcessManager}.scala
  internal/parsing/JsonParser.scala
```

Test sources reorganized into `{module}/test/src/` following the same pattern.

### New Test Infrastructure

- `effectful/test/resources/bin/mock-claude*` — Seven mock CLI scripts
- `effectful/test/resources/logback.xml` — Test logging config
- `direct/test/resources/logback.xml` — Test logging config
- `effectful/test/src/works/iterative/claude/effectful/internal/testing/MockScriptResource.scala` — Key new utility for classpath-to-disk script extraction

### Deleted Files

- `works/iterative/claude/ClaudeCode.scala` — Unused backward-compat effectful facade
- `works/iterative/claude/model.scala` — Duplicate of `core.model` types
- `project.scala` — Scala CLI build config (superseded)
- `publish-conf.scala` — Scala CLI publish config (superseded)

### Documentation

- `ARCHITECTURE.md` — Added "Build Modules" section with module layout, dependency graph, artifact coordinates, and build commands
- `README.md` — Replaced Scala CLI dep with Maven Central coordinates; added three-module architecture section; updated Requirements to reference Mill
- `CLAUDE.md` — Updated build commands to Mill

<details>
<summary>Complete file list (83 files)</summary>

```
.github/workflows/publish.yml
.gitignore
.mill-version
.scalafmt.conf
ARCHITECTURE.md
CLAUDE.md
README.md
build.mill
core/src/works/iterative/claude/core/CLIError.scala
core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala
core/src/works/iterative/claude/core/model/ContentBlock.scala
core/src/works/iterative/claude/core/model/Message.scala
core/src/works/iterative/claude/core/model/PermissionMode.scala
core/src/works/iterative/claude/core/model/QueryOptions.scala
core/src/works/iterative/claude/core/parsing/JsonParser.scala
core/test/src/works/iterative/claude/core/cli/CLIArgumentBuilderTest.scala
core/test/src/works/iterative/claude/core/parsing/JsonParserTest.scala
direct/src/works/iterative/claude/direct/ClaudeCode.scala
direct/src/works/iterative/claude/direct/Logger.scala
direct/src/works/iterative/claude/direct/internal/cli/CLIDiscovery.scala
direct/src/works/iterative/claude/direct/internal/cli/FileSystemOps.scala
direct/src/works/iterative/claude/direct/internal/cli/ProcessManager.scala
direct/src/works/iterative/claude/direct/internal/parsing/JsonParser.scala
direct/src/works/iterative/claude/direct/package.scala
direct/test/resources/logback.xml
direct/test/src/works/iterative/claude/direct/ClaudeCodeIntegrationTest.scala
direct/test/src/works/iterative/claude/direct/ClaudeCodeStreamingTest.scala
direct/test/src/works/iterative/claude/direct/ClaudeCodeTest.scala
direct/test/src/works/iterative/claude/direct/internal/cli/CLIDiscoveryTest.scala
direct/test/src/works/iterative/claude/direct/internal/cli/EnvironmentTest.scala
direct/test/src/works/iterative/claude/direct/internal/cli/FileSystemOpsTest.scala
direct/test/src/works/iterative/claude/direct/internal/cli/ProcessManagerTest.scala
direct/test/src/works/iterative/claude/direct/internal/parsing/JsonParserTest.scala
direct/test/src/works/iterative/claude/direct/internal/testing/MockCliScript.scala
direct/test/src/works/iterative/claude/direct/internal/testing/TestAssumptions.scala
direct/test/src/works/iterative/claude/direct/internal/testing/TestConstants.scala
effectful/src/works/iterative/claude/effectful/ClaudeCode.scala
effectful/src/works/iterative/claude/effectful/internal/cli/CLIDiscovery.scala
effectful/src/works/iterative/claude/effectful/internal/cli/FileSystemOps.scala
effectful/src/works/iterative/claude/effectful/internal/cli/ProcessManager.scala
effectful/src/works/iterative/claude/effectful/internal/parsing/JsonParser.scala
effectful/test/resources/bin/mock-claude
effectful/test/resources/bin/mock-claude-all-messages
effectful/test/resources/bin/mock-claude-bad-json
effectful/test/resources/bin/mock-claude-env-test
effectful/test/resources/bin/mock-claude-fail
effectful/test/resources/bin/mock-claude-hang
effectful/test/resources/bin/mock-claude-inherit-env
effectful/test/resources/logback.xml
effectful/test/src/works/iterative/claude/ClaudeCodeIntegrationTest.scala
effectful/test/src/works/iterative/claude/ClaudeCodeLoggingTest.scala
effectful/test/src/works/iterative/claude/ErrorContextLoggingTest.scala
effectful/test/src/works/iterative/claude/effectful/internal/cli/CLIDiscoveryTest.scala
effectful/test/src/works/iterative/claude/effectful/internal/cli/EnvironmentInheritanceTest.scala
effectful/test/src/works/iterative/claude/effectful/internal/cli/EnvironmentSecurityTest.scala
effectful/test/src/works/iterative/claude/effectful/internal/cli/EnvironmentValidationTest.scala
effectful/test/src/works/iterative/claude/effectful/internal/cli/FileSystemOpsTest.scala
effectful/test/src/works/iterative/claude/effectful/internal/cli/ProcessManagerTest.scala
effectful/test/src/works/iterative/claude/effectful/internal/testing/MockScriptResource.scala
effectful/test/src/works/iterative/claude/internal/LoggingSetupTest.scala
mill
project-management/issues/CC-12/analysis.md
project-management/issues/CC-12/implementation-log.md
project-management/issues/CC-12/phase-01-context.md
project-management/issues/CC-12/phase-01-tasks.md
project-management/issues/CC-12/phase-02-context.md
project-management/issues/CC-12/phase-02-tasks.md
project-management/issues/CC-12/phase-03-context.md
project-management/issues/CC-12/phase-03-tasks.md
project-management/issues/CC-12/phase-04-context.md
project-management/issues/CC-12/phase-04-tasks.md
project-management/issues/CC-12/review-phase-01-20260331-104159.md
project-management/issues/CC-12/review-phase-03-20260402.md
project-management/issues/CC-12/review-phase-04-20260402.md
project-management/issues/CC-12/review-state.json
project-management/issues/CC-12/tasks.md
project.scala  (deleted)
publish-conf.scala  (deleted)
works/iterative/claude/ClaudeCode.scala  (deleted)
works/iterative/claude/model.scala  (deleted)
```

</details>
