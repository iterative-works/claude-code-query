# Phase 1 Tasks: Option-C isolation knobs (domain + CLI + tests)

**Issue:** CC-46
**Phase:** 1 of 1
**Context:** `phase-01-context.md`

## Setup

- [ ] [setup] Read `core/src/works/iterative/claude/core/model/PermissionMode.scala` to confirm the three existing cases and Scaladoc style.
- [ ] [setup] Read `core/src/works/iterative/claude/core/model/QueryOptions.scala` and `SessionOptions.scala` to confirm existing field/helper style and `QueryOptions.simple` factory.
- [ ] [setup] Read `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala` to locate the existing `permissionMode` match and the tail `List(...).flatten` in both `buildArgs` and `buildSessionArgs`.
- [ ] [setup] Read `core/test/src/works/iterative/claude/core/cli/CLIArgumentBuilderTest.scala` and `SessionOptionsArgsTest.scala` to match existing test style.
- [ ] [run] Run `./mill __.compile` and `./mill __.test` to confirm a clean baseline.

## Implementation — Domain additions (purely additive, pre-TDD)

- [ ] [impl] Add `PermissionMode.DontAsk` case with Scaladoc "Enforce `allowedTools` as a hard allow-list; no prompts, no hangs" (file: `core/src/works/iterative/claude/core/model/PermissionMode.scala`).
- [ ] [impl] Add fields `strictMcpConfig: Option[Boolean] = None`, `mcpConfigPath: Option[String] = None`, `settingSources: List[String] = Nil` to `QueryOptions` with Scaladoc per CLI flag (file: `core/src/works/iterative/claude/core/model/QueryOptions.scala`).
- [ ] [impl] Add fluent helpers `withStrictMcpConfig`, `withMcpConfigPath`, `withSettingSources` to `QueryOptions` (same file).
- [ ] [impl] Update `QueryOptions.simple(prompt)` factory to pass `strictMcpConfig = None, mcpConfigPath = None, settingSources = Nil` as named arguments (same file).
- [ ] [impl] Add the same three fields with identical names/types/defaults/Scaladoc to `SessionOptions` (file: `core/src/works/iterative/claude/core/model/SessionOptions.scala`).
- [ ] [impl] Add the same three `with*` fluent helpers to `SessionOptions` (same file).
- [ ] [run] Run `./mill core.compile` to confirm domain additions compile cleanly. Expect a non-exhaustive-match warning at both `CLIArgumentBuilder` sites as a positive signal.

## Tests (failing first) — TDD per knob

### Group A: `PermissionMode.DontAsk`

- [ ] [test] Write test `PermissionMode.DontAsk maps to --permission-mode dontAsk` asserting `args.containsSlice(List("--permission-mode", "dontAsk"))` in `CLIArgumentBuilderTest.scala`.
- [ ] [test] Write symmetric session-side test in `SessionOptionsArgsTest.scala`.
- [ ] [run] Run `./mill core.test` and confirm both tests fail (missing branch).
- [ ] [impl] Add `case Some(PermissionMode.DontAsk) => List("--permission-mode", "dontAsk")` to `permissionModeArgs` in both `buildArgs` and `buildSessionArgs` (file: `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala`).
- [ ] [run] Run `./mill core.test` and confirm the two `DontAsk` tests are green.

### Group B: `strictMcpConfig`

- [ ] [test] Write test `strictMcpConfig = Some(true) emits --strict-mcp-config` in `CLIArgumentBuilderTest.scala`.
- [ ] [test] Write test `strictMcpConfig default (None) does not emit --strict-mcp-config` in `CLIArgumentBuilderTest.scala`.
- [ ] [test] Write test `strictMcpConfig = Some(false) does not emit --strict-mcp-config` in `CLIArgumentBuilderTest.scala`.
- [ ] [test] Write the three symmetric session-side tests in `SessionOptionsArgsTest.scala`.
- [ ] [run] Run `./mill core.test` and confirm the new `strictMcpConfig` tests fail.
- [ ] [impl] Add `strictMcpConfigArgs` match block and append it after `maxThinkingTokensArgs` in the tail `List(...).flatten` of both `buildArgs` and `buildSessionArgs`.
- [ ] [run] Run `./mill core.test` and confirm the `strictMcpConfig` tests are green.

