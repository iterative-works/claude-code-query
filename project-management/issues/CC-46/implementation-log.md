# Implementation Log: Add Option-C tool-isolation knobs

Issue: CC-46

This log tracks the evolution of implementation across phases.

---

## Phase 1: Option-C isolation knobs (domain + CLI + tests) (2026-04-21)

**Layer:** Domain + CLI translation + tests (merged per analysis — phase-size floor)

**What was built:**
- `core/src/works/iterative/claude/core/model/PermissionMode.scala` — added `DontAsk` enum case with Scaladoc.
- `core/src/works/iterative/claude/core/model/QueryOptions.scala` — added three fields (`strictMcpConfig: Option[Boolean]`, `mcpConfigPath: Option[String]`, `settingSources: List[String]`) with Scaladoc, three fluent helpers, and exhaustive update to `QueryOptions.simple` factory. Updated `permissionMode` Scaladoc to list `DontAsk`.
- `core/src/works/iterative/claude/core/model/SessionOptions.scala` — symmetric three fields, Scaladoc, fluent helpers.
- `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala` — added `DontAsk` branch to `permissionModeArgs` match in both `buildArgs` and `buildSessionArgs`; added `strictMcpConfigArgs`, `mcpConfigPathArgs`, `settingSourcesArgs` val blocks tail-appended in that order after `maxThinkingTokensArgs` in both builders. Empty-branch values use `List.empty` to match file convention.

**Dependencies on other layers:**
- None (phase 1 of 1). `direct` and `effectful` modules automatically pick up new fields via existing `type` / `val` re-exports — verified by `./mill direct.compile` and `./mill effectful.compile` succeeding with no source edits.

**Testing:**
- Unit tests: 19 added (9 in `CLIArgumentBuilderTest`, 10 in `SessionOptionsArgsTest` including the `requiredFlags` regression). Covers presence, absence, `Some(false)` non-emission, CSV join, terminator placement, and deterministic argv ordering.
- Integration tests: none needed (pure `List[String]` translation, no effects).

**Decisions made:**
- `settingSources` stays `List[String]` (not a typed enum) per the issue's explicit requirement.
- `strictMcpConfig: Option[Boolean]` mirrors existing `continueConversation` tri-state pattern; `Some(false)` and `None` both emit nothing (no `--no-strict-mcp-config` flag exists in the CLI).
- `mcpConfigPath` emits `List("--mcp-config", path, "--")` because the CLI flag is variadic — the `"--"` terminator prevents it from consuming downstream argv tokens.
- Argv order is locked: `permissionModeArgs` → `strictMcpConfigArgs` → `mcpConfigPathArgs` → `settingSourcesArgs`, tail-appended in both builders.
- `PermissionMode.DontAsk` gets per-case Scaladoc; the other three cases remain undocumented per the phase context's scope.
- Code review warnings about `Option[Boolean]` tri-state, `buildArgs`/`buildSessionArgs` duplication, missing validation on `settingSources`/`mcpConfigPath`, and other structural concerns were flagged out of scope per the analysis's resolved decisions.

**Code review:**
- Iterations: 2 (iteration 1 surfaced 2 critical testing findings; iteration 2 confirmed all resolved, 0 critical remaining).
- Iteration 2 testing reviewer suggested re-adding `!args.contains("--")` to `mcpConfigPath` absence tests — ignored because it contradicts iteration 1's valid reasoning (`"--"` is a POSIX sentinel that could legitimately appear for other flags).
- Review file: `review-phase-01-20260421-221853.md`

**For next phases:**
- No next phases — this is the only phase for CC-46.
- Consumers (PROC-401 and future tickets) can now set all four isolation knobs via `withStrictMcpConfig`, `withMcpConfigPath`, `withSettingSources`, and `withPermissionMode(PermissionMode.DontAsk)` on either `QueryOptions` or `SessionOptions`.

**Files changed:**
```
M core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala
M core/src/works/iterative/claude/core/model/PermissionMode.scala
M core/src/works/iterative/claude/core/model/QueryOptions.scala
M core/src/works/iterative/claude/core/model/SessionOptions.scala
M core/test/src/works/iterative/claude/core/cli/CLIArgumentBuilderTest.scala
M core/test/src/works/iterative/claude/core/cli/SessionOptionsArgsTest.scala
```

---
