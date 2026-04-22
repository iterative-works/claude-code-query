# Phase 1: Option-C isolation knobs (domain + CLI + tests)

## Goals

Extend the SDK's configuration surface with the four CLI knobs needed for hard tool-isolation (PROC-401 / Option C): `--strict-mcp-config`, `--mcp-config <path>`, `--setting-sources <csv>`, and `--permission-mode dontAsk`. This is a purely additive, single-phase change merging the domain, CLI-translation, and test layers because they are mechanically coupled through Scala 3 enum exhaustiveness and the project's TDD rule.

- Add `PermissionMode.DontAsk` enum case.
- Add three new fields + fluent helpers on `QueryOptions` and `SessionOptions`.
- Extend `CLIArgumentBuilder.buildArgs` and `buildSessionArgs` with one new `match` branch and three new translations each.
- Lock presence, absence, and deterministic argv ordering of all four flags via tests.
- Preserve byte-identical argv for callers that do not set any of the new knobs.

## Scope

**In-scope (merged into this single phase):**
- Domain / model layer: `PermissionMode`, `QueryOptions`, `SessionOptions`.
- CLI translation layer: `CLIArgumentBuilder` (both public methods).
- Test layer: `CLIArgumentBuilderTest`, `SessionOptionsArgsTest`.

**Out-of-scope:**
- No source edits to the `direct` or `effectful` modules. They pick up the new domain additions transparently via their existing `type`/`val` re-exports in `direct/src/works/iterative/claude/direct/package.scala` and `effectful/src/works/iterative/claude/effectful/package.scala`.
- No behaviour change for existing callers (defaults must emit zero new argv tokens).
- No new abstractions (builder, typeclass, DSL). The existing `Option[T]` + `match` / `if` pattern is mirrored exactly.
- No rename of `CLIArgumentBuilderTest.scala` to `QueryOptionsArgsTest.scala`; the existing file is the de-facto QueryOptions argv test.
- No deprecation or renaming of existing `PermissionMode` cases.
- No introduction of a typed `SettingSource` enum. `settingSources` stays `List[String]` per the issue.

## Dependencies

**Prior phases:** None (this is phase 1 of 1).

**Upstream components required (existing, to be extended):**
- `enum PermissionMode` with cases `Default`, `AcceptEdits`, `BypassPermissions` (`core/src/works/iterative/claude/core/model/PermissionMode.scala`).
- `case class QueryOptions` with existing fields including `permissionMode: Option[PermissionMode] = None`; fluent helpers `withPermissionMode`, `withContinueConversation`, `withInheritEnvironment`; factory `QueryOptions.simple(prompt)` (`core/src/works/iterative/claude/core/model/QueryOptions.scala`).
- `case class SessionOptions` with symmetric shape; `object SessionOptions` providing `val defaults: SessionOptions = SessionOptions()` (`core/src/works/iterative/claude/core/model/SessionOptions.scala`).
- `CLIArgumentBuilder.buildArgs(QueryOptions)` and `CLIArgumentBuilder.buildSessionArgs(SessionOptions)` with their current `permissionMode` match blocks (`core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala`).
- Existing test files `CLIArgumentBuilderTest.scala` and `SessionOptionsArgsTest.scala`, extended by adding new `test("...")` blocks.

**Downstream consumers:**
- `direct` module — re-exports `type PermissionMode`, `val PermissionMode`, `type QueryOptions`, `val QueryOptions`, `type SessionOptions`, `val SessionOptions`. No source edit required; recompile propagates the new case / fields automatically.
- `effectful` module — same re-export pattern, same outcome.

## Approach

Ordered implementation sequence, mirroring the analysis's "Implementation Sequence":

1. **Domain layer first.** Add `PermissionMode.DontAsk`. Add three fields + three fluent helpers on each of `QueryOptions` and `SessionOptions`. Update `QueryOptions.simple` to pass the new defaults explicitly (the factory already enumerates every field by name, so keep it exhaustive). `SessionOptions.defaults` needs no change because the new fields have defaults.

