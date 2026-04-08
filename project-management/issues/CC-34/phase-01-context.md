# Phase 1: Lib-owned project dir resolution

**Issue:** CC-34
**Phase:** 1 of 1
**Estimate:** 2.5–5 hours

## Goals

Move the Claude Code on-disk project-directory convention inside the SDK so consumers no longer duplicate base-dir resolution (`CLAUDE_CONFIG_DIR` override) and cwd-to-project-dir encoding. Expose cwd-based convenience methods on both the direct and effectful index APIs while leaving the existing path-based API untouched.

## Scope

In scope:
- New `core` pure utilities: `ProjectPathEncoder`, `ClaudeProjects`.
- New cwd-based convenience methods on `DirectConversationLogIndex` and `EffectfulConversationLogIndex`.
- Unit tests for the pure layer; integration-style tests for the index conveniences using tmp dirs as `CLAUDE_CONFIG_DIR`.

Out of scope:
- Renaming, deprecating, or changing existing path-based methods.
- Windows path handling.
- Shell `~` expansion inside `CLAUDE_CONFIG_DIR` values.
- Any CLI / HTTP / UI surface.

## Dependencies

- Existing `core/log/ProjectPathDecoder` (symmetric partner — informs naming and test style).
- Existing `DirectConversationLogIndex` / `EffectfulConversationLogIndex` path-based methods (delegation target).
- `os-lib` (already in use), `cats-effect` (already available in `effectful`).

No prior phase outputs — this is the only phase.

## Approach

1. **Domain (core) — pure encoder + resolver**
   - `works.iterative.claude.core.log.ProjectPathEncoder` (mirror the package of `ProjectPathDecoder`):
     ```scala
     object ProjectPathEncoder:
       def encode(cwd: os.Path): String = cwd.toString.replace('/', '-')
     ```
   - `works.iterative.claude.core.ClaudeProjects` (or alongside the encoder in `core.log` — pick the package the decoder lives in for symmetry):
     ```scala
     object ClaudeProjects:
       def baseDir(configDirOverride: Option[os.Path], home: os.Path): os.Path =
         configDirOverride.getOrElse(home / ".claude") / "projects"

       def projectDirFor(cwd: os.Path, configDirOverride: Option[os.Path], home: os.Path): os.Path =
         baseDir(configDirOverride, home) / ProjectPathEncoder.encode(cwd)
     ```
   - Both are pure and take env/home as parameters — no `sys.env` read inside `core`.

2. **Application (direct) — cwd conveniences with constructor injection**
   - Make the primary constructor private and accept injected config:
     ```scala
     class DirectConversationLogIndex private (
         configDirOverride: Option[os.Path],
         home: os.Path
     ) extends ConversationLogIndex[[A] =>> A]
     ```
   - Factory methods:
     - `apply(): DirectConversationLogIndex` — production entry point. Reads `sys.env.get("CLAUDE_CONFIG_DIR").filter(_.nonEmpty).map(os.Path(_))` eagerly, uses `os.home` for the home dir. Returns an instance. Signature preserved from current code — only the body changes.
     - `apply(configDirOverride: Option[os.Path], home: os.Path): DirectConversationLogIndex` — test seam.
   - Add instance methods:
     - `listSessionsFor(cwd: os.Path): Seq[LogFileMetadata]` — delegates to `listSessions(ClaudeProjects.projectDirFor(cwd, configDirOverride, home))`.
     - `forSessionAt(cwd: os.Path, sessionId: String): Option[LogFileMetadata]` — analogous.
   - Existing path-based methods (`listSessions`, `forSession`) unchanged.

3. **Application (effectful) — IO factory + pure test seam**
   - Make the primary constructor private and accept injected config:
     ```scala
     class EffectfulConversationLogIndex private (
         configDirOverride: Option[os.Path],
         home: os.Path
     ) extends ConversationLogIndex[IO]
     ```
   - Factory methods:
     - `apply(): IO[EffectfulConversationLogIndex]` — **BREAKING CHANGE** from current `apply(): EffectfulConversationLogIndex`. Reads env + `os.home` inside `IO` so effects stay suspended; env is captured once per instance, not per call. Approved: Michal (2026-04-08).
     - `make(configDirOverride: Option[os.Path], home: os.Path): EffectfulConversationLogIndex` — synchronous test seam, no `IO` wrapping needed by tests.
   - Add instance methods:
     - `listSessionsFor(cwd: os.Path): IO[Seq[LogFileMetadata]]` — delegates to `listSessions(ClaudeProjects.projectDirFor(cwd, configDirOverride, home))`.
     - `forSessionAt(cwd: os.Path, sessionId: String): IO[Option[LogFileMetadata]]` — analogous.
   - Existing path-based methods (`listSessions`, `forSession`) unchanged.
   - **Migration note:** any existing caller using `EffectfulConversationLogIndex()` synchronously must switch to the IO-returning factory. Grep the repo during implementation and update call sites.

### Design decisions (resolved)

- **Home dir abstraction:** use `os.home` (typed `os.Path`, cross-platform) rather than `sys.env("HOME")`. Michal's call — better abstraction wins over matching the `CLIDiscovery` convention.
- **Test seam:** constructor injection via private constructor + alternate factory. No method-signature pollution; production call path untouched.
- **Effectful env read timing:** captured once inside `IO` at construction via the `apply()` factory. Honors analysis.md:61 (no eager env read at call site) without re-reading env per method call.
- **`CLAUDE_CONFIG_DIR` empty string:** treated as unset (`.filter(_.nonEmpty)` before `os.Path`).

