# Phase 2 Tasks: Source Reorganization

**Issue:** CC-12
**Phase:** 2 - Source Reorganization
**Depends on:** Phase 1 (Build Infrastructure) -- completed

## Setup

- [x] [setup] Create core module directories: `core/src/works/iterative/claude/core/{cli,model,parsing}/`
- [x] [setup] Create core test directories: `core/test/src/works/iterative/claude/core/{cli,parsing}/`
- [x] [setup] Create direct module directories: `direct/src/works/iterative/claude/direct/internal/{cli,parsing}/`
- [x] [setup] Create direct test directories: `direct/test/src/works/iterative/claude/direct/internal/{cli,parsing,testing}/`
- [x] [setup] Create effectful module directories: `effectful/src/works/iterative/claude/effectful/internal/{cli,parsing}/`
- [x] [setup] Create effectful test directories: `effectful/test/src/works/iterative/claude/effectful/internal/cli/`
- [x] [setup] Create effectful top-level test directory: `effectful/test/src/works/iterative/claude/internal/`
- [x] [setup] Create test resource directories: `effectful/test/resources/bin/`, `direct/test/resources/`

## Source Moves

- [x] [move-core] `git mv works/iterative/claude/core/cli/CLIArgumentBuilder.scala core/src/works/iterative/claude/core/cli/`
- [x] [move-core] `git mv works/iterative/claude/core/CLIError.scala core/src/works/iterative/claude/core/`
- [x] [move-core] `git mv works/iterative/claude/core/model/ContentBlock.scala core/src/works/iterative/claude/core/model/`
- [x] [move-core] `git mv works/iterative/claude/core/model/Message.scala core/src/works/iterative/claude/core/model/`
- [x] [move-core] `git mv works/iterative/claude/core/model/PermissionMode.scala core/src/works/iterative/claude/core/model/`
- [x] [move-core] `git mv works/iterative/claude/core/model/QueryOptions.scala core/src/works/iterative/claude/core/model/`
- [x] [move-core] `git mv works/iterative/claude/core/parsing/JsonParser.scala core/src/works/iterative/claude/core/parsing/`
- [x] [move-direct] `git mv works/iterative/claude/direct/ClaudeCode.scala direct/src/works/iterative/claude/direct/`
- [x] [move-direct] `git mv works/iterative/claude/direct/Logger.scala direct/src/works/iterative/claude/direct/`
- [x] [move-direct] `git mv works/iterative/claude/direct/package.scala direct/src/works/iterative/claude/direct/`
- [x] [move-direct] `git mv works/iterative/claude/direct/internal/cli/CLIDiscovery.scala direct/src/works/iterative/claude/direct/internal/cli/`
- [x] [move-direct] `git mv works/iterative/claude/direct/internal/cli/FileSystemOps.scala direct/src/works/iterative/claude/direct/internal/cli/`
- [x] [move-direct] `git mv works/iterative/claude/direct/internal/cli/ProcessManager.scala direct/src/works/iterative/claude/direct/internal/cli/`
- [x] [move-direct] `git mv works/iterative/claude/direct/internal/parsing/JsonParser.scala direct/src/works/iterative/claude/direct/internal/parsing/`
- [x] [move-effectful] `git mv works/iterative/claude/effectful/ClaudeCode.scala effectful/src/works/iterative/claude/effectful/`
- [x] [move-effectful] `git mv works/iterative/claude/effectful/internal/cli/CLIDiscovery.scala effectful/src/works/iterative/claude/effectful/internal/cli/`
- [x] [move-effectful] `git mv works/iterative/claude/effectful/internal/cli/FileSystemOps.scala effectful/src/works/iterative/claude/effectful/internal/cli/`
- [x] [move-effectful] `git mv works/iterative/claude/effectful/internal/cli/ProcessManager.scala effectful/src/works/iterative/claude/effectful/internal/cli/`
- [x] [move-effectful] `git mv works/iterative/claude/effectful/internal/parsing/JsonParser.scala effectful/src/works/iterative/claude/effectful/internal/parsing/`

## Test Moves