### Group C: `mcpConfigPath`

- [ ] [test] Write test `mcpConfigPath maps to --mcp-config <path> --` asserting `args.containsSlice(List("--mcp-config", "./.mcp.json", "--"))` in `CLIArgumentBuilderTest.scala`.
- [ ] [test] Write test `mcpConfigPath default (None) does not emit --mcp-config` asserting `!args.contains("--mcp-config")` and `!args.contains("--")` in `CLIArgumentBuilderTest.scala`.
- [ ] [test] Write the two symmetric session-side tests in `SessionOptionsArgsTest.scala`.
- [ ] [run] Run `./mill core.test` and confirm the new `mcpConfigPath` tests fail.
- [ ] [impl] Add `mcpConfigPathArgs` match block and append it after `strictMcpConfigArgs` in the tail `List(...).flatten` of both `buildArgs` and `buildSessionArgs`.
- [ ] [run] Run `./mill core.test` and confirm the `mcpConfigPath` tests are green.

### Group D: `settingSources`

- [ ] [test] Write test `settingSources non-empty maps to --setting-sources with CSV value` (using `List("project", "user")` → `"project,user"`) in `CLIArgumentBuilderTest.scala`.
- [ ] [test] Write test `settingSources default (Nil) does not emit --setting-sources` in `CLIArgumentBuilderTest.scala`.
- [ ] [test] Write the two symmetric session-side tests in `SessionOptionsArgsTest.scala`.
- [ ] [run] Run `./mill core.test` and confirm the new `settingSources` tests fail.
- [ ] [impl] Add `settingSourcesArgs` `if/else` block and append it after `mcpConfigPathArgs` in the tail `List(...).flatten` of both `buildArgs` and `buildSessionArgs`.
- [ ] [run] Run `./mill core.test` and confirm the `settingSources` tests are green.

### Group E: Round-trip smoke tests + session `requiredFlags` regression

- [ ] [test] Add the verbatim "all four Option-C isolation flags round-trip in deterministic order" smoke test to `CLIArgumentBuilderTest.scala` (see phase context §Testing Strategy for the exact body, including `pmIdx < strictIdx < mcpIdx < ssIdx` ordering asserts).
- [ ] [test] Add the symmetric session-side round-trip smoke test to `SessionOptionsArgsTest.scala` using `SessionOptions().with*` builders and `buildSessionArgs`.
- [ ] [test] Add the `required session flags still appear at the start of the argument list when all four new flags are set` regression test in `SessionOptionsArgsTest.scala`, asserting `args.take(requiredFlags.length) == requiredFlags`.
- [ ] [run] Run `./mill core.test` and confirm all three smoke/regression tests are green.

## Integration / verification sweep

- [ ] [verify] Run `./mill __.compile` and confirm no non-exhaustive-match warnings (both `PermissionMode` match sites updated) and no other new warnings.
- [ ] [verify] Run `./mill __.test` and confirm all tests green (nine new `CLIArgumentBuilderTest` cases, ten new `SessionOptionsArgsTest` cases, all pre-existing tests).
- [ ] [verify] Run `./mill direct.compile` to confirm re-export propagation works in the `direct` module without source edits.
- [ ] [verify] Run `./mill effectful.compile` to confirm re-export propagation works in the `effectful` module without source edits.
- [ ] [verify] Run `sg --lang scala -p 'PermissionMode.$CASE'` (or equivalent) to confirm no non-exhaustive `PermissionMode` match exists outside `CLIArgumentBuilder` (Risk 1 mitigation).
