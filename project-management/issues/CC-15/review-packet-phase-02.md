---
generated_from: 7db6751172e0715c77b0ef37e232ad849c8b2af7
generated_at: 2026-04-04T20:39:58Z
branch: CC-15-phase-02
issue_id: CC-15
phase: 2
files_analyzed:
  - core/src/works/iterative/claude/core/model/SessionOptions.scala
  - core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala
  - core/test/src/works/iterative/claude/core/model/SessionOptionsTest.scala
  - core/test/src/works/iterative/claude/core/cli/SessionOptionsArgsTest.scala
---

# Review Packet: Phase 2 - SessionOptions Configuration

## Goals

This phase establishes the configuration foundation for persistent Claude Code streaming sessions. It provides the data types and CLI argument mapping needed before any process management or session lifecycle code can be written (Phase 3 and beyond).

Key objectives:

- Define `SessionOptions`, a case class mirroring `QueryOptions` but without a `prompt` field, since prompts arrive per-turn in session mode rather than as a startup argument
- Provide fluent `with*` builder methods on `SessionOptions` so callers have a familiar, ergonomic API identical in style to `QueryOptions`
- Add `CLIArgumentBuilder.buildSessionArgs(options: SessionOptions): List[String]` that always prepends the three required streaming flags (`--print`, `--input-format stream-json`, `--output-format stream-json`) and maps all option fields to their CLI equivalents without appending a trailing prompt

## Scenarios

- [ ] `SessionOptions()` constructs with all fields set to `None`
- [ ] `SessionOptions.defaults` equals `SessionOptions()` with all None fields
- [ ] Every `with*` builder method sets only its targeted field and leaves all others unchanged
- [ ] `SessionOptions` has no `prompt` field (enforced at compile time)
- [ ] `buildSessionArgs` always produces `--print`, `--input-format stream-json`, `--output-format stream-json` as the first five tokens
- [ ] `buildSessionArgs` with default options produces exactly those five tokens and nothing else
- [ ] `maxTurns`, `model`, `allowedTools`, `disallowedTools`, `systemPrompt`, `appendSystemPrompt`, `resume`, `maxThinkingTokens` each map to the correct CLI flag
- [ ] `continueConversation = Some(true)` maps to `--continue`; `Some(false)` produces no flag
- [ ] `permissionMode` maps to `--permission-mode` for all three enum values (`default`, `acceptEdits`, `bypassPermissions`)
- [ ] Multiple options combine correctly without interfering with the required flags
- [ ] All existing `CLIArgumentBuilderTest` tests continue to pass (no regression in `buildArgs`)

## Entry Points

| File | Method/Class | Why Start Here |
|------|--------------|----------------|
| `core/src/works/iterative/claude/core/model/SessionOptions.scala` | `SessionOptions` | The new domain type — review field list and builder methods here |
| `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala` | `buildSessionArgs()` | New method added to existing builder — review the streaming flags prepend and option mapping |
| `core/test/src/works/iterative/claude/core/cli/SessionOptionsArgsTest.scala` | `SessionOptionsArgsTest` | 16 tests covering every CLI flag mapping — the most complete verification of the contract |
| `core/test/src/works/iterative/claude/core/model/SessionOptionsTest.scala` | `SessionOptionsTest` | 3 tests covering construction, defaults, and all 18 builder methods |

## Diagrams

### Data flow: SessionOptions to CLI args

```
Caller
  │
  ▼
SessionOptions (case class, all fields Option[_])
  │  withModel(), withSystemPrompt(), withPermissionMode(), ...
  │
  ▼
CLIArgumentBuilder.buildSessionArgs(options)
  │
  ├─ Always prepend: ["--print", "--input-format", "stream-json",
  │                   "--output-format", "stream-json"]
  │
  └─ Append per-field args (same mapping as buildArgs):
       --max-turns, --model, --allowedTools, --disallowedTools,
       --system-prompt, --append-system-prompt, --continue, --resume,
       --permission-mode, --max-thinking-tokens
       (no trailing prompt argument)
  │
  ▼
List[String]  ──►  passed to process builder in Phase 3
```

### Relationship to existing types

```
QueryOptions (existing)            SessionOptions (new, Phase 2)
  prompt: String          ──X──    (omitted — sent per-turn)
  cwd, model, ...         ──=──    cwd, model, ...  (identical fields)
       │                                │
       ▼                                ▼
CLIArgumentBuilder                CLIArgumentBuilder
  .buildArgs()                      .buildSessionArgs()
  (no prefix flags)                 (prepends --print + stream-json flags)
  (appends prompt arg)              (no trailing prompt)
```

### Phase dependency map

```
Phase 01: Stdin message format   Phase 02: SessionOptions (this phase)
         │                                        │
         └──────────────┬─────────────────────────┘
                        ▼
              Phase 03: Process management / session lifecycle
                        │
              Phase 04: Multi-turn sequencing
                        │
              Phase 05: Effectful API
```

## Test Summary

| Test File | Type | Count | Tests |
|-----------|------|-------|-------|
| `SessionOptionsTest` | Unit | 3 | Default construction (all None), `defaults` factory equals `SessionOptions()`, all 18 `with*` builders set only their field |
| `SessionOptionsArgsTest` | Unit | 16 | Required flags present and first, no trailing prompt, `maxTurns`, `model`, `allowedTools`, `disallowedTools`, `systemPrompt`, `appendSystemPrompt`, `continueConversation` (true/false), `resume`, all three `permissionMode` values, `maxThinkingTokens`, None-only options, multi-option combination |
| Integration | — | 0 | None required; pure data transformation with no I/O. Phase 3 will add integration tests that exercise the full process launch path using `SessionOptions`. |
| E2E | — | 0 | None required for this phase. Phase 3 carries E2E coverage. |

**Note on test count vs. spec:** The phase context specifies up to 18 builder tests, but the implementation consolidates all builder assertions into a single test (`each with* builder sets only its field`). This covers all 18 builders without the overhead of 18 separate test cases; the trade-off is that a failure message is slightly less precise about which builder failed.

## Files Changed

| File | Change | Summary |
|------|--------|---------|
| `core/src/works/iterative/claude/core/model/SessionOptions.scala` | New | 18-field case class with fluent builders and `defaults` companion factory |
| `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala` | Modified | Added `buildSessionArgs(options: SessionOptions)` method; updated import to include `SessionOptions` |
| `core/test/src/works/iterative/claude/core/model/SessionOptionsTest.scala` | New | 3 unit tests for construction, defaults, and all builder methods |
| `core/test/src/works/iterative/claude/core/cli/SessionOptionsArgsTest.scala` | New | 16 unit tests for `buildSessionArgs` flag mapping |

<details>
<summary>Notable implementation detail: duplication in CLIArgumentBuilder</summary>

`buildSessionArgs` duplicates the per-option argument-building logic from `buildArgs` rather than extracting a shared helper. The phase context explicitly approves this approach: `QueryOptions` and `SessionOptions` are unrelated case classes, extracting a structural-type helper would add complexity, and a third call site does not yet exist. The duplication is mechanical and the field names are identical, making the two methods easy to compare side by side.

</details>

<details>
<summary>Fields not translated to CLI flags</summary>

The following fields are included in `SessionOptions` for use by the Phase 3 session runner but are intentionally not emitted as CLI arguments: `timeout`, `inheritEnvironment`, `environmentVariables`, `executable`, `executableArgs`, `pathToClaudeCodeExecutable`. `mcpTools` is carried but also not emitted — no corresponding CLI flag was confirmed during analysis.

</details>
