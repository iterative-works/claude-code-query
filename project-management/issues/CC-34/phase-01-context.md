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

2. **Application (direct) — cwd conveniences**
   - Add to `DirectConversationLogIndex`:
     - `listSessionsFor(cwd: os.Path): Seq[LogFileMetadata]`
     - `forSessionAt(cwd: os.Path, sessionId: String): Option[LogFileMetadata]`
   - Each resolves `sys.env.get("CLAUDE_CONFIG_DIR")` (treat empty as unset) and `os.home` eagerly, calls `ClaudeProjects.projectDirFor`, then delegates to the existing path-based method.

3. **Application (effectful) — IO-wrapped conveniences**
   - Add to `EffectfulConversationLogIndex`:
     - `listSessionsFor(cwd: os.Path): IO[Seq[LogFileMetadata]]`
     - `forSessionAt(cwd: os.Path, sessionId: String): IO[Option[LogFileMetadata]]`
   - Env + home reads happen inside the `IO` (via `IO.delay` / `Sync[F].delay`) so effects stay suspended. Delegates to existing path-based IO methods.

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
- Create a tmp dir laid out as `$tmp/projects/-a-b/<session>.jsonl` with a real fixture.
- Call `listSessionsFor(os.Path("/a/b"))` while pointing `CLAUDE_CONFIG_DIR` at `$tmp` (via a test seam — either set the env for the forked JVM or add a package-private overload taking the override explicitly, whichever matches existing test style).
- Assert it returns the same metadata as `listSessions($tmp/projects/-a-b)`.
- Same for `forSessionAt`.
- Empty `CLAUDE_CONFIG_DIR` string → treated as unset.

**`EffectfulConversationLogIndex` (integration):**
- Analogous, returning `IO`, unsafely run in the test.

**Regression gate:**
- `ProjectPathDecoderTest`, existing `DirectConversationLogIndexTest`, `EffectfulConversationLogIndexTest` must remain green untouched.
- `./mill __.compile` must produce zero warnings.
- `./mill __.test` must pass.

**Test seam note (CLARIFY during implementation):**
The tests need a way to inject `CLAUDE_CONFIG_DIR` without relying on process env. Check how existing tests handle env / home. Preferred options, in order:
1. Package-private overload on the index that accepts `(configDirOverride, home)` explicitly — tests use the overload, production call path threads env reads.
2. Test-time env mutation (only if already used elsewhere in this repo).
Pick option 1 unless the repo already has a pattern for option 2.

## Acceptance Criteria

- [ ] `ProjectPathEncoder` exists in `core` with tests covering the three encoding cases.
- [ ] `ClaudeProjects` exists in `core`, fully pure, with tests for unset / set / empty-string override and `projectDirFor` composition.
- [ ] `DirectConversationLogIndex.listSessionsFor(cwd)` and `forSessionAt(cwd, id)` exist, delegate to existing path-based methods, honor `CLAUDE_CONFIG_DIR`.
- [ ] `EffectfulConversationLogIndex.listSessionsFor(cwd)` and `forSessionAt(cwd, id)` exist as IO-returning methods with env reads suspended.
- [ ] All new public symbols carry Scaladoc covering encoding convention and `CLAUDE_CONFIG_DIR` semantics (env var replaces `~/.claude`; empty = unset; no `~` expansion).
- [ ] Existing path-based API is byte-identical (no signature, name, or behavior changes).
- [ ] `./mill __.compile` has zero warnings; `./mill __.test` and `./mill __.itest` pass.
- [ ] No new runtime dependencies introduced.
