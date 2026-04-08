# Technical Analysis: Resolve Claude projects directory inside the lib (honor CLAUDE_CONFIG_DIR)

**Issue:** CC-34
**Created:** 2026-04-08
**Status:** Draft

## Problem Statement

`DirectConversationLogIndex` and `EffectfulConversationLogIndex` both require callers to pass a fully-resolved `projectDir: os.Path` pointing at the per-project subdirectory under `~/.claude/projects/`. Every consumer of the SDK that wants to enumerate sessions for "the current working directory" must reimplement two pieces of logic:

1. **Base directory resolution** — defaulting to `~/.claude/projects/` but honoring the `CLAUDE_CONFIG_DIR` environment variable when it is set (Claude Code CLI's documented override).
2. **Project path encoding** — converting an absolute cwd like `/home/mph/ops/kanon` to the dash-encoded directory name `-home-mph-ops-kanon` that Claude Code uses on disk.

Both concerns are part of the Claude Code on-disk contract and belong inside the SDK. Today the codebase already ships `ProjectPathDecoder` (the inverse direction), but no encoder and no base-dir resolver exist, forcing each downstream tool to duplicate the convention.

## Proposed Solution

### High-Level Approach

Introduce a small `ClaudeProjects` value object that owns the on-disk layout knowledge: base directory resolution (with `CLAUDE_CONFIG_DIR` override) and cwd-to-project-dir encoding. Pair the existing `ProjectPathDecoder` with a symmetrical `ProjectPathEncoder` for the pure encoding step, and have `ClaudeProjects` compose the two concerns.

Add cwd-based convenience overloads on both `DirectConversationLogIndex` and `EffectfulConversationLogIndex` that internally call `ClaudeProjects.projectDirFor(cwd)` and then delegate to the existing path-based methods. Keep the existing `listSessions(projectPath)` / `forSession(projectPath, sessionId)` API exactly as-is so consumers that already resolve paths themselves continue to compile and work.

### Why This Approach

- Keeps the on-disk convention in one named place (`ClaudeProjects`) instead of scattering it across consumers.
- Pure encoding logic stays in `core` next to the existing `ProjectPathDecoder`, preserving symmetry.
- Convenience overloads are additive — zero risk to existing call sites.
- The env-var lookup is the only impure bit and can be isolated behind a single function, optionally parameterized for testability.

## Architecture Design

### Domain Layer (core module)

**Components:**
- `ProjectPathEncoder` — pure object with `encode(cwd: os.Path): String` that produces the dash-encoded directory name (e.g. `/home/mph/ops/kanon` → `-home-mph-ops-kanon`). Mirrors `ProjectPathDecoder`.
- `ClaudeProjects` (location TBD — see CLARIFY below) — small value object exposing:
  - `baseDir(): os.Path` — resolves `CLAUDE_CONFIG_DIR` env var if set (joining `/projects` if it points at the config root) else `~/.claude/projects`.
  - `projectDirFor(cwd: os.Path): os.Path` — composes `baseDir()` with `ProjectPathEncoder.encode(cwd)`.
  - Optionally a testable variant that accepts an env lookup function and a home dir, with the public no-arg version delegating to `sys.env.get` and `sys.props("user.home")`.

**Responsibilities:**
- Encode the Claude Code on-disk convention for project directories.
- Honor the documented `CLAUDE_CONFIG_DIR` override.
- Stay free of any I/O beyond reading env vars / system properties.

**Estimated Effort:** 1.5–3 hours
**Complexity:** Straightforward

---

### Application Layer (direct + effectful modules)

**Components:**
- `DirectConversationLogIndex` — two new convenience methods:
  - `listSessionsFor(cwd: os.Path): Seq[LogFileMetadata]`
  - `forSessionAt(cwd: os.Path, sessionId: String): Option[LogFileMetadata]`
- `EffectfulConversationLogIndex` — analogous IO-returning overloads:
  - `listSessionsFor(cwd: os.Path): IO[Seq[LogFileMetadata]]`
  - `forSessionAt(cwd: os.Path, sessionId: String): IO[Option[LogFileMetadata]]`
  - `CLAUDE_CONFIG_DIR` resolution should be wrapped in `IO` here (env reads are effects; the effectful API should not capture them eagerly at call site).

**Responsibilities:**
- Orchestrate `ClaudeProjects.projectDirFor(cwd)` plus the existing path-based listing logic.
- Preserve the existing path-based API verbatim.

**Estimated Effort:** 1–2 hours
**Complexity:** Straightforward

---

### Infrastructure Layer

Not applicable — no new persistence, no new external clients. The only "infrastructure" touch is `sys.env` / `sys.props` lookup, which lives inside `ClaudeProjects` itself.

---

### Presentation Layer

Not applicable — this is a library-internal API change with no CLI, HTTP, or UI surface.

---

## Technical Decisions

### Patterns

- Pure functional core: `ProjectPathEncoder` is a total pure function.
- Effects-at-the-edge: env lookup encapsulated in one place; effectful module wraps it in `IO`.
- Additive API evolution: new methods alongside existing ones; no deprecations.
- Symmetry with existing `ProjectPathDecoder`.

### Technology Choices

- **Frameworks/Libraries**: No new dependencies. `os-lib` already in use; `cats-effect` already available in `effectful`.
- **Data Storage**: None.
- **External Systems**: None — only env var + home dir lookup.

### Integration Points

- `direct/DirectConversationLogIndex` → `core/ClaudeProjects` → `core/ProjectPathEncoder`.
- `effectful/EffectfulConversationLogIndex` → `core/ClaudeProjects` (wrapped in `IO`) → `core/ProjectPathEncoder`.
- `core/ProjectPathDecoder` and `core/ProjectPathEncoder` become a matched pair.

## Technical Risks & Uncertainties

### CLARIFY: Where does `ClaudeProjects` live — `core` or `direct`?

The encoder is unambiguously pure and belongs in `core` next to `ProjectPathDecoder`. `ClaudeProjects.baseDir()` however performs an env lookup, which is a (small) effect.

**Questions to answer:**
1. Is the project's "functional core" rule strict enough that `sys.env.get` disqualifies code from `core`?
2. Do we want the effectful module to compute `baseDir` inside `IO` (which means `ClaudeProjects.baseDir` must not be called eagerly) or is one-shot resolution at construction time acceptable?
3. Should we expose a pure `ClaudeProjects.from(envVar: Option[String], home: os.Path)` constructor and keep the impure `apply()` separate?

**Options:**
- **Option A** — `ProjectPathEncoder` in `core`; `ClaudeProjects` in `core` with both a pure `from(env, home)` constructor and an impure default that reads `sys.env`. Pros: one home for the convention, easy to test. Cons: tiny impurity in `core`.
- **Option B** — `ProjectPathEncoder` in `core`; `ClaudeProjects` duplicated as thin wrappers in `direct` (sync env read) and `effectful` (env read inside `IO`). Pros: keeps `core` 100% pure. Cons: small duplication.
- **Option C** — Everything in `core`, but `baseDir` takes the env value as a parameter (`baseDir(configDirOverride: Option[String], home: os.Path)`), and each module's index resolves the inputs at its own boundary. Pros: maximally pure, single home. Cons: slightly clumsier ergonomics for the most common no-arg call.

**Impact:** Determines module placement, test setup style, and how the effectful module suspends env access.

---

### CLARIFY: Semantics of `CLAUDE_CONFIG_DIR`

Need to confirm against Claude Code CLI's documented behavior: when `CLAUDE_CONFIG_DIR=/foo/bar` is set, is the projects directory `/foo/bar/projects` or `/foo/bar` itself? The CLI source / docs should be the authority — guessing here would create a subtle incompatibility.

**Questions to answer:**
1. Does the CLI append `/projects` to `CLAUDE_CONFIG_DIR`, or is `CLAUDE_CONFIG_DIR` already the projects root?
2. What does the CLI do when the env var is set to an empty string?
3. Does `~` expansion happen inside the env value?

**Impact:** Correctness of the entire feature; getting this wrong silently breaks every consumer.

---

### CLARIFY: Encoding edge cases

The naive `path.toString.replace('/', '-')` works for `/home/mph/ops/kanon` but needs spec confirmation for:

**Questions to answer:**
1. Windows-style paths or drive letters — do we care? (Probably not for this SDK.)
2. Paths containing literal `-` — the existing decoder is already documented as ambiguous; encoder is unambiguous so this is fine, but a test should pin it.
3. Trailing slash on cwd — should it be normalized?
4. Should we validate that `cwd` is absolute and throw / return an error otherwise?

**Impact:** Minor correctness issues; addressable purely with tests.

---

## Total Estimates

**Per-Layer Breakdown:**
- Domain Layer (core encoder + ClaudeProjects): 1.5–3 hours
- Application Layer (direct + effectful conveniences): 1–2 hours
- Infrastructure Layer: 0 hours
- Presentation Layer: 0 hours

**Total Range:** 2.5–5 hours

**Confidence:** High

**Reasoning:**
- Scope is small and well-contained.
- Existing `ProjectPathDecoder` provides a clear template for the encoder.
- Existing index implementations are short and straightforward to extend additively.
- Main uncertainty is the `CLAUDE_CONFIG_DIR` semantics CLARIFY, which is a research question, not an implementation one.

## Testing Strategy

### Per-Layer Testing

**Domain Layer (core):**
- Unit tests for `ProjectPathEncoder.encode`:
  - `/home/mph/ops/kanon` → `-home-mph-ops-kanon`
  - `/` → `-`
  - Path containing `-` round-trips through encoder unambiguously (pin current behavior).
  - Optionally: rejects/normalizes non-absolute paths.
- Unit tests for `ClaudeProjects` using injectable env/home:
  - `CLAUDE_CONFIG_DIR` unset → `~/.claude/projects`.
  - `CLAUDE_CONFIG_DIR` set → resolved per the semantics CLARIFY.
  - `projectDirFor(cwd)` composes base + encoded cwd correctly.

**Application Layer:**
- `DirectConversationLogIndexTest`: integration test with a tmp dir acting as `CLAUDE_CONFIG_DIR`, real `.jsonl` fixtures, asserting `listSessionsFor(cwd)` returns the same metadata as the existing `listSessions(projectPath)` path.
- `EffectfulConversationLogIndexTest`: analogous IO-based test.
- Existing path-based tests must remain green unchanged (source-compatibility regression check).

**Test Data Strategy:**
- Tmp directories created per test, cleaned up after.
- Inject env via the pure constructor in unit tests; use real env override (or test-scoped tmp) only in the index integration tests.

**Regression Coverage:**
- Existing `DirectConversationLogIndexTest` and `EffectfulConversationLogIndexTest` must run unchanged.
- Existing `ProjectPathDecoderTest` must remain green.

## Deployment Considerations

### Database Changes
None.

### Configuration Changes
None inside the SDK. Consumers gain the ability to honor `CLAUDE_CONFIG_DIR` automatically.

### Rollout Strategy
Single library release. Purely additive.

### Rollback Plan
Revert the commit; existing API is untouched so consumers are unaffected.

## Dependencies

### Prerequisites
- Confirm `CLAUDE_CONFIG_DIR` semantics (CLARIFY above) before implementation.

### Layer Dependencies
- `core` encoder + `ClaudeProjects` must land before the `direct`/`effectful` conveniences.
- `direct` and `effectful` conveniences are independent of each other and can be parallelized.

### External Blockers
- None.

## Risks & Mitigations

### Risk 1: Misinterpreting `CLAUDE_CONFIG_DIR` semantics
**Likelihood:** Medium
**Impact:** High
**Mitigation:** Verify against the Claude Code CLI source / docs before coding. Add an integration test that mirrors the CLI's actual layout.

### Risk 2: Breaking source compatibility on the existing index API
**Likelihood:** Low
**Impact:** Medium
**Mitigation:** Add new methods only; do not rename, remove, or change signatures of existing ones. Existing tests act as the compat gate.

### Risk 3: Hidden impurity in `core` if `ClaudeProjects` lands there
**Likelihood:** Low
**Impact:** Low
**Mitigation:** Resolve via the CLARIFY — prefer Option A or C which keep the impure entry point clearly labeled.

---

## Implementation Sequence

**Recommended Layer Order:**

1. **Domain Layer (core)** — Add `ProjectPathEncoder` with tests; add `ClaudeProjects` (or its pure half) with tests. Foundation for everything else.
2. **Application Layer (direct)** — Add `listSessionsFor` / `forSessionAt` to `DirectConversationLogIndex` with tests.
3. **Application Layer (effectful)** — Add the IO-wrapped equivalents to `EffectfulConversationLogIndex` with tests.

**Ordering Rationale:**
- The encoder is a hard prerequisite for both index changes.
- Steps 2 and 3 are independent and can be done in parallel or in either order.
- No presentation/infra work means the sequence collapses to "pure first, then thin wrappers".

## Documentation Requirements

- [x] Inline doc comments on `ProjectPathEncoder` and `ClaudeProjects` describing the encoding convention and `CLAUDE_CONFIG_DIR` behavior.
- [x] Update `ARCHITECTURE.md` if it enumerates core log utilities.
- [ ] No API documentation site to update.
- [ ] No user-facing documentation (library-internal change).
- [ ] No migration guide needed (purely additive).

---

**Analysis Status:** Ready for Review

**Next Steps:**
1. Resolve CLARIFY markers (especially `CLAUDE_CONFIG_DIR` semantics and `ClaudeProjects` placement).
2. Run **wf-create-tasks** with CC-34.
3. Run **wf-implement** for layer-by-layer implementation.
