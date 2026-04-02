# Phase 2: Source Reorganization

## Goals

Move all source files, test files, and resources from the flat Scala CLI layout into Mill's conventional multi-module directory structure (`{module}/src/`, `{module}/test/src/`, `{module}/test/resources/`). Delete obsolete files that are no longer needed. After this phase, `mill _.compile` and `mill _.test.compile` must succeed.

## Scope

### In Scope
- Moving source files into `core/src/`, `direct/src/`, `effectful/src/`
- Moving test files into `core/test/src/`, `direct/test/src/`, `effectful/test/src/`
- Moving test resources (mock scripts, logback.xml) into appropriate test resource directories
- Deleting obsolete files: top-level `ClaudeCode.scala`, `model.scala`, `project.scala`, `publish-conf.scala`
- Removing empty directories left behind after moves

### Out of Scope
- Package renames (existing package structure already matches module boundaries)
- Functional code changes (no logic changes, only file moves)
- Sonatype/Maven Central publishing configuration (Phase 03)
- Dependency verification (Phase 04)

## Dependencies

### Prior Phases
- **Phase 1 (Build Infrastructure)**: `build.mill` must exist with `core`, `direct`, `effectful` modules and correct dependency declarations. Completed.

### External Dependencies
- Mill 1.1.2 must be installed and functional.
- `mill-iw-support` plugin (0.1.4-SNAPSHOT) must be accessible.

## Approach

### Step 1: Create target directory structure

Create the Mill module directories:

```
core/src/works/iterative/claude/core/
core/test/src/works/iterative/claude/core/
direct/src/works/iterative/claude/direct/
direct/test/src/works/iterative/claude/direct/
effectful/src/works/iterative/claude/effectful/
effectful/test/src/works/iterative/claude/effectful/
effectful/test/resources/bin/
```

### Step 2: Move core sources

Move files from `works/iterative/claude/core/` to `core/src/works/iterative/claude/core/`, preserving subdirectory structure.

### Step 3: Move direct sources

Move files from `works/iterative/claude/direct/` to `direct/src/works/iterative/claude/direct/`, preserving subdirectory structure.

### Step 4: Move effectful sources

Move files from `works/iterative/claude/effectful/` to `effectful/src/works/iterative/claude/effectful/`, preserving subdirectory structure.

### Step 5: Move core tests

Move files from `test/works/iterative/claude/core/` to `core/test/src/works/iterative/claude/core/`, preserving subdirectory structure.

### Step 6: Move direct tests

Move files from `test/works/iterative/claude/direct/` to `direct/test/src/works/iterative/claude/direct/`, preserving subdirectory structure.

### Step 7: Move effectful tests

Move files from `test/works/iterative/claude/effectful/` to `effectful/test/src/works/iterative/claude/effectful/`, preserving subdirectory structure.

### Step 8: Move top-level effectful tests

These top-level test files belong to the effectful module:

- `test/works/iterative/claude/ClaudeCodeIntegrationTest.scala` -> `effectful/test/src/works/iterative/claude/ClaudeCodeIntegrationTest.scala`
- `test/works/iterative/claude/ClaudeCodeLoggingTest.scala` -> `effectful/test/src/works/iterative/claude/ClaudeCodeLoggingTest.scala`
- `test/works/iterative/claude/ErrorContextLoggingTest.scala` -> `effectful/test/src/works/iterative/claude/ErrorContextLoggingTest.scala`
- `test/works/iterative/claude/internal/LoggingSetupTest.scala` -> `effectful/test/src/works/iterative/claude/internal/LoggingSetupTest.scala`

### Step 9: Move test resources

- `test/bin/*` (mock CLI scripts) -> `effectful/test/resources/bin/`
- `src/main/resources/logback.xml` -> `effectful/test/resources/logback.xml`

Also add `logback.xml` to `direct/test/resources/logback.xml` since `direct` tests also depend on logback-classic.

### Step 10: Delete obsolete files