2. **TDD pair: CLI translation + tests.** For each new field/branch, in order `permissionMode DontAsk` → `strictMcpConfig` → `mcpConfigPath` → `settingSources`:
   1. Add a failing test in `CLIArgumentBuilderTest` (and symmetric test in `SessionOptionsArgsTest`).
   2. Run `./mill core.test` to confirm it fails.
   3. Add the minimal match branch / translation in `CLIArgumentBuilder` (both `buildArgs` and `buildSessionArgs`).
   4. Run `./mill core.test` to confirm green.
   Then add the "absent when default" and "`Some(false)`" variants, and finally the two round-trip smoke tests.

3. **Verification sweep.** Run `./mill __.compile` to confirm no non-exhaustive-match warnings (this is the positive regression signal that both builder sites were updated). Run `./mill __.test`. Run `./mill direct.compile` and `./mill effectful.compile` to confirm the re-exports propagate without source edits.

## Component Specifications

### 1. `PermissionMode` — add `DontAsk` case

**File:** `/home/mph/Devel/iw/claude-code-query-CC-46/core/src/works/iterative/claude/core/model/PermissionMode.scala`

**Change:** Add one enum case, `DontAsk`, after the existing three. Extend the file's enum Scaladoc (or add a per-case Scaladoc line) to describe `DontAsk` as "Enforce `allowedTools` as a hard allow-list; no prompts, no hangs".

**Shape after change:**

```scala
enum PermissionMode:
  case Default
  case AcceptEdits
  case BypassPermissions
  /** Enforce `allowedTools` as a hard allow-list; no prompts, no hangs. */
  case DontAsk
```

---

### 2. `QueryOptions` — add three fields and three fluent helpers

**File:** `/home/mph/Devel/iw/claude-code-query-CC-46/core/src/works/iterative/claude/core/model/QueryOptions.scala`

**Changes:**
- Add three fields to the case class (with Scaladoc matching the existing field-comment style):
  - `strictMcpConfig: Option[Boolean] = None` — maps to `--strict-mcp-config`. `Some(true)` emits the flag; `Some(false)` and `None` emit nothing (no `--no-strict-mcp-config` flag exists in the CLI today).
  - `mcpConfigPath: Option[String] = None` — maps to `--mcp-config <path> --`. The trailing `"--"` terminator is mandatory because `--mcp-config` is variadic.
  - `settingSources: List[String] = Nil` — maps to `--setting-sources <csv>`. Empty list emits nothing.
- Add three fluent helpers inside the case class body, after the existing `with*` methods:

```scala
def withStrictMcpConfig(flag: Boolean): QueryOptions =
  copy(strictMcpConfig = Some(flag))
def withMcpConfigPath(path: String): QueryOptions =
  copy(mcpConfigPath = Some(path))
def withSettingSources(sources: List[String]): QueryOptions =
  copy(settingSources = sources)
```

- Update the `QueryOptions.simple(prompt)` factory to explicitly pass the three new defaults as named arguments (`strictMcpConfig = None, mcpConfigPath = None, settingSources = Nil`), keeping the factory exhaustive per the existing convention.

---

### 3. `SessionOptions` — symmetric three fields and three fluent helpers

**File:** `/home/mph/Devel/iw/claude-code-query-CC-46/core/src/works/iterative/claude/core/model/SessionOptions.scala`

**Changes:**
- Add three fields to the case class with identical names, types, defaults, and Scaladoc as on `QueryOptions`:
  - `strictMcpConfig: Option[Boolean] = None`
  - `mcpConfigPath: Option[String] = None`
  - `settingSources: List[String] = Nil`
- Add three fluent helpers inside the case class body:

```scala
def withStrictMcpConfig(flag: Boolean): SessionOptions =
  copy(strictMcpConfig = Some(flag))
def withMcpConfigPath(path: String): SessionOptions =
  copy(mcpConfigPath = Some(path))
def withSettingSources(sources: List[String]): SessionOptions =
  copy(settingSources = sources)
```

- `object SessionOptions.defaults` does not need to change; its `SessionOptions()` constructor call picks up the new defaults automatically.

---

### 4. `CLIArgumentBuilder` — new `DontAsk` branch + three new translations, in both builders

