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
- `ClaudeProjects` — pure object in `core` exposing parameterized functions:
  - `baseDir(configDirOverride: Option[os.Path], home: os.Path): os.Path` — returns `configDirOverride.getOrElse(home / ".claude") / "projects"`.
  - `projectDirFor(cwd: os.Path, configDirOverride: Option[os.Path], home: os.Path): os.Path` — composes `baseDir` with `ProjectPathEncoder.encode(cwd)`.
  - Each module's index resolves `CLAUDE_CONFIG_DIR` and `os.home` at its own boundary and passes them in.

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

## Resolved Decisions

### Decision: `ClaudeProjects` lives in `core`, env value passed from the edge (Option C)

Both `ProjectPathEncoder` and `ClaudeProjects` live in `core`. `ClaudeProjects` is fully pure — its API takes the `CLAUDE_CONFIG_DIR` value and home dir as parameters:

```scala
object ClaudeProjects:
  def baseDir(configDirOverride: Option[os.Path], home: os.Path): os.Path =
    configDirOverride.getOrElse(home / ".claude") / "projects"

  def projectDirFor(cwd: os.Path, configDirOverride: Option[os.Path], home: os.Path): os.Path =
    baseDir(configDirOverride, home) / ProjectPathEncoder.encode(cwd)
```

The env read happens at each module's boundary:
- `direct` reads `sys.env.get("CLAUDE_CONFIG_DIR").map(os.Path(_))` and `os.home` eagerly when the convenience method is called.
- `effectful` wraps the same reads in `IO` (or `Sync[F].delay`) so effects stay suspended.

Trade-off: the no-arg ergonomics are slightly clumsier in `core`, but both modules expose ergonomic no-arg conveniences (`listSessionsFor(cwd)`) so end users never see the parameterized form.

### Decision: `CLAUDE_CONFIG_DIR` is the config root

When set, projects directory is `${CLAUDE_CONFIG_DIR}/projects` (i.e. the env var replaces `~/.claude`, not `~/.claude/projects`). Matches the issue's real-world example (`CLAUDE_CONFIG_DIR=~/.claude-iw` → sessions at `~/.claude-iw/projects/…`).

Edge behavior:
- Empty string → treat as unset (fall back to `~/.claude/projects`).
- No shell `~` expansion in the env value (the JVM won't expand it; document this as a known limitation or resolve via `os.Path` which requires absolute paths anyway).

### Decision: Encoding is `/` → `-` character replacement, no validation gymnastics

`ProjectPathEncoder.encode(cwd: os.Path): String = cwd.toString.replace('/', '-')`.

By taking `os.Path` (always absolute, normalized) we get the edge cases for free:
- No trailing slash (os.Path normalizes).
- Always absolute (os.Path enforces).
- Paths containing literal `-` encode unambiguously (the ambiguity is only in the decode direction; `ProjectPathDecoder` already documents it).
- Windows: out of scope — this SDK targets Unix layouts anyway.

Pin current behavior with tests; no runtime validation needed.

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
- None — all CLARIFYs resolved.

### Layer Dependencies
- `core` encoder + `ClaudeProjects` must land before the `direct`/`effectful` conveniences.
- `direct` and `effectful` conveniences are independent of each other and can be parallelized.

### External Blockers
- None.

## Risks & Mitigations

### Risk 1: `CLAUDE_CONFIG_DIR` semantics differ from CLI in practice
**Likelihood:** Low
**Impact:** High
**Mitigation:** Decided: env var replaces `~/.claude` (projects dir is `$CLAUDE_CONFIG_DIR/projects`). Add an integration test using a tmp dir as `CLAUDE_CONFIG_DIR` that mirrors real sessions. If downstream reports mismatch, adjust.

### Risk 2: Breaking source compatibility on the existing index API
**Likelihood:** Low
**Impact:** Medium
**Mitigation:** Add new methods only; do not rename, remove, or change signatures of existing ones. Existing tests act as the compat gate.

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