4. **Docs touch-up**
   - Scaladoc on `ProjectPathEncoder` and `ClaudeProjects` describing the encoding convention and `CLAUDE_CONFIG_DIR` semantics (env var replaces `~/.claude`, so projects dir is `$CLAUDE_CONFIG_DIR/projects`; empty treated as unset; no `~` expansion).
   - Update `ARCHITECTURE.md` if it enumerates core log utilities.

## Files to Modify / Create

Create:
- `core/src/works/iterative/claude/core/log/ProjectPathEncoder.scala` (or matching package of existing decoder)
- `core/src/works/iterative/claude/core/ClaudeProjects.scala` (or colocated with encoder)
- `core/test/.../ProjectPathEncoderTest.scala`
- `core/test/.../ClaudeProjectsTest.scala`
- `direct/test/.../DirectConversationLogIndexCwdTest.scala` (or extend the existing test file)
- `effectful/test/.../EffectfulConversationLogIndexCwdTest.scala` (or extend existing)

Modify:
- `direct/src/.../DirectConversationLogIndex.scala` — add two new methods.
- `effectful/src/.../EffectfulConversationLogIndex.scala` — add two new IO methods.
- `ARCHITECTURE.md` — only if it lists core log utilities.

Do not modify:
- Existing path-based method signatures or bodies.
- `ProjectPathDecoder`.

## Testing Strategy

TDD per component. Write failing tests first.

**`ProjectPathEncoder` (unit):**
- `/home/mph/ops/kanon` → `-home-mph-ops-kanon`
- `/` → `-`
- Path already containing `-` encodes verbatim (pin behavior).

**`ClaudeProjects` (unit, pure — inject env/home):**
- `configDirOverride = None`, `home = /tmp/fakeHome` → `baseDir = /tmp/fakeHome/.claude/projects`.
- `configDirOverride = Some(/tmp/custom)` → `baseDir = /tmp/custom/projects`.
- `projectDirFor(cwd=/a/b, None, /tmp/fakeHome)` → `/tmp/fakeHome/.claude/projects/-a-b`.

**`DirectConversationLogIndex` (integration, tmp dir):**
- Construct via the test-seam factory: `DirectConversationLogIndex(Some(tmpDir), tmpHome)`.
- Lay out `$tmpDir/projects/-a-b/<session>.jsonl` with a real fixture.
- Call `listSessionsFor(os.Path("/a/b"))` and assert it returns the same metadata as `listSessions($tmpDir/projects/-a-b)`.
- Same for `forSessionAt`.
- Test that `configDirOverride = None` falls back to `home / ".claude" / "projects"`.
- Production `apply()` — smoke test that it constructs without throwing; do not assert on real `$HOME` contents.

**`EffectfulConversationLogIndex` (integration):**
- Construct via synchronous test seam: `EffectfulConversationLogIndex.make(Some(tmpDir), tmpHome)`.
- Analogous fixture setup; assert IO-returning methods produce expected metadata.
- Production `apply(): IO[...]` — smoke test that the IO can be run and produces an instance.

**Regression gate:**
- `ProjectPathDecoderTest`, existing `DirectConversationLogIndexTest`, `EffectfulConversationLogIndexTest` must remain green untouched.
- `./mill __.compile` must produce zero warnings.
- `./mill __.test` must pass.

## Acceptance Criteria

- [ ] `ProjectPathEncoder` exists in `core` with tests covering the three encoding cases.
- [ ] `ClaudeProjects` exists in `core`, fully pure, with tests for unset / set / empty-string override and `projectDirFor` composition.
- [ ] `DirectConversationLogIndex.listSessionsFor(cwd)` and `forSessionAt(cwd, id)` exist, delegate to existing path-based methods, honor `CLAUDE_CONFIG_DIR`.
- [ ] `DirectConversationLogIndex` has a private constructor with `(configDirOverride, home)`, a no-arg production `apply()` reading env + `os.home`, and a test-seam `apply(configDirOverride, home)`.
- [ ] `EffectfulConversationLogIndex.listSessionsFor(cwd)` and `forSessionAt(cwd, id)` exist as IO-returning methods.
- [ ] `EffectfulConversationLogIndex.apply(): IO[EffectfulConversationLogIndex]` reads env + `os.home` inside `IO`, captured once per instance. Existing synchronous `apply()` is removed (breaking change, approved).
- [ ] `EffectfulConversationLogIndex.make(configDirOverride, home)` exists as a synchronous test seam.
- [ ] All new public symbols carry Scaladoc covering encoding convention and `CLAUDE_CONFIG_DIR` semantics (env var replaces `~/.claude`; empty = unset; no `~` expansion).
- [ ] Existing path-based instance methods (`listSessions`, `forSession`) are byte-identical.
- [ ] All in-repo call sites of the old `EffectfulConversationLogIndex()` factory are updated to the IO-returning version.
- [ ] `./mill __.compile` has zero warnings; `./mill __.test` and `./mill __.itest` pass.
- [ ] No new runtime dependencies introduced.
