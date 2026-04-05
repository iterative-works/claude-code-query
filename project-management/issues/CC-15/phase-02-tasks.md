# Phase 02 Tasks: SessionOptions configuration

**Issue:** CC-15
**Phase Context:** phase-02-context.md

## Tasks

### Setup

- [x] [setup] Read `QueryOptions.scala`, `CLIArgumentBuilder.scala`, `CLIArgumentBuilderTest.scala`, and `PermissionMode.scala` to confirm field names, types, defaults, and test patterns
- [x] [setup] Create empty test file `core/test/src/works/iterative/claude/core/model/SessionOptionsTest.scala` with package declaration, imports, and PURPOSE comments
- [x] [setup] Create empty test file `core/test/src/works/iterative/claude/core/cli/SessionOptionsArgsTest.scala` with package declaration, imports, and PURPOSE comments

### Tests (Red Phase)

#### SessionOptions construction and builder tests

- [x] [test] Write test "SessionOptions() has all None fields" -- construct with no args, verify every field is `None`
- [x] [test] Write test "SessionOptions.defaults equals SessionOptions()" -- verify `SessionOptions.defaults == SessionOptions()`
- [x] [test] Write tests for each `with*` builder method (one test per method, 18 total) -- verify calling `withX(value)` on `SessionOptions()` sets only the targeted field and all others remain `None`; group in a single test to keep the file manageable, e.g. "each with* builder sets only its field"

#### SessionOptions CLI argument mapping tests

- [x] [test] Write test "default options produce only the three required session flags" -- `buildSessionArgs(SessionOptions())` returns exactly `List("--print", "--input-format", "stream-json", "--output-format", "stream-json")`
- [x] [test] Write test "required session flags appear at the start of the argument list" -- set a field like `model`, verify the first 5 elements are the required flags
- [x] [test] Write test "no trailing prompt argument" -- `buildSessionArgs(SessionOptions())` has exactly 5 elements
- [x] [test] Write test "maxTurns maps to --max-turns" -- mirror pattern from `CLIArgumentBuilderTest`
- [x] [test] Write test "model maps to --model"
- [x] [test] Write test "allowedTools maps to --allowedTools with comma-joined value"
- [x] [test] Write test "disallowedTools maps to --disallowedTools with comma-joined value"
- [x] [test] Write test "systemPrompt maps to --system-prompt"
- [x] [test] Write test "appendSystemPrompt maps to --append-system-prompt"
- [x] [test] Write test "continueConversation Some(true) maps to --continue, Some(false) produces no flag"
- [x] [test] Write test "resume maps to --resume"
- [x] [test] Write test "permissionMode maps to --permission-mode for all three enum values"
- [x] [test] Write test "maxThinkingTokens maps to --max-thinking-tokens"
- [x] [test] Write test "None values produce no extra args beyond the required flags"
- [x] [test] Write test "multiple options combine correctly" -- set several fields, verify all flags present with correct values after the required prefix

### Implementation (Green Phase)

- [x] [impl] Create `core/src/works/iterative/claude/core/model/SessionOptions.scala` -- case class with all fields from `QueryOptions` except `prompt`, all defaulting to `None`; include fluent `with*` builder methods matching `QueryOptions` style; companion object with `val defaults: SessionOptions = SessionOptions()`; include PURPOSE comments
- [x] [impl] Run `SessionOptionsTest` and verify all construction/builder tests pass
- [x] [impl] Add `buildSessionArgs(options: SessionOptions): List[String]` method to `CLIArgumentBuilder.scala` -- prepend `--print`, `--input-format`, `stream-json`, `--output-format`, `stream-json`; append per-field flag mapping duplicated from `buildArgs` (same logic, same flag names); no trailing prompt; add `SessionOptions` to the import
- [x] [impl] Run `SessionOptionsArgsTest` and verify all argument mapping tests pass

### Integration

- [x] [integration] Run full test suite (`./mill __.test`) to confirm no regressions in existing `CLIArgumentBuilderTest` and all other tests
- [x] [integration] Verify no compilation warnings in `./mill __.compile`
**Phase Status:** Complete