**File:** `/home/mph/Devel/iw/claude-code-query-CC-46/core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala`

**Changes apply symmetrically to both `buildArgs(QueryOptions)` and `buildSessionArgs(SessionOptions)`.**

**a) `DontAsk` branch in the existing `permissionMode` match:**

Add a new case to the existing `permissionModeArgs` match block so it becomes exhaustive over all four enum cases:

```scala
case Some(PermissionMode.DontAsk) => List("--permission-mode", "dontAsk")
```

The `permissionModeArgs` value's position in the final `List(...).flatten` is unchanged.

**b) `strictMcpConfigArgs`:**

```scala
val strictMcpConfigArgs = options.strictMcpConfig match
  case Some(true) => List("--strict-mcp-config")
  case _          => Nil
```

Emits `List("--strict-mcp-config")` only for `Some(true)`. Both `None` and `Some(false)` emit `Nil` (no negation flag exists in the CLI today).

**c) `mcpConfigPathArgs`:**

```scala
val mcpConfigPathArgs = options.mcpConfigPath match
  case Some(path) => List("--mcp-config", path, "--")
  case None       => Nil
```

Emits the three-element list `List("--mcp-config", path, "--")` for `Some(path)`. Emits `Nil` for `None`. The trailing `"--"` is mandatory because `--mcp-config` is variadic and would otherwise consume downstream argv tokens (prompt, caller-appended flags) as additional config paths.

**d) `settingSourcesArgs`:**

```scala
val settingSourcesArgs =
  if options.settingSources.nonEmpty then
    List("--setting-sources", options.settingSources.mkString(","))
  else Nil
```

Emits `List("--setting-sources", "<csv>")` when the list is non-empty. Emits `Nil` otherwise.

**e) Argv ordering at the tail:**

In the final `List(...).flatten` of `buildArgs`, append the three new arg groups after `maxThinkingTokensArgs` in this exact order:

```
maxTurnsArgs, modelArgs, allowedToolsArgs, disallowedToolsArgs,
systemPromptArgs, appendSystemPromptArgs, continueConversationArgs,
resumeArgs, permissionModeArgs, maxThinkingTokensArgs,
strictMcpConfigArgs, mcpConfigPathArgs, settingSourcesArgs
```

In `buildSessionArgs`, the same three groups are appended in the same order after `maxThinkingTokensArgs`, and the required streaming flags block (`--print`, `--input-format`, `stream-json`, `--output-format`, `stream-json`) remains first so `args.take(requiredFlags.length) == requiredFlags` continues to hold.

`permissionModeArgs` does not move; only the `DontAsk` branch is added to its match.

Both `buildArgs` and `buildSessionArgs` receive the same four additions in the same order.

---

### 5. `CLIArgumentBuilderTest` — new tests for QueryOptions-side coverage

**File:** `/home/mph/Devel/iw/claude-code-query-CC-46/core/test/src/works/iterative/claude/core/cli/CLIArgumentBuilderTest.scala`

**Change:** Append nine new `test("...")` blocks covering the four new knobs plus the round-trip smoke test. See Testing Strategy below for the exact list and the verbatim smoke-test body.

---

### 6. `SessionOptionsArgsTest` — new tests for SessionOptions-side coverage

**File:** `/home/mph/Devel/iw/claude-code-query-CC-46/core/test/src/works/iterative/claude/core/cli/SessionOptionsArgsTest.scala`

**Change:** Append the symmetric nine test blocks, plus one regression test asserting `args.take(requiredFlags.length) == requiredFlags` still holds when all four new flags are set. See Testing Strategy below.

## Files to Modify

**Domain / model layer (`core/src/.../model/`):**
- `/home/mph/Devel/iw/claude-code-query-CC-46/core/src/works/iterative/claude/core/model/PermissionMode.scala`
- `/home/mph/Devel/iw/claude-code-query-CC-46/core/src/works/iterative/claude/core/model/QueryOptions.scala`
- `/home/mph/Devel/iw/claude-code-query-CC-46/core/src/works/iterative/claude/core/model/SessionOptions.scala`