- `works/iterative/claude/ClaudeCode.scala` — effectful facade, nothing imports it
- `works/iterative/claude/model.scala` — duplicates types already in `core/model/`
- `project.scala` — Scala CLI config, superseded by `build.mill`
- `publish-conf.scala` — Scala CLI publish config, superseded by `build.mill`

### Step 11: Clean up empty directories

Remove the now-empty directory trees:
- `works/` (entire tree, all sources moved out)
- `test/` (entire tree, all tests and resources moved out)
- `src/` (entire tree, resources moved out)

## Files to Create/Modify/Delete

### Files to Move (source -> target)

**Core sources:**
| Source | Target |
|--------|--------|
| `works/iterative/claude/core/cli/CLIArgumentBuilder.scala` | `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala` |
| `works/iterative/claude/core/CLIError.scala` | `core/src/works/iterative/claude/core/CLIError.scala` |
| `works/iterative/claude/core/model/ContentBlock.scala` | `core/src/works/iterative/claude/core/model/ContentBlock.scala` |
| `works/iterative/claude/core/model/Message.scala` | `core/src/works/iterative/claude/core/model/Message.scala` |
| `works/iterative/claude/core/model/PermissionMode.scala` | `core/src/works/iterative/claude/core/model/PermissionMode.scala` |
| `works/iterative/claude/core/model/QueryOptions.scala` | `core/src/works/iterative/claude/core/model/QueryOptions.scala` |
| `works/iterative/claude/core/parsing/JsonParser.scala` | `core/src/works/iterative/claude/core/parsing/JsonParser.scala` |

**Direct sources:**
| Source | Target |
|--------|--------|
| `works/iterative/claude/direct/ClaudeCode.scala` | `direct/src/works/iterative/claude/direct/ClaudeCode.scala` |
| `works/iterative/claude/direct/Logger.scala` | `direct/src/works/iterative/claude/direct/Logger.scala` |
| `works/iterative/claude/direct/package.scala` | `direct/src/works/iterative/claude/direct/package.scala` |
| `works/iterative/claude/direct/internal/cli/CLIDiscovery.scala` | `direct/src/works/iterative/claude/direct/internal/cli/CLIDiscovery.scala` |
| `works/iterative/claude/direct/internal/cli/FileSystemOps.scala` | `direct/src/works/iterative/claude/direct/internal/cli/FileSystemOps.scala` |
| `works/iterative/claude/direct/internal/cli/ProcessManager.scala` | `direct/src/works/iterative/claude/direct/internal/cli/ProcessManager.scala` |
| `works/iterative/claude/direct/internal/parsing/JsonParser.scala` | `direct/src/works/iterative/claude/direct/internal/parsing/JsonParser.scala` |

**Effectful sources:**
| Source | Target |
|--------|--------|
| `works/iterative/claude/effectful/ClaudeCode.scala` | `effectful/src/works/iterative/claude/effectful/ClaudeCode.scala` |
| `works/iterative/claude/effectful/internal/cli/CLIDiscovery.scala` | `effectful/src/works/iterative/claude/effectful/internal/cli/CLIDiscovery.scala` |
| `works/iterative/claude/effectful/internal/cli/FileSystemOps.scala` | `effectful/src/works/iterative/claude/effectful/internal/cli/FileSystemOps.scala` |
| `works/iterative/claude/effectful/internal/cli/ProcessManager.scala` | `effectful/src/works/iterative/claude/effectful/internal/cli/ProcessManager.scala` |
| `works/iterative/claude/effectful/internal/parsing/JsonParser.scala` | `effectful/src/works/iterative/claude/effectful/internal/parsing/JsonParser.scala` |

**Core tests:**
| Source | Target |
|--------|--------|
| `test/works/iterative/claude/core/cli/CLIArgumentBuilderTest.scala` | `core/test/src/works/iterative/claude/core/cli/CLIArgumentBuilderTest.scala` |
| `test/works/iterative/claude/core/parsing/JsonParserTest.scala` | `core/test/src/works/iterative/claude/core/parsing/JsonParserTest.scala` |

