# Technical Analysis: Add Option-C tool-isolation knobs (strict MCP config, mcp-config path, setting sources, DontAsk permission mode)

**Issue:** CC-46 (GitHub: [iterative-works/claude-code-query#46](https://github.com/iterative-works/claude-code-query/issues/46))
**Created:** 2026-04-21
**Status:** Draft

## Problem Statement

Consumer `iterative-works/procedures` (issue PROC-401, ADR 0001 Step 2) needs to spawn event-processor subprocesses with a **hard** tool allow-list that cannot be widened by inherited `.mcp.json` files, user-level settings, or interactive permission prompts.

Today, `QueryOptions` / `SessionOptions` expose `allowedTools` and `permissionMode` but cannot express the full Claude Code CLI "Option C" isolation story. Without the four CLI flags below, an isolated subprocess can either (a) silently inherit the user's `.mcp.json` / `~/.claude/settings.json`, or (b) hang on an interactive permission prompt for a tool not in `allowedTools`:

| CLI flag                     | Purpose                                                                |
| ---------------------------- | ---------------------------------------------------------------------- |
| `--strict-mcp-config`        | Prevent merging of any `.mcp.json` other than the one we pass.         |
| `--mcp-config <path>`        | Explicitly point at a specific `.mcp.json` (don't rely on cwd lookup). |
| `--setting-sources <csv>`    | Restrict settings resolution (e.g., `project` excludes user/managed).  |
| `--permission-mode dontAsk`  | Enforce `allowedTools` as a hard allow-list (no prompts, no hangs).    |

This is a pure additive feature: the SDK must expose these four knobs through the existing `QueryOptions` / `SessionOptions` / `PermissionMode` / `CLIArgumentBuilder` surface with zero behavioural change for callers that don't set them.

## Proposed Solution

### High-Level Approach

A thin, three-layer extension entirely inside the `core` module:

1. Extend the **domain model** (`PermissionMode`, `QueryOptions`, `SessionOptions`) with one new enum case and three new optional fields plus matching fluent `with*` helpers, all defaulting to the current no-op behaviour.
2. Extend the **CLI translation layer** (`CLIArgumentBuilder.buildArgs` and `buildSessionArgs`) with one new match branch for `PermissionMode.DontAsk` and three new translations for the new fields. Defaults emit no flags, preserving backwards compatibility.
3. Extend the **test layer** (`CLIArgumentBuilderTest`, `SessionOptionsArgsTest`) with per-field "emitted when set, absent when default" coverage, the new `DontAsk` branch, and a smoke round-trip test asserting all four flags appear together in deterministic order.

### Why This Approach

- The existing code already models every other CLI flag the same way (`Option[T]` field + fluent helper + `match` branch emitting `List[String]`), so mirroring the pattern keeps the diff small, obvious, and review-friendly.
- No behavioural change to existing flags and no deprecation of existing `PermissionMode` cases — aligns with the issue's non-goals.
- Adding case-class fields with defaults and a new enum case propagates through `direct`/`effectful` automatically because both modules re-export via `type`/`val` aliases (`direct/src/.../package.scala`, `effectful/src/.../package.scala`) — no source changes needed there.

## Architecture Design

**Purpose:** Define WHAT components each layer needs. Scope is the single `core` module; `direct` and `effectful` pick up the additions transparently via re-export.

### Domain / Model Layer

**Components:**
- `core/src/works/iterative/claude/core/model/PermissionMode.scala` — add enum case `DontAsk`.
- `core/src/works/iterative/claude/core/model/QueryOptions.scala` — add three fields (`strictMcpConfig: Option[Boolean] = None`, `mcpConfigPath: Option[String] = None`, `settingSources: List[String] = Nil`) with matching `withStrictMcpConfig(flag: Boolean)` (wraps in `Some`), `withMcpConfigPath(path: String)`, `withSettingSources(sources: List[String])` helpers. Update the `QueryOptions.simple(prompt)` factory to explicitly pass the new defaults so the factory remains exhaustive in named arguments.
- `core/src/works/iterative/claude/core/model/SessionOptions.scala` — symmetric three fields + three fluent helpers. `SessionOptions.defaults` remains `SessionOptions()` and needs no edit because the new fields have defaults.

**Responsibilities:**
- Model the four CLI knobs as first-class configuration.
- Preserve full source/binary compatibility for callers that don't touch the new fields (defaults match today's behaviour: no flag emitted).

**Estimated Effort:** 0.75-1.25 hours
**Complexity:** Straightforward

---

### CLI Translation Layer

**Components:**
- `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala` — two edits, one per public method:
  - `buildArgs(QueryOptions)`:
    - Extend the `permissionMode` match with `case Some(PermissionMode.DontAsk) => List("--permission-mode", "dontAsk")`.
    - Add `strictMcpConfigArgs`: `options.strictMcpConfig match { case Some(true) => List("--strict-mcp-config"); case _ => Nil }` (covers `None` and `Some(false)` — no `--no-strict-mcp-config` flag exists today).
    - Add `mcpConfigPathArgs`: `options.mcpConfigPath match { case Some(path) => List("--mcp-config", path, "--"); case None => Nil }`. The trailing `"--"` is mandatory — `--mcp-config` is variadic and would otherwise consume downstream argv tokens (prompt, caller-appended flags) as additional config paths.
    - Add `settingSourcesArgs`: `if options.settingSources.nonEmpty then List("--setting-sources", options.settingSources.mkString(",")) else Nil`.
    - Append the new arg groups to the final `List(...).flatten` in deterministic order.
  - `buildSessionArgs(SessionOptions)`: same four additions, same ordering.

**Responsibilities:**
- Translate each new field into its CLI flag when set, emit nothing when default.
- Maintain a single, deterministic argv order (see Technical Decisions below).
- Keep the `DontAsk` branch exhaustive across both builder methods (Scala 3 will warn on non-exhaustive match; we must update both sites).

**Estimated Effort:** 0.75-1.25 hours
**Complexity:** Straightforward

---

### Test Layer

**Components:**
- `core/test/src/works/iterative/claude/core/cli/CLIArgumentBuilderTest.scala` — add one test per new field ("emitted when set" + "absent when default"), one test for the `PermissionMode.DontAsk` branch, and the smoke round-trip test.
- `core/test/src/works/iterative/claude/core/cli/SessionOptionsArgsTest.scala` — symmetric coverage for `buildSessionArgs`, including a session-flavoured round-trip test.

**Responsibilities:**
- Lock down presence/absence of each new flag.
- Lock down the deterministic ordering decided in Technical Decisions (so future reorderings surface as test failures, not silent breakage for consumers that grep argv).
- Cover the `DontAsk` serialisation string exactly (`"dontAsk"`, camelCase — matches the CLI docs and mirrors `"acceptEdits"` / `"bypassPermissions"`).

**Estimated Effort:** 1.25-2 hours
**Complexity:** Straightforward

---

## Technical Decisions

### Patterns

- **Mirror existing style.** Each new flag follows the same `Option[T]` / boolean / `List[String]` + `match` / `if` template already used in `CLIArgumentBuilder`. Do not introduce a new abstraction (builder, typeclass, DSL) — YAGNI.
- **Functional core.** All additions are pure: immutable case classes, pure `List[String]` outputs.
- **Defaults preserve behaviour.** `strictMcpConfig: Option[Boolean] = None`, `mcpConfigPath: Option[String] = None`, `settingSources: List[String] = Nil`. Each translates to `List.empty` when defaulted (and `Some(false)` also emits nothing, since no negation flag exists).
- **Enum case naming.** Keep `DontAsk` exactly as specified in the issue (Scala PascalCase). The CLI string is `"dontAsk"` (camelCase), matching the existing pattern `AcceptEdits -> "acceptEdits"` and `BypassPermissions -> "bypassPermissions"`.

### Technology Choices

- **Frameworks/Libraries:** None new. Scala 3 stdlib only.
- **Data Storage:** N/A.
- **External Systems:** N/A (this only prepares argv for the existing Claude Code CLI subprocess).

### Integration Points

- `direct` module (`direct/src/works/iterative/claude/direct/package.scala`): `PermissionMode` is re-exported as `type PermissionMode = ...; val PermissionMode = ...`, so `PermissionMode.DontAsk` is automatically visible to direct callers. No edit required.
- `effectful` module (`effectful/src/works/iterative/claude/effectful/package.scala`): same pattern, same outcome.
- Downstream consumers (PROC-401 specifically) must recompile against the new `0.3.0-SNAPSHOT` to pick up the enum case and fields.

### Argv Ordering (decided)

Append the new arg groups to the existing `List(...).flatten` in the following order, placed **after** `permissionModeArgs` and **after** `maxThinkingTokensArgs` (i.e., at the tail end of the existing sequence). Within the new group, order is:

1. `strictMcpConfigArgs`
2. `mcpConfigPathArgs`
3. `settingSourcesArgs`

The `DontAsk` branch slots into the existing `permissionModeArgs` position (no positional change to that group).

**Critical: `--mcp-config` is variadic — it greedily consumes subsequent tokens as additional config paths until it hits a recognized flag or a `--` terminator. Tail-positioning alone is NOT safe**, because `buildArgs` output is concatenated with downstream pieces (prompt, other CLI fragments) by the process runner, so anything appended by the caller would be swallowed as a config path. Emitting a `--` terminator immediately after the path isolates the variadic regardless of argv position or what follows.

Therefore:

- `mcpConfigPathArgs` emits `List("--mcp-config", path, "--")` when set (path + explicit terminator).
- `mcpConfigPathArgs` emits `Nil` when unset (no flag, no terminator).

So for `buildArgs`, the final `List(...).flatten` becomes:

```
maxTurnsArgs, modelArgs, allowedToolsArgs, disallowedToolsArgs,
systemPromptArgs, appendSystemPromptArgs, continueConversationArgs,
resumeArgs, permissionModeArgs, maxThinkingTokensArgs,
strictMcpConfigArgs, mcpConfigPathArgs, settingSourcesArgs
```

where `mcpConfigPathArgs`, when non-empty, is a three-element list `["--mcp-config", "<path>", "--"]`.

For `buildSessionArgs`, same order appended after `maxThinkingTokensArgs`, with the required streaming flags block remaining first.

Rationale: tail-append keeps the existing `args.take(requiredFlags.length)` invariant in `SessionOptionsArgsTest` intact. Within the new group, the order is semantically grouped (strict flag first, then the path it governs, then orthogonal settings-source restriction). The `--` terminator after `--mcp-config <path>` makes the group position-independent and robust to future additions or caller-appended arguments.

## Resolved Decisions

### Decision: QueryOptions-side test file

The issue mentions `QueryOptionsArgsTest` symmetric to `SessionOptionsArgsTest`, but that file does not exist. `CLIArgumentBuilderTest.scala` is the de-facto QueryOptions args test today.

**Decided:** Option A — add the new QueryOptions-side coverage (seven per-field/branch tests plus the smoke round-trip) to the existing `CLIArgumentBuilderTest.scala`. Smallest diff, consistent with current repo layout. Potential future canonicalization (rename to `QueryOptionsArgsTest.scala`) is out of scope for this issue.

---

### Decision: Argv ordering policy

**Decided:** Tail-append the three new groups in order `strictMcpConfigArgs` → `mcpConfigPathArgs` → `settingSourcesArgs`, after `maxThinkingTokensArgs`. `mcpConfigPathArgs` emits a three-element list `["--mcp-config", "<path>", "--"]` to terminate the variadic `--mcp-config` and prevent downstream argv tokens (prompt, caller-appended flags) from being swallowed as additional config paths. Smoke test uses `containsSlice` for pair/triple adjacency and `indexOf` comparisons for cross-group ordering. See Technical Decisions > Argv Ordering for the full rationale.

---

### Decision: Boolean fluent helper shape

**Decided:** Option A — single `withStrictMcpConfig(flag: Boolean)` on both `QueryOptions` and `SessionOptions`. No zero-arg convenience. Matches existing precedent from `withContinueConversation(continue: Boolean)` and `withInheritEnvironment(inherit: Boolean)` in the same case classes.

---

### Decision: `strictMcpConfig` field type

**Decided:** Option B — `strictMcpConfig: Option[Boolean] = None` on both `QueryOptions` and `SessionOptions`. Consistent with other boolean-ish fields (`continueConversation`, `inheritEnvironment`). Preserves the possibility of distinguishing "unset" from "explicit false" in case a future CLI surface adds a negation semantic; the SDK should not prematurely flatten that.

**Translation:**
- `Some(true)` → `List("--strict-mcp-config")`
- `Some(false)` or `None` → `Nil` (no flag emitted — today the CLI has no `--no-strict-mcp-config`, so both default and explicit-false are argv-identical)

**Fluent helper:** `withStrictMcpConfig(flag: Boolean): QueryOptions = copy(strictMcpConfig = Some(flag))` (and the mirror on `SessionOptions`).

---

## Total Estimates

**Per-Layer Breakdown:**
- Domain / Model Layer: 0.75-1.25 hours
- CLI Translation Layer: 0.75-1.25 hours
- Test Layer: 1.25-2 hours

**Total Range:** 2.75-4.5 hours

**Confidence:** High

**Reasoning:**
- Pure additive mirror of an existing, well-understood pattern — no design discovery required.
- Single-module scope; no cross-module churn thanks to re-export aliases.
- Test surface is small and mechanical.
- Resolved decisions (see "Resolved Decisions" section) are about style/naming, not about unknowns that could blow up effort.

## Recommended Phase Plan

Total estimate is 2.75-4.5h. Low-end is below the 3h floor and the whole change is tightly coupled (the `DontAsk` enum case forces the CLI builder update forces the test update), so **a single phase is the right answer**.

- **Phase 1: Option-C isolation knobs (domain + CLI + tests)**
  - Includes: Domain/Model layer, CLI translation layer, Test layer
  - Estimate: 2.75-4.5 hours
  - Rationale: The three layers are mechanically coupled (adding the enum case without updating the CLI builder's match produces a non-exhaustive-match warning; adding fields without tests violates the project's TDD rule). Merging also keeps the review packet cohesive — a reviewer wants to see the flag, its translation, and its test together. Splitting would multiply ceremony (three PRs, three reviews) for no isolation benefit.

**Total phases:** 1 (for total estimate 2.75-4.5 hours)

## Testing Strategy

### Per-Layer Testing

Unit tests only — this change has no integration or E2E surface (it's pure argv construction).

**Domain / Model Layer:**
- No dedicated tests. Case-class field defaults and fluent helpers are exercised transitively by the CLI translation tests below. (Consistent with how existing fields like `continueConversation` are covered.)

**CLI Translation Layer (`CLIArgumentBuilderTest.scala`):**
Add the following tests:
1. `strictMcpConfig = Some(true) emits --strict-mcp-config` — asserts `args.contains("--strict-mcp-config")`.
2. `strictMcpConfig default (None) does not emit --strict-mcp-config` — asserts `!args.contains("--strict-mcp-config")`.
2a. `strictMcpConfig = Some(false) does not emit --strict-mcp-config` — asserts `!args.contains("--strict-mcp-config")` (documents the "no negation flag" behaviour).
3. `mcpConfigPath maps to --mcp-config <path> --` — asserts the three-element slice `List("--mcp-config", path, "--")` appears (terminator isolates the variadic `--mcp-config` from whatever follows in argv).
4. `mcpConfigPath default (None) does not emit --mcp-config` — asserts absent; also asserts the standalone `"--"` terminator is absent when the flag is unset (so we don't emit a stray terminator).
5. `settingSources non-empty maps to --setting-sources with CSV value` — asserts `args.contains("--setting-sources")` and `args.contains("project,user")` for `List("project", "user")`.
6. `settingSources default (Nil) does not emit --setting-sources` — asserts absent.
7. `PermissionMode.DontAsk maps to --permission-mode dontAsk` — asserts pair appears and string is exactly `"dontAsk"`.
8. **Smoke round-trip test** (verbatim spec):

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

**Session Translation Layer (`SessionOptionsArgsTest.scala`):**
Same seven single-field tests, plus a session-flavoured round-trip using `SessionOptions().withStrictMcpConfig(true).withMcpConfigPath("./.mcp.json").withSettingSources(List("project")).withPermissionMode(PermissionMode.DontAsk)`. Also add: `required session flags still appear at the start of the argument list when all four new flags are set` — reuses the existing pattern (`args.take(requiredFlags.length) == requiredFlags`).

**Test Data Strategy:**
- Inline literal values per test. No fixtures needed.

**Regression Coverage:**
- Existing tests in both test files must continue to pass unchanged.
- Existing argv-ordering assertions (`args.take(requiredFlags.length)` in `SessionOptionsArgsTest`) are preserved because new flags append at the tail.
- The `PermissionMode` match in `CLIArgumentBuilder` is currently exhaustive only on `Default / AcceptEdits / BypassPermissions / None`. Scala 3 will emit a non-exhaustive-match warning when `DontAsk` is added to the enum — this is a **required regression signal**, not a failure: it proves both match sites were updated.

## Deployment Considerations

### Database Changes
None.

### Configuration Changes
None in this SDK. Consumers (PROC-401) will start passing the four new flags.

### Rollout Strategy
- Publish under continuing `0.3.0-SNAPSHOT` on a feature branch (per issue's "Delivery" section).
- PROC-401 bumps Maven coord, exercises the flags against the real event-processor subprocess, and reports back.
- Cut `v0.3.0` tag after PROC-401 validation settles.

### Rollback Plan
- Additive, opt-in feature. Rollback = consumers stop setting the new fields (behaviour reverts to pre-change since defaults emit nothing) or pin to the previous snapshot.

## Dependencies

### Prerequisites
- None. Current codebase (branch `CC-46`) is in clean state.

### Layer Dependencies
- Domain → CLI translation → Tests. Strict forward dependency within the phase.
- `direct` and `effectful` modules have no source dependencies (re-export handles propagation). They will recompile cleanly.

### External Blockers
- None.

## Risks & Mitigations

### Risk 1: Non-exhaustive `PermissionMode` match somewhere other than `CLIArgumentBuilder`
**Likelihood:** Low
**Impact:** Medium (compilation warning → possible CI failure given `-Xfatal-warnings` if enabled)
**Mitigation:** Grep the repo for `PermissionMode` uses before committing. The issue-provided context lists only two match sites (`buildArgs`, `buildSessionArgs`). Verify this during implementation with `sg --lang scala -p 'PermissionMode.$CASE'` / `rg 'case PermissionMode\\.'`.

### Risk 2: Consumer (PROC-401) expects a different argv order than our chosen one
**Likelihood:** Low
**Impact:** Low (the CLI accepts any order; our smoke test just pins one)
**Mitigation:** Ordering is locked by decision (see "Resolved Decisions > Argv ordering policy") and asserted in the smoke test. If PROC-401 surfaces a different expectation during validation, adjust the smoke test and this analysis in follow-up.

### Risk 3: `settingSources: List[String]` vs typed enum drift
**Likelihood:** Low
**Impact:** Low (stringly-typed API is what the issue specifies)
**Mitigation:** Stick with `List[String]` per the issue. A future ticket can introduce a `SettingSource` enum if PROC-401 or another consumer asks for it. Note this in the journal rather than acting now (YAGNI).

---

## Implementation Sequence

**Recommended Layer Order (within the single phase):**

1. **Domain / Model Layer** — Add `PermissionMode.DontAsk`; add three fields + three fluent helpers to `QueryOptions` and `SessionOptions`; update `QueryOptions.simple` named-arg list. Pure, no dependencies.
2. **CLI Translation Layer (TDD pairing with Tests)** — Per project TDD rule: write a failing test for each new field/branch in `CLIArgumentBuilderTest` / `SessionOptionsArgsTest`, then add the corresponding branch/translation in `CLIArgumentBuilder`. Repeat per field. Final step in this layer is the smoke round-trip test.
3. **Cleanup & Verification** — Run `./mill __.compile` to confirm no non-exhaustive-match warnings remain; run `./mill __.test` to confirm green; run `./mill direct.compile` / `./mill effectful.compile` to confirm re-export propagation works without source edits there.

**Ordering Rationale:**
- Domain first because the CLI builder and tests both depend on the new enum case and new fields existing.
- CLI translation and tests are written hand-in-hand (TDD) — conceptually one step.
- Final compile/test sweep catches any exhaustiveness or downstream-module surprises.

## Documentation Requirements

- [x] Code documentation — extend the Scaladoc on `QueryOptions` / `SessionOptions` new fields to describe the CLI flag they map to (match the existing field comment style).
- [x] Code documentation — add a line to `PermissionMode` enum Scaladoc describing `DontAsk` as "Enforce `allowedTools` as a hard allow-list; no prompts, no hangs" (mirror existing per-case comments in `QueryOptions.permissionMode`).
- [ ] API documentation — no external API doc site to update.
- [ ] Architecture decision record — not warranted; this is a mechanical extension of an existing pattern.
- [ ] User-facing documentation — none (no UI).
- [ ] Migration guide — not needed (additive, defaults preserve behaviour).

---

**Analysis Status:** Ready for Review

**Next Steps:**
1. Run **wf-create-tasks** with issue ID `CC-46`.
2. Run **wf-implement** for the single merged phase.
