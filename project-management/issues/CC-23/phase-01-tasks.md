# Phase 1 Tasks: Build modules and test file moves

## Setup

- [x] [impl] Record current test counts: run `./mill core.test` `./mill direct.test` `./mill effectful.test` and note the total number of tests passing
- [x] [impl] Add `object itest extends ScalaTests with TestModule.Munit` inside `core` in `build.mill` with same `mvnDeps` as `core.test` and `moduleDeps = super.moduleDeps ++ Seq(outer.test)`
- [x] [impl] Add `object itest extends ScalaTests with TestModule.Munit` inside `direct` in `build.mill` with same `mvnDeps` as `direct.test` and `moduleDeps = super.moduleDeps ++ Seq(outer.test)`
- [x] [impl] Add `object itest extends ScalaTests with TestModule.Munit` inside `effectful` in `build.mill` with same `mvnDeps` as `effectful.test` and `moduleDeps = super.moduleDeps ++ Seq(effectful.test, direct.test)`
- [x] [verify] Run `./mill resolve __.itest` to confirm Mill recognizes all three itest modules
- [x] [verify] Run `./mill __.compile` to confirm empty itest modules compile alongside existing code

## File Moves — core

- [x] [impl] Create directory `core/itest/src/works/iterative/claude/core/model/`
- [x] [impl] `git mv core/test/src/works/iterative/claude/core/model/SDKUserMessageRoundTripTest.scala core/itest/src/works/iterative/claude/core/model/`
- [x] [impl] `git mv core/test/src/works/iterative/claude/core/model/SDKUserMessageE2ETest.scala core/itest/src/works/iterative/claude/core/model/`
- [x] [verify] Run `./mill core.compile` and `./mill core.test.compile` and `./mill core.itest.compile` — all succeed

## File Moves — direct

- [x] [impl] Create directory `direct/itest/src/works/iterative/claude/direct/`
- [x] [impl] Create directory `direct/itest/src/works/iterative/claude/direct/internal/cli/`
- [x] [impl] `git mv direct/test/src/works/iterative/claude/direct/ClaudeCodeIntegrationTest.scala direct/itest/src/works/iterative/claude/direct/`
- [x] [impl] `git mv direct/test/src/works/iterative/claude/direct/ClaudeCodeStreamingTest.scala direct/itest/src/works/iterative/claude/direct/`
- [x] [impl] `git mv direct/test/src/works/iterative/claude/direct/SessionIntegrationTest.scala direct/itest/src/works/iterative/claude/direct/`
- [x] [impl] `git mv direct/test/src/works/iterative/claude/direct/SessionErrorIntegrationTest.scala direct/itest/src/works/iterative/claude/direct/`
- [x] [impl] `git mv direct/test/src/works/iterative/claude/direct/SessionE2ETest.scala direct/itest/src/works/iterative/claude/direct/`
- [x] [impl] `git mv direct/test/src/works/iterative/claude/direct/internal/cli/EnvironmentTest.scala direct/itest/src/works/iterative/claude/direct/internal/cli/`
- [x] [impl] `git mv direct/test/src/works/iterative/claude/direct/internal/cli/ProcessManagerTest.scala direct/itest/src/works/iterative/claude/direct/internal/cli/`
- [x] [verify] Run `./mill direct.compile` and `./mill direct.test.compile` and `./mill direct.itest.compile` — all succeed

## File Moves — effectful (simple moves)

- [x] [impl] Create directory `effectful/itest/src/works/iterative/claude/`
- [x] [impl] Create directory `effectful/itest/src/works/iterative/claude/effectful/`
- [x] [impl] Create directory `effectful/itest/src/works/iterative/claude/effectful/internal/cli/`
- [x] [impl] `git mv effectful/test/src/works/iterative/claude/ClaudeCodeIntegrationTest.scala effectful/itest/src/works/iterative/claude/`
- [x] [impl] `git mv effectful/test/src/works/iterative/claude/ClaudeCodeLoggingTest.scala effectful/itest/src/works/iterative/claude/`
- [x] [impl] `git mv effectful/test/src/works/iterative/claude/effectful/SessionIntegrationTest.scala effectful/itest/src/works/iterative/claude/effectful/`
- [x] [impl] `git mv effectful/test/src/works/iterative/claude/effectful/SessionErrorIntegrationTest.scala effectful/itest/src/works/iterative/claude/effectful/`
- [x] [impl] `git mv effectful/test/src/works/iterative/claude/effectful/SessionE2ETest.scala effectful/itest/src/works/iterative/claude/effectful/`
- [x] [impl] `git mv effectful/test/src/works/iterative/claude/effectful/internal/cli/EnvironmentValidationTest.scala effectful/itest/src/works/iterative/claude/effectful/internal/cli/`
- [x] [impl] `git mv effectful/test/src/works/iterative/claude/effectful/internal/cli/EnvironmentInheritanceTest.scala effectful/itest/src/works/iterative/claude/effectful/internal/cli/`
- [x] [impl] `git mv effectful/test/src/works/iterative/claude/effectful/internal/cli/EnvironmentSecurityTest.scala effectful/itest/src/works/iterative/claude/effectful/internal/cli/`

## Split — effectful ProcessManagerTest

- [x] [impl] Read `effectful/test/src/works/iterative/claude/effectful/internal/cli/ProcessManagerTest.scala` and identify the 7 unit tests (`configureProcessBuilder` tests) vs 3 integration tests (`executeProcess` tests)
- [x] [impl] Create `effectful/itest/src/works/iterative/claude/effectful/internal/cli/ProcessManagerIntegrationTest.scala` containing only the 3 `executeProcess` tests
- [x] [impl] Remove the 3 `executeProcess` tests from the original `effectful/test/src/.../ProcessManagerTest.scala`, leaving only the 7 `configureProcessBuilder` unit tests
- [x] [verify] Run `./mill effectful.test.compile` and `./mill effectful.itest.compile` — both succeed

## Verification

- [x] [verify] Run `./mill __.compile` — full project compiles
- [x] [verify] Run `./mill __.test` — only unit tests run, all pass
- [x] [verify] Run `./mill __.itest` — only integration/E2E tests run, all pass (some may be skipped due to CLI unavailability, that is expected)
- [x] [verify] Run `./mill core.test`, `./mill direct.test`, `./mill effectful.test` individually — all pass
- [x] [verify] Run `./mill core.itest`, `./mill direct.itest`, `./mill effectful.itest` individually — all pass
- [x] [verify] Compare total test count (test + itest) against the count recorded in Setup — no tests lost or duplicated
- [x] [verify] Commit all changes

## Documentation

- [x] [impl] Update `CLAUDE.md` — add `./mill __.itest` to the build commands section
- [x] [impl] Update `ARCHITECTURE.md` — add or update testing strategy section to describe test/itest split and classification criteria
- [x] [impl] Update `README.md` — add `./mill __.itest` if test commands are mentioned
- [x] [verify] Commit documentation changes
