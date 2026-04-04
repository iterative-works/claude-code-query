# Phase 02: SessionOptions configuration

**Issue:** CC-15 - Support persistent two-way conversations with a single Claude Code session
**Estimated Effort:** 4-6 hours
**Status:** Not started

## Goals

1. Define `SessionOptions` case class in `core.model` holding all startup configuration for a streaming session (everything from `QueryOptions` except `prompt`)
2. Provide fluent builder methods on `SessionOptions` matching the `QueryOptions` style
3. Add `CLIArgumentBuilder.buildSessionArgs(options: SessionOptions): List[String]` that produces the correct CLI flags for streaming session mode, including the required `--print --input-format stream-json --output-format stream-json` flags and omitting the trailing prompt argument

## Scope

### In scope

- `SessionOptions` case class with all fields from `QueryOptions` except `prompt`
- Fluent `with*` builder methods on `SessionOptions`
- `SessionOptions.defaults` companion object factory method
- `CLIArgumentBuilder.buildSessionArgs` for converting `SessionOptions` to CLI args
- Unit tests for every `SessionOptions` field mapping in `buildSessionArgs`
- Unit tests confirming the three required session flags are always present
- Unit tests confirming `prompt` is not part of `SessionOptions`

### Out of scope

- Process management, stdin/stdout wiring (Phase 03)
- Multi-turn sequencing (Phase 04)
- Effectful API surface (Phase 05)
- `SDKUserMessage` encoding (Phase 01)
- `mcpTools` flag mapping (no corresponding CLI flag was identified; carry the field but do not emit args unless a flag is confirmed)

## Dependencies

- No code dependency on Phase 01. Phases 01 and 02 are independent foundations.
- Phase 03 depends on both Phase 01 and Phase 02.

## Approach

### 1. Create `SessionOptions` case class

Create `core/src/works/iterative/claude/core/model/SessionOptions.scala`.

Copy every field from `QueryOptions` verbatim **except `prompt`**. Field names, types, and default values must be identical so that code migrating from `QueryOptions` only needs to drop the `prompt` argument. Fields to include:

| Field | Type | Default |
|---|---|---|
| `cwd` | `Option[String]` | `None` |
| `executable` | `Option[String]` | `None` |
| `executableArgs` | `Option[List[String]]` | `None` |
| `pathToClaudeCodeExecutable` | `Option[String]` | `None` |
| `maxTurns` | `Option[Int]` | `None` |
| `allowedTools` | `Option[List[String]]` | `None` |
| `disallowedTools` | `Option[List[String]]` | `None` |
| `systemPrompt` | `Option[String]` | `None` |
| `appendSystemPrompt` | `Option[String]` | `None` |
| `mcpTools` | `Option[List[String]]` | `None` |
| `permissionMode` | `Option[PermissionMode]` | `None` |
| `continueConversation` | `Option[Boolean]` | `None` |
| `resume` | `Option[String]` | `None` |
| `model` | `Option[String]` | `None` |
| `maxThinkingTokens` | `Option[Int]` | `None` |
| `timeout` | `Option[scala.concurrent.duration.FiniteDuration]` | `None` |
| `inheritEnvironment` | `Option[Boolean]` | `None` |
| `environmentVariables` | `Option[Map[String, String]]` | `None` |

Note: `timeout`, `inheritEnvironment`, `environmentVariables`, `executable`, `executableArgs`, and `pathToClaudeCodeExecutable` are process-level configuration consumed by the session runner (Phase 03), not translated to CLI flags. Include them so the caller has one place to specify all session configuration.

### 2. Add fluent builder methods

Add a `with*` method for each field, following the exact same pattern used in `QueryOptions`:

```scala
def withCwd(cwd: String): SessionOptions = copy(cwd = Some(cwd))
def withModel(model: String): SessionOptions = copy(model = Some(model))
// ... etc for every field
```

### 3. Add `SessionOptions.defaults` factory

In the companion object, add:

```scala
object SessionOptions:
  /** Create SessionOptions with all defaults */
  val defaults: SessionOptions = SessionOptions()
```

This mirrors the spirit of `QueryOptions.simple(prompt)` but without needing a required argument.

### 4. Add `CLIArgumentBuilder.buildSessionArgs`

Extend `CLIArgumentBuilder` with a new method. The implementation should:

1. Reuse the per-option argument lists that already exist in `buildArgs` (extract them or duplicate with `SessionOptions` parameter types — see note below)
2. Prepend the three required streaming flags: `--print`, `--input-format`, `stream-json`, `--output-format`, `stream-json`
3. Append all the option-driven flags (same as `buildArgs` minus any prompt-specific logic)
4. **Not** append a trailing prompt argument