**CLI translation layer (`core/src/.../cli/`):**
- `/home/mph/Devel/iw/claude-code-query-CC-46/core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala`

**Test layer (`core/test/src/.../cli/`):**
- `/home/mph/Devel/iw/claude-code-query-CC-46/core/test/src/works/iterative/claude/core/cli/CLIArgumentBuilderTest.scala`
- `/home/mph/Devel/iw/claude-code-query-CC-46/core/test/src/works/iterative/claude/core/cli/SessionOptionsArgsTest.scala`

## Testing Strategy

TDD for every new field and branch: write the failing test first, run it to confirm the failure mode (missing flag / non-exhaustive-match warning / compilation error on the unknown helper), then add the translation branch. No mocks. Pure `List[String]` comparisons.

### `CLIArgumentBuilderTest.scala` — nine new tests

1. `strictMcpConfig = Some(true) emits --strict-mcp-config` — assert `args.contains("--strict-mcp-config")`.
2. `strictMcpConfig default (None) does not emit --strict-mcp-config` — assert `!args.contains("--strict-mcp-config")`.
3. `strictMcpConfig = Some(false) does not emit --strict-mcp-config` — assert `!args.contains("--strict-mcp-config")` (documents the "no negation flag" behaviour).
4. `mcpConfigPath maps to --mcp-config <path> --` — assert `args.containsSlice(List("--mcp-config", "./.mcp.json", "--"))`.
5. `mcpConfigPath default (None) does not emit --mcp-config` — assert `!args.contains("--mcp-config")` and `!args.contains("--")` (no stray terminator when the flag is absent).
6. `settingSources non-empty maps to --setting-sources with CSV value` — assert `args.contains("--setting-sources")` and `args.contains("project,user")` for `List("project", "user")`.
7. `settingSources default (Nil) does not emit --setting-sources` — assert `!args.contains("--setting-sources")`.
8. `PermissionMode.DontAsk maps to --permission-mode dontAsk` — assert `args.containsSlice(List("--permission-mode", "dontAsk"))`.
9. Smoke round-trip test — verbatim from the analysis:

```scala
test("all four Option-C isolation flags round-trip in deterministic order"):
  val options = QueryOptions
    .simple("test prompt")
    .withStrictMcpConfig(true)
    .withMcpConfigPath("./.mcp.json")
    .withSettingSources(List("project"))
    .withPermissionMode(PermissionMode.DontAsk)

  val args = CLIArgumentBuilder.buildArgs(options)

  // presence
  assert(args.contains("--strict-mcp-config"))
  // --mcp-config is variadic; must be followed by "--" to isolate it
  assert(args.containsSlice(List("--mcp-config", "./.mcp.json", "--")))
  assert(args.containsSlice(List("--setting-sources", "project")))
  assert(args.containsSlice(List("--permission-mode", "dontAsk")))

  // deterministic order: permission-mode precedes the MCP/settings trio,
  // which appears as strict -> mcp-config (+ terminator) -> setting-sources
  val pmIdx = args.indexOf("--permission-mode")
  val strictIdx = args.indexOf("--strict-mcp-config")
  val mcpIdx = args.indexOf("--mcp-config")
  val ssIdx = args.indexOf("--setting-sources")
  assert(pmIdx < strictIdx)
  assert(strictIdx < mcpIdx)
  assert(mcpIdx < ssIdx)
```

### `SessionOptionsArgsTest.scala` — symmetric nine tests + required-flags regression

Mirror tests 1-8 above using `SessionOptions().with*` builders and `CLIArgumentBuilder.buildSessionArgs(...)`.

Add a session-flavoured round-trip test analogous to #9, constructed from:

```scala
SessionOptions()
  .withStrictMcpConfig(true)
  .withMcpConfigPath("./.mcp.json")
  .withSettingSources(List("project"))
  .withPermissionMode(PermissionMode.DontAsk)
```

with the same `containsSlice` / `indexOf` ordering asserts.

Add one additional regression test:

> `required session flags still appear at the start of the argument list when all four new flags are set`