**Direct tests:**
| Source | Target |
|--------|--------|
| `test/works/iterative/claude/direct/ClaudeCodeIntegrationTest.scala` | `direct/test/src/works/iterative/claude/direct/ClaudeCodeIntegrationTest.scala` |
| `test/works/iterative/claude/direct/ClaudeCodeStreamingTest.scala` | `direct/test/src/works/iterative/claude/direct/ClaudeCodeStreamingTest.scala` |
| `test/works/iterative/claude/direct/ClaudeCodeTest.scala` | `direct/test/src/works/iterative/claude/direct/ClaudeCodeTest.scala` |
| `test/works/iterative/claude/direct/internal/cli/CLIDiscoveryTest.scala` | `direct/test/src/works/iterative/claude/direct/internal/cli/CLIDiscoveryTest.scala` |
| `test/works/iterative/claude/direct/internal/cli/EnvironmentTest.scala` | `direct/test/src/works/iterative/claude/direct/internal/cli/EnvironmentTest.scala` |
| `test/works/iterative/claude/direct/internal/cli/FileSystemOpsTest.scala` | `direct/test/src/works/iterative/claude/direct/internal/cli/FileSystemOpsTest.scala` |
| `test/works/iterative/claude/direct/internal/cli/ProcessManagerTest.scala` | `direct/test/src/works/iterative/claude/direct/internal/cli/ProcessManagerTest.scala` |
| `test/works/iterative/claude/direct/internal/parsing/JsonParserTest.scala` | `direct/test/src/works/iterative/claude/direct/internal/parsing/JsonParserTest.scala` |
| `test/works/iterative/claude/direct/internal/testing/MockCliScript.scala` | `direct/test/src/works/iterative/claude/direct/internal/testing/MockCliScript.scala` |
| `test/works/iterative/claude/direct/internal/testing/TestAssumptions.scala` | `direct/test/src/works/iterative/claude/direct/internal/testing/TestAssumptions.scala` |
| `test/works/iterative/claude/direct/internal/testing/TestConstants.scala` | `direct/test/src/works/iterative/claude/direct/internal/testing/TestConstants.scala` |

**Effectful tests:**
| Source | Target |
|--------|--------|
| `test/works/iterative/claude/effectful/internal/cli/CLIDiscoveryTest.scala` | `effectful/test/src/works/iterative/claude/effectful/internal/cli/CLIDiscoveryTest.scala` |
| `test/works/iterative/claude/effectful/internal/cli/EnvironmentInheritanceTest.scala` | `effectful/test/src/works/iterative/claude/effectful/internal/cli/EnvironmentInheritanceTest.scala` |
| `test/works/iterative/claude/effectful/internal/cli/EnvironmentSecurityTest.scala` | `effectful/test/src/works/iterative/claude/effectful/internal/cli/EnvironmentSecurityTest.scala` |
| `test/works/iterative/claude/effectful/internal/cli/EnvironmentValidationTest.scala` | `effectful/test/src/works/iterative/claude/effectful/internal/cli/EnvironmentValidationTest.scala` |
| `test/works/iterative/claude/effectful/internal/cli/FileSystemOpsTest.scala` | `effectful/test/src/works/iterative/claude/effectful/internal/cli/FileSystemOpsTest.scala` |
| `test/works/iterative/claude/effectful/internal/cli/ProcessManagerTest.scala` | `effectful/test/src/works/iterative/claude/effectful/internal/cli/ProcessManagerTest.scala` |