The cleanest implementation is to extract the repeated argument-building logic into a private helper that accepts the shared fields, then call it from both `buildArgs` and `buildSessionArgs`. This avoids duplicating the per-option mapping logic. However, since `QueryOptions` and `SessionOptions` are separate case classes (not related by inheritance), the shared fields would need to be passed individually or the helper could accept a structural type. The simplest approach: duplicate the field-by-field logic in `buildSessionArgs` since it is mechanical and all the field names are identical — the duplication is acceptable given the small size. Defer extraction to a future refactor only if a third call site appears.

Expected output for `SessionOptions.defaults`:

```
List("--print", "--input-format", "stream-json", "--output-format", "stream-json")
```

Expected output for `SessionOptions().withModel("claude-opus-4-5").withSystemPrompt("You are a code reviewer")`:

```
List("--print", "--input-format", "stream-json", "--output-format", "stream-json",
     "--model", "claude-opus-4-5",
     "--system-prompt", "You are a code reviewer")
```

## Files to Modify/Create

### New files

- `core/src/works/iterative/claude/core/model/SessionOptions.scala` — case class with fluent builder methods and companion object
- `core/test/src/works/iterative/claude/core/cli/SessionOptionsArgsTest.scala` — unit tests for `buildSessionArgs`
- `core/test/src/works/iterative/claude/core/model/SessionOptionsTest.scala` — unit tests for `SessionOptions` construction and builder methods

### Files to modify

- `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala` — add `buildSessionArgs(options: SessionOptions): List[String]` method and update import

### Reference files (no changes)

- `core/src/works/iterative/claude/core/model/QueryOptions.scala` — field list and fluent builder pattern to replicate
- `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala` — existing arg-building patterns
- `core/test/src/works/iterative/claude/core/cli/CLIArgumentBuilderTest.scala` — test style to follow
- `core/src/works/iterative/claude/core/model/PermissionMode.scala` — imported by both options classes

## Testing Strategy

### Unit tests — `SessionOptionsTest`

1. **`SessionOptions()` has all None fields** — construct with no args, verify no field is set
2. **`SessionOptions.defaults` equals `SessionOptions()`** — sanity check on the factory
3. **Each `with*` builder sets the correct field** — one test per builder method (18 tests), verifying only the targeted field changes and all others remain as before
4. **`SessionOptions` has no `prompt` field** — compile-time fact; add a comment in the test explaining this is enforced by the type

### Unit tests — `SessionOptionsArgsTest`

1. **Default options produce the three required flags** — `buildSessionArgs(SessionOptions())` contains `--print`, `--input-format stream-json`, `--output-format stream-json`
2. **Required flags appear first** — verify the three flags are at the start of the list (index 0..4)
3. **No trailing prompt argument** — `buildSessionArgs(SessionOptions())` result has exactly 5 elements (the three flag pairs)
4. **`maxTurns` maps to `--max-turns`** — mirrors existing `CLIArgumentBuilderTest` pattern
5. **`model` maps to `--model`**
6. **`allowedTools` maps to `--allowedTools`** with comma-joined value
7. **`disallowedTools` maps to `--disallowedTools`** with comma-joined value
8. **`systemPrompt` maps to `--system-prompt`**
9. **`appendSystemPrompt` maps to `--append-system-prompt`**
10. **`continueConversation = Some(true)` maps to `--continue`**
11. **`continueConversation = Some(false)` produces no flag**
12. **`resume` maps to `--resume`**
13. **`permissionMode` maps to `--permission-mode`** (all three enum values)
14. **`maxThinkingTokens` maps to `--max-thinking-tokens`**
15. **`None` values produce no extra args** — options with all None fields produce only the 5 required flag tokens
16. **Multiple options combine correctly** — set several fields, verify all flags present with correct values

### Integration tests

None required for this phase. `SessionOptions` and `buildSessionArgs` are pure data transformation with no I/O. Phase 03 will add integration tests that exercise the full process launch path using `SessionOptions`.

### E2E tests

None required for this phase. Phase 03 carries E2E coverage.

## Acceptance Criteria

1. `SessionOptions` case class exists in `works.iterative.claude.core.model` with all fields from `QueryOptions` except `prompt`
2. `SessionOptions` has no `prompt` field (enforced at compile time)
3. Every field has a corresponding fluent `with*` builder method returning `SessionOptions`
4. `SessionOptions.defaults` exists and equals `SessionOptions()` with all None fields
5. `CLIArgumentBuilder.buildSessionArgs(options: SessionOptions): List[String]` exists and always includes `--print`, `--input-format stream-json`, `--output-format stream-json`
6. `buildSessionArgs` maps all option fields to the same CLI flags as `buildArgs` does for `QueryOptions`
7. `buildSessionArgs` does not append a trailing prompt argument
8. All new files start with the required two-line PURPOSE comment
9. All unit tests pass with no compilation warnings
10. All existing tests continue to pass (no regressions in `CLIArgumentBuilderTest`)