Build the options as above and assert `args.take(requiredFlags.length) == requiredFlags`. This locks in the tail-append invariant and protects the existing `requiredFlags` preamble.

### Regression coverage

- All existing tests in both files must pass unchanged.
- The `PermissionMode` match in both `CLIArgumentBuilder` methods is exhaustive after this change; compilation without a non-exhaustive-match warning is the positive signal that both match sites were updated.

## Acceptance Criteria

- [ ] `PermissionMode.DontAsk` enum case exists and is exhaustively matched in both `buildArgs` and `buildSessionArgs`.
- [ ] `QueryOptions` has new fields `strictMcpConfig: Option[Boolean] = None`, `mcpConfigPath: Option[String] = None`, `settingSources: List[String] = Nil`.
- [ ] `SessionOptions` has the same three fields with the same names, types, and defaults.
- [ ] `QueryOptions` exposes `withStrictMcpConfig(flag: Boolean)`, `withMcpConfigPath(path: String)`, `withSettingSources(sources: List[String])`.
- [ ] `SessionOptions` exposes the same three `with*` helpers with symmetric signatures.
- [ ] `QueryOptions.simple` factory updated to pass the three new defaults as named arguments, keeping the factory exhaustive.
- [ ] When `mcpConfigPath = Some(path)`, the argv contains the three-element slice `List("--mcp-config", path, "--")` (terminator included) in both builders.
- [ ] Argv ordering is locked by tests: `permissionModeArgs` precedes the MCP/settings trio; within that trio, order is strict → mcp-config(+terminator) → setting-sources; and for `buildSessionArgs`, `requiredFlags` remains at positions 0..4.
- [ ] `./mill __.compile` succeeds with no new warnings (specifically, no non-exhaustive `PermissionMode` match warning from either builder site).
- [ ] `./mill __.test` is green, including the nine new `CLIArgumentBuilderTest` cases, the symmetric ten new `SessionOptionsArgsTest` cases (nine + required-flags regression), and all pre-existing tests.
- [ ] `./mill direct.compile` and `./mill effectful.compile` are green, proving re-export propagation works without source edits in those modules.
- [ ] Scaladoc added on the three new fields on both `QueryOptions` and `SessionOptions`, describing each CLI flag they map to in the same style as existing field comments. Scaladoc added on the new `PermissionMode.DontAsk` case describing it as "Enforce `allowedTools` as a hard allow-list; no prompts, no hangs".

## Risks

**Risk 1: Non-exhaustive `PermissionMode` match somewhere other than `CLIArgumentBuilder`.**
Likelihood: Low. Impact: Medium (compiler warning, possible CI failure if `-Xfatal-warnings` is enabled). Mitigation: before committing, search the repo for all `PermissionMode` use sites with `sg --lang scala -p 'PermissionMode.$CASE'` and `rg 'case PermissionMode\.'`. The issue-provided survey lists only `buildArgs` and `buildSessionArgs`, but re-verify during implementation.

**Risk 2: Consumer (PROC-401) expects a different argv order than the one chosen here.**
Likelihood: Low. Impact: Low (the CLI accepts any order; the smoke test only pins one). Mitigation: ordering is locked by the resolved decision (permission-mode → strict → mcp-config+terminator → setting-sources, tail-appended) and asserted in the smoke tests. If PROC-401 validation surfaces a different expectation, adjust the smoke tests and this analysis in follow-up rather than changing behaviour silently.

**Risk 3: `settingSources: List[String]` is stringly typed and may drift from the CLI's accepted values.**
Likelihood: Low. Impact: Low (the issue explicitly specifies `List[String]`). Mitigation: stick with `List[String]` per the issue. A future ticket can introduce a typed `SettingSource` enum if PROC-401 or another consumer asks for it. Note this in the journal rather than acting now (YAGNI).

## Estimated Effort

2.75-4.5 hours total for the merged phase:
- Domain / model layer: 0.75-1.25 hours
- CLI translation layer: 0.75-1.25 hours
- Test layer: 1.25-2 hours

Confidence: High. Pure additive mirror of a well-understood existing pattern; all open questions resolved in the analysis; single-module scope with no cross-module source churn.