**Top-level effectful tests (need module assignment):**
| Source | Target |
|--------|--------|
| `test/works/iterative/claude/ClaudeCodeIntegrationTest.scala` | `effectful/test/src/works/iterative/claude/ClaudeCodeIntegrationTest.scala` |
| `test/works/iterative/claude/ClaudeCodeLoggingTest.scala` | `effectful/test/src/works/iterative/claude/ClaudeCodeLoggingTest.scala` |
| `test/works/iterative/claude/ErrorContextLoggingTest.scala` | `effectful/test/src/works/iterative/claude/ErrorContextLoggingTest.scala` |
| `test/works/iterative/claude/internal/LoggingSetupTest.scala` | `effectful/test/src/works/iterative/claude/internal/LoggingSetupTest.scala` |

**Test resources:**
| Source | Target |
|--------|--------|
| `test/bin/mock-claude` | `effectful/test/resources/bin/mock-claude` |
| `test/bin/mock-claude-all-messages` | `effectful/test/resources/bin/mock-claude-all-messages` |
| `test/bin/mock-claude-bad-json` | `effectful/test/resources/bin/mock-claude-bad-json` |
| `test/bin/mock-claude-env-test` | `effectful/test/resources/bin/mock-claude-env-test` |
| `test/bin/mock-claude-fail` | `effectful/test/resources/bin/mock-claude-fail` |
| `test/bin/mock-claude-hang` | `effectful/test/resources/bin/mock-claude-hang` |
| `test/bin/mock-claude-inherit-env` | `effectful/test/resources/bin/mock-claude-inherit-env` |
| `src/main/resources/logback.xml` | `effectful/test/resources/logback.xml` |

Additionally, copy `logback.xml` to `direct/test/resources/logback.xml` (direct tests also use logback-classic).

### Files to Delete

- `works/iterative/claude/ClaudeCode.scala` — unused effectful facade
- `works/iterative/claude/model.scala` — duplicate types from `core/model/`
- `project.scala` — Scala CLI config, superseded by `build.mill`
- `publish-conf.scala` — Scala CLI publish config, superseded by `build.mill`

### Directories to Remove (after all moves)

- `works/` — entire tree (will be empty)
- `test/` — entire tree (will be empty)
- `src/` — entire tree (will be empty)

## Testing Strategy

### Verification Steps

1. **Compilation check**: `mill _.compile` must succeed for all three modules
2. **Test compilation check**: `mill _.test.compile` must succeed for all three modules
3. **Unit tests**: `mill _.test` must pass (run all tests across all modules)
4. **No leftover files**: Verify `works/`, `test/`, and `src/` directories no longer exist
5. **No obsolete files**: Verify `project.scala` and `publish-conf.scala` no longer exist
6. **Resource accessibility**: Verify mock scripts are accessible from effectful tests (check `effectful/test/resources/bin/` contains all 7 mock scripts with execute permission)
7. **Git cleanliness**: All moves should be done via `git mv` to preserve history; deletions via `git rm`

### Smoke Tests

- `mill core.compile` — compiles core types and parsing
- `mill direct.compile` — compiles direct module (depends on core)
- `mill effectful.compile` — compiles effectful module (depends on core)
- `mill core.test` — runs core unit tests
- `mill direct.test` — runs direct unit tests
- `mill effectful.test` — runs effectful unit tests

## Acceptance Criteria

- [ ] All source files are under `{module}/src/works/iterative/claude/{module}/...`
- [ ] All test files are under `{module}/test/src/works/iterative/claude/...`
- [ ] Top-level effectful tests are under `effectful/test/src/works/iterative/claude/...`
- [ ] Mock scripts are under `effectful/test/resources/bin/` with execute permissions
- [ ] `logback.xml` is under `effectful/test/resources/` and `direct/test/resources/`
- [ ] `works/iterative/claude/ClaudeCode.scala` is deleted
- [ ] `works/iterative/claude/model.scala` is deleted
- [ ] `project.scala` is deleted
- [ ] `publish-conf.scala` is deleted
- [ ] Empty old directories (`works/`, `test/`, `src/`) are removed
- [ ] `mill _.compile` succeeds
- [ ] `mill _.test.compile` succeeds
- [ ] `mill _.test` passes (all tests green)
- [ ] No package name changes in any source file