- [x] [move-test-core] `git mv test/works/iterative/claude/core/cli/CLIArgumentBuilderTest.scala core/test/src/works/iterative/claude/core/cli/`
- [x] [move-test-core] `git mv test/works/iterative/claude/core/parsing/JsonParserTest.scala core/test/src/works/iterative/claude/core/parsing/`
- [x] [move-test-direct] `git mv test/works/iterative/claude/direct/ClaudeCodeIntegrationTest.scala direct/test/src/works/iterative/claude/direct/`
- [x] [move-test-direct] `git mv test/works/iterative/claude/direct/ClaudeCodeStreamingTest.scala direct/test/src/works/iterative/claude/direct/`
- [x] [move-test-direct] `git mv test/works/iterative/claude/direct/ClaudeCodeTest.scala direct/test/src/works/iterative/claude/direct/`
- [x] [move-test-direct] `git mv test/works/iterative/claude/direct/internal/cli/CLIDiscoveryTest.scala direct/test/src/works/iterative/claude/direct/internal/cli/`
- [x] [move-test-direct] `git mv test/works/iterative/claude/direct/internal/cli/EnvironmentTest.scala direct/test/src/works/iterative/claude/direct/internal/cli/`
- [x] [move-test-direct] `git mv test/works/iterative/claude/direct/internal/cli/FileSystemOpsTest.scala direct/test/src/works/iterative/claude/direct/internal/cli/`
- [x] [move-test-direct] `git mv test/works/iterative/claude/direct/internal/cli/ProcessManagerTest.scala direct/test/src/works/iterative/claude/direct/internal/cli/`
- [x] [move-test-direct] `git mv test/works/iterative/claude/direct/internal/parsing/JsonParserTest.scala direct/test/src/works/iterative/claude/direct/internal/parsing/`
- [x] [move-test-direct] `git mv test/works/iterative/claude/direct/internal/testing/MockCliScript.scala direct/test/src/works/iterative/claude/direct/internal/testing/`
- [x] [move-test-direct] `git mv test/works/iterative/claude/direct/internal/testing/TestAssumptions.scala direct/test/src/works/iterative/claude/direct/internal/testing/`
- [x] [move-test-direct] `git mv test/works/iterative/claude/direct/internal/testing/TestConstants.scala direct/test/src/works/iterative/claude/direct/internal/testing/`
- [x] [move-test-effectful] `git mv test/works/iterative/claude/effectful/internal/cli/CLIDiscoveryTest.scala effectful/test/src/works/iterative/claude/effectful/internal/cli/`
- [x] [move-test-effectful] `git mv test/works/iterative/claude/effectful/internal/cli/EnvironmentInheritanceTest.scala effectful/test/src/works/iterative/claude/effectful/internal/cli/`
- [x] [move-test-effectful] `git mv test/works/iterative/claude/effectful/internal/cli/EnvironmentSecurityTest.scala effectful/test/src/works/iterative/claude/effectful/internal/cli/`
- [x] [move-test-effectful] `git mv test/works/iterative/claude/effectful/internal/cli/EnvironmentValidationTest.scala effectful/test/src/works/iterative/claude/effectful/internal/cli/`
- [x] [move-test-effectful] `git mv test/works/iterative/claude/effectful/internal/cli/FileSystemOpsTest.scala effectful/test/src/works/iterative/claude/effectful/internal/cli/`
- [x] [move-test-effectful] `git mv test/works/iterative/claude/effectful/internal/cli/ProcessManagerTest.scala effectful/test/src/works/iterative/claude/effectful/internal/cli/`
- [x] [move-test-effectful-toplevel] `git mv test/works/iterative/claude/ClaudeCodeIntegrationTest.scala effectful/test/src/works/iterative/claude/`
- [x] [move-test-effectful-toplevel] `git mv test/works/iterative/claude/ClaudeCodeLoggingTest.scala effectful/test/src/works/iterative/claude/`
- [x] [move-test-effectful-toplevel] `git mv test/works/iterative/claude/ErrorContextLoggingTest.scala effectful/test/src/works/iterative/claude/`
- [x] [move-test-effectful-toplevel] `git mv test/works/iterative/claude/internal/LoggingSetupTest.scala effectful/test/src/works/iterative/claude/internal/`

## Resource Moves

- [x] [move-resource] `git mv test/bin/mock-claude effectful/test/resources/bin/`
- [x] [move-resource] `git mv test/bin/mock-claude-all-messages effectful/test/resources/bin/`
- [x] [move-resource] `git mv test/bin/mock-claude-bad-json effectful/test/resources/bin/`
- [x] [move-resource] `git mv test/bin/mock-claude-env-test effectful/test/resources/bin/`
- [x] [move-resource] `git mv test/bin/mock-claude-fail effectful/test/resources/bin/`
- [x] [move-resource] `git mv test/bin/mock-claude-hang effectful/test/resources/bin/`
- [x] [move-resource] `git mv test/bin/mock-claude-inherit-env effectful/test/resources/bin/`
- [x] [move-resource] `git mv src/main/resources/logback.xml effectful/test/resources/logback.xml`
- [x] [move-resource] Copy `effectful/test/resources/logback.xml` to `direct/test/resources/logback.xml` and `git add` it
- [x] [move-resource] Verify mock scripts in `effectful/test/resources/bin/` retain execute permissions

## Cleanup

- [x] [delete] `git rm works/iterative/claude/ClaudeCode.scala` (unused effectful facade)
- [x] [delete] `git rm works/iterative/claude/model.scala` (duplicate of core/model types)
- [x] [delete] `git rm project.scala` (Scala CLI config, superseded by build.mill)
- [x] [delete] `git rm publish-conf.scala` (Scala CLI publish config, superseded by build.mill)
- [x] [cleanup] Remove empty `works/` directory tree
- [x] [cleanup] Remove empty `test/` directory tree
- [x] [cleanup] Remove empty `src/` directory tree

## Verification

- [x] [verify] `mill core.compile` succeeds
- [x] [verify] `mill direct.compile` succeeds
- [x] [verify] `mill effectful.compile` succeeds
- [x] [verify] `mill core.test.compile` succeeds
- [x] [verify] `mill direct.test.compile` succeeds
- [x] [verify] `mill effectful.test.compile` succeeds
- [x] [verify] `mill core.test` passes
- [x] [verify] `mill direct.test` passes
- [x] [verify] `mill effectful.test` passes
- [x] [verify] Confirm `works/`, `test/`, `src/` directories no longer exist
- [x] [verify] Confirm `project.scala` and `publish-conf.scala` no longer exist
- [x] [verify] No source files contain changed package declarations (moves only, no package renames)
