# Implementation Log: Resolve Claude projects directory inside the lib (honor CLAUDE_CONFIG_DIR)

Issue: CC-34

This log tracks the evolution of implementation across phases.

---

## Phase 1: Lib-owned project dir resolution (2026-04-08)

**Layer:** Core + Direct + Effectful (single-phase issue)

**What was built:**
- `core/src/works/iterative/claude/core/log/ProjectPathEncoder.scala` — pure `encode(path)` that mirrors the CLI's `cwd`→project-dir naming convention (`/` → `-`).
- `core/src/works/iterative/claude/core/log/ClaudeProjects.scala` — pure resolver: `resolveConfigDir(env)`, `baseDir(override, home)`, `projectDirFor(cwd, override, home)`. No env/IO access inside `core`.
- `direct/src/works/iterative/claude/direct/log/DirectConversationLogIndex.scala` — private constructor with injected `(configDirOverride, home)`; two factory overloads: `apply()` (eager env read, production) and `apply(override, home)` (test seam); new `listSessionsFor(cwd)` / `forSessionAt(cwd, sessionId)`.
- `effectful/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndex.scala` — private constructor; **breaking change:** `apply(): IO[EffectfulConversationLogIndex]` now reads env + `os.home` inside `IO` (captured once per instance); `make(override, home)` synchronous test seam; new `listSessionsFor(cwd)` / `forSessionAt(cwd, sessionId)` returning `IO[...]`.
- `ARCHITECTURE.md` — added entries for the two new core utilities.
- `README.md` — updated example to use the IO-returning factory.

**Dependencies on other layers:** None (first and only phase).

**Design decisions resolved during phase:**
- `os.home` (typed, cross-platform) over `sys.env("HOME")`.
- Constructor injection via private constructor + alternate factory — no method-signature pollution.
- Effectful env read deferred inside `IO`, captured once per instance.
- `CLAUDE_CONFIG_DIR=""` treated as unset via `.filter(_.nonEmpty)` — logic centralized in `ClaudeProjects.resolveConfigDir` (iteration 3).

**Testing:**
- Unit tests: 17 new (3 `ProjectPathEncoderTest` + 4 + 3 `ClaudeProjectsTest` including `resolveConfigDir` cases + 5 `DirectConversationLogIndexCwdTest` + 5 `EffectfulConversationLogIndexCwdTest`).
- Integration tests: +2 (effectful cwd IO tests use temp-dir fixtures; counted under mill `__.itest`).
- Full suite: 397/397 unit, 550/550 integration, zero compile warnings.

**Migration — breaking `EffectfulConversationLogIndex()` factory:**
- `effectful/test/.../EffectfulConversationLogIndexTest.scala` — switched to `.make(None, os.home)`.
- `effectful/test/.../EffectfulPackageReexportTest.scala` — switched to `for index <- EffectfulConversationLogIndex()` (IO binding); also fixed two resource-leak-on-failure bugs by moving `os.remove.all(tmpDir)` into `.guarantee` blocks.
- `README.md` — updated documentation example.

**Code review:**
- Iterations: 3/3 (stopped at warnings only; zero critical issues after iteration 1)
- Iteration 1: flagged missing empty-string test coverage, `override_` naming, Java-style null assertions, redundant cleanup, fixture duplication of `projectDirFor`. All fixed.
- Iteration 2: flagged `resolveConfigDir` duplication between Direct/Effectful companions, misplaced unit tests in cwd files, `ProjectPathEncoder.encode` param named `cwd` (too specific), second reader test leaking tmpDir on failure. All fixed.
- Iteration 3: flagged Scaladoc rationale sentence (CLAUDE.md rule violation), vague `@param cwd` on `forSessionAt`, `os.makeDir.all` before `try` block. All fixed inline.

**For future work:**
- `listSessionsFor`/`forSessionAt` are currently concrete methods on the two implementations only. Reviewers recommended promoting them to the `ConversationLogIndex[F]` trait (with default implementations delegating to `listSessions`/`forSession` via `ClaudeProjects.projectDirFor`) so callers can use the cwd API through the port. Deferred — not in this phase's scope.
- Test-seam naming asymmetry: `DirectConversationLogIndex` uses overloaded `apply(...)`, `EffectfulConversationLogIndex` uses `make(...)` because `apply(): IO[...]` is already taken. Intentional.

**Files changed:**
```
M	ARCHITECTURE.md
M	README.md
A	core/src/works/iterative/claude/core/log/ClaudeProjects.scala
A	core/src/works/iterative/claude/core/log/ProjectPathEncoder.scala
A	core/test/src/works/iterative/claude/core/log/ClaudeProjectsTest.scala
A	core/test/src/works/iterative/claude/core/log/ProjectPathEncoderTest.scala
M	direct/src/works/iterative/claude/direct/log/DirectConversationLogIndex.scala
A	direct/test/src/works/iterative/claude/direct/log/DirectConversationLogIndexCwdTest.scala
M	effectful/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndex.scala
M	effectful/test/src/works/iterative/claude/effectful/EffectfulPackageReexportTest.scala
A	effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndexCwdTest.scala
M	effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndexTest.scala
```

---
