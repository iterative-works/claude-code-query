# Phase 02 Tasks: SessionOptions configuration

**Issue:** CC-15
**Phase Context:** phase-02-context.md

## Tasks

### Setup

- [ ] [setup] Read `QueryOptions.scala`, `CLIArgumentBuilder.scala`, `CLIArgumentBuilderTest.scala`, and `PermissionMode.scala` to confirm field names, types, defaults, and test patterns
- [ ] [setup] Create empty test file `core/test/src/works/iterative/claude/core/model/SessionOptionsTest.scala` with package declaration, imports, and PURPOSE comments
- [ ] [setup] Create empty test file `core/test/src/works/iterative/claude/core/cli/SessionOptionsArgsTest.scala` with package declaration, imports, and PURPOSE comments

### Tests (Red Phase)

#### SessionOptions construction and builder tests

- [ ] [test] Write test "SessionOptions() has all None fields" -- construct with no args, verify every field is `None`
- [ ] [test] Write test "SessionOptions.defaults equals SessionOptions()" -- verify `SessionOptions.defaults == SessionOptions()`
- [ ] [test] Write tests for each `with*` builder method (one test per method, 18 total) -- verify calling `withX(value)` on `SessionOptions()` sets only the targeted field and all others remain `None`; group in a single test to keep the file manageable, e.g. "each with* builder sets only its field"

#### SessionOptions CLI argument mapping tests

- [ ] [test] Write test "default options produce only the three required session flags" -- `buildSessionArgs(SessionOptions())` returns exactly `List("--print", "--input-format", "stream-json", "--output-format", "stream-json")`
- [ ] [test] Write test "required session flags appear at the start of the argument list" -- set a field like `model`, verify the first 5 elements are the required flags
- [ ] [test] Write test "no trailing prompt argument" -- `buildSessionArgs(SessionOptions())` has exactly 5 elements
- [ ] [test] Write test "maxTurns maps to --max-turns" -- mirror pattern from `CLIArgumentBuilderTest`
- [ ] [test] Write test "model maps to --model"
- [ ] [test] Write test "allowedTools maps to --allowedTools with comma-joined value"
- [ ] [test] Write test "disallowedTools maps to --disallowedTools with comma-joined value"
- [ ] [test] Write test "systemPrompt maps to --system-prompt"
- [ ] [test] Write test "appendSystemPrompt maps to --append-system-prompt"
- [ ] [test] Write test "continueConversation Some(true) maps to --continue, Some(false) produces no flag"
- [ ] [test] Write test "resume maps to --resume"
- [ ] [test] Write test "permissionMode maps to --permission-mode for all three enum values"
- [ ] [test] Write test "maxThinkingTokens maps to --max-thinking-tokens"
- [ ] [test] Write test "None values produce no extra args beyond the required flags"
- [ ] [test] Write test "multiple options combine correctly" -- set several fields, verify all flags present with correct values after the required prefix

### Implementation (Green Phase)

- [ ] [impl] Create `core/src/works/iterative/claude/core/model/SessionOptions.scala` -- case class with all fields from `QueryOptions` except `prompt`, all defaulting to `None`; include fluent `with*` builder methods matching `QueryOptions` style; companion object with `val defaults: SessionOptions = SessionOptions()`; include PURPOSE comments
- [ ] [impl] Run `SessionOptionsTest` and verify all construction/builder tests pass
- [ ] [impl] Add `buildSessionArgs(options: SessionOptions): List[String]` method to `CLIArgumentBuilder.scala` -- prepend `--print`, `--input-format`, `stream-json`, `--output-format`, `stream-json`; append per-field flag mapping duplicated from `buildArgs` (same logic, same flag names); no trailing prompt; add `SessionOptions` to the import
- [ ] [impl] Run `SessionOptionsArgsTest` and verify all argument mapping tests pass

### Integration

- [ ] [integration] Run full test suite (`./mill __.test`) to confirm no regressions in existing `CLIArgumentBuilderTest` and all other tests
- [ ] [integration] Verify no compilation warnings in `./mill __.compile`
