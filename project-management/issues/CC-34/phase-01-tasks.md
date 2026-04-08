# Phase 1 Tasks: Lib-owned project dir resolution

## Setup

- [x] [setup] Confirm package of existing `ProjectPathDecoder` (`core/src/works/iterative/claude/core/log/ProjectPathDecoder.scala`) — new encoder + `ClaudeProjects` must live in the same package for symmetry
- [x] [setup] Record current test counts: `./mill core.test`, `./mill direct.test`, `./mill effectful.test` — note totals so regression gate can confirm no tests lost
- [x] [setup] Grep repo for existing call sites of `EffectfulConversationLogIndex()` (both source and tests) so the breaking-change migration list is known up front

## Core — ProjectPathEncoder (TDD)

- [x] [test] Create `core/test/src/works/iterative/claude/core/log/ProjectPathEncoderTest.scala` with three failing cases: `/home/mph/ops/kanon` → `-home-mph-ops-kanon`, `/` → `-`, and a path already containing `-` (e.g. `/a-b/c`) encodes verbatim with all `/` replaced by `-`
- [x] [verify] Run `./mill core.test.compile` — test file fails to compile because `ProjectPathEncoder` does not yet exist (expected)
- [x] [impl] Create `core/src/works/iterative/claude/core/log/ProjectPathEncoder.scala` with a 2-line `PURPOSE:` header and a pure `encode(cwd: os.Path): String` method that replaces `/` with `-`; include Scaladoc describing the encoding convention
- [x] [verify] Run `./mill core.test` — `ProjectPathEncoderTest` passes

## Core — ClaudeProjects (TDD)

- [x] [test] Create `core/test/src/works/iterative/claude/core/log/ClaudeProjectsTest.scala` with failing cases for `baseDir`: unset override + `home=/tmp/fakeHome` → `/tmp/fakeHome/.claude/projects`; `Some(/tmp/custom)` → `/tmp/custom/projects`
- [x] [test] Add failing case for `projectDirFor(cwd=/a/b, None, /tmp/fakeHome)` → `/tmp/fakeHome/.claude/projects/-a-b` (composition test)
- [x] [verify] Run `./mill core.test.compile` — test file fails to compile (expected)
- [x] [impl] Create `core/src/works/iterative/claude/core/log/ClaudeProjects.scala` with a 2-line `PURPOSE:` header, a pure `baseDir(configDirOverride, home)` and `projectDirFor(cwd, configDirOverride, home)`; no `sys.env` reads; Scaladoc covering `CLAUDE_CONFIG_DIR` semantics (replaces `~/.claude`, empty = unset, no `~` expansion)
- [x] [verify] Run `./mill core.test` — all `ClaudeProjectsTest` cases pass
- [x] [verify] Run `./mill core.compile` — zero warnings

## Direct — test seam and cwd methods (TDD)

- [x] [test] Create `direct/test/src/works/iterative/claude/direct/log/DirectConversationLogIndexCwdTest.scala` (mirror directory of existing `DirectConversationLogIndexTest`): failing test constructing index via `DirectConversationLogIndex(Some(tmpDir), tmpHome)`, laying out `$tmpDir/projects/-a-b/<session>.jsonl` fixture, asserting `listSessionsFor(os.Path("/a/b"))` returns same metadata as `listSessions($tmpDir/projects/-a-b)`
- [x] [test] Add failing case for `forSessionAt(os.Path("/a/b"), sessionId)` returning same `Option[LogFileMetadata]` as the path-based form
- [x] [test] Add failing case for `configDirOverride = None` falling back to `home / ".claude" / "projects"` using a `tmpHome` fixture
- [x] [test] Add smoke test for production `DirectConversationLogIndex()` no-arg factory — constructs without throwing; do not assert on real `$HOME` contents
- [x] [verify] Run `./mill direct.test.compile` — fails because new ctor/methods do not yet exist (expected)
- [x] [impl] In `direct/src/.../DirectConversationLogIndex.scala`: make primary constructor `private`, accept `(configDirOverride: Option[os.Path], home: os.Path)`; keep existing path-based `listSessions` / `forSession` byte-identical
- [x] [impl] Add companion `apply(): DirectConversationLogIndex` — reads `sys.env.get("CLAUDE_CONFIG_DIR").filter(_.nonEmpty).map(os.Path(_))` once, uses `os.home`, returns instance
- [x] [impl] Add companion `apply(configDirOverride: Option[os.Path], home: os.Path): DirectConversationLogIndex` — test seam
- [x] [impl] Add instance `listSessionsFor(cwd: os.Path): Seq[LogFileMetadata]` delegating to `listSessions(ClaudeProjects.projectDirFor(cwd, configDirOverride, home))`
- [x] [impl] Add instance `forSessionAt(cwd: os.Path, sessionId: String): Option[LogFileMetadata]` analogous delegation
- [x] [impl] Add Scaladoc to all new public symbols (factories + cwd methods) covering `CLAUDE_CONFIG_DIR` semantics
- [x] [verify] Run `./mill direct.test` — new tests pass, existing `DirectConversationLogIndexTest` still green
- [x] [verify] Run `./mill direct.compile` — zero warnings

## Effectful — IO factory (BREAKING) and cwd methods (TDD)

- [x] [test] Create `effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndexCwdTest.scala`: failing test constructing via synchronous test seam `EffectfulConversationLogIndex.make(Some(tmpDir), tmpHome)`, with `$tmpDir/projects/-a-b/<session>.jsonl` fixture, asserting `listSessionsFor(os.Path("/a/b")).unsafeRunSync()` matches path-based `listSessions` result
- [x] [test] Add failing case for `forSessionAt(os.Path("/a/b"), sessionId)` returning same `Option[LogFileMetadata]` as path-based form
- [x] [test] Add failing case for `configDirOverride = None` fallback to `home / ".claude" / "projects"`
- [x] [test] Add smoke test that production `EffectfulConversationLogIndex.apply(): IO[...]` runs and produces an instance
- [x] [verify] Run `./mill effectful.test.compile` — fails (expected)
- [x] [impl] In `effectful/src/.../EffectfulConversationLogIndex.scala`: make primary constructor `private` accepting `(configDirOverride: Option[os.Path], home: os.Path)`; keep existing path-based methods byte-identical
- [x] [impl] Replace existing synchronous `apply(): EffectfulConversationLogIndex` with `apply(): IO[EffectfulConversationLogIndex]` — reads env + `os.home` inside `IO`, captured once per instance (BREAKING CHANGE, approved in context §Design decisions)
- [x] [impl] Add companion `make(configDirOverride: Option[os.Path], home: os.Path): EffectfulConversationLogIndex` — synchronous test seam
- [x] [impl] Add instance `listSessionsFor(cwd: os.Path): IO[Seq[LogFileMetadata]]` delegating to `listSessions(ClaudeProjects.projectDirFor(cwd, configDirOverride, home))`
- [x] [impl] Add instance `forSessionAt(cwd: os.Path, sessionId: String): IO[Option[LogFileMetadata]]` analogous delegation
- [x] [impl] Add Scaladoc to all new public symbols covering `CLAUDE_CONFIG_DIR` semantics
- [x] [verify] Run `./mill effectful.test` — new tests pass

## Migration — update callers of old synchronous factory

- [x] [impl] Grep all source and test modules for `EffectfulConversationLogIndex()` / `EffectfulConversationLogIndex.apply()` call sites (result of setup grep) — update each to consume the `IO[...]` result, either via `for` binding or by using `.make(...)` in tests
- [x] [verify] Run `./mill __.compile` — zero warnings, no unresolved references

## Integration and regression gates

- [x] [integration] Run `./mill __.compile` — full project compiles with zero warnings
- [x] [integration] Run `./mill __.test` — all unit tests pass; compare totals against Setup baseline (new tests added, none lost)
- [x] [integration] Run `./mill __.itest` — all integration tests pass (skips due to CLI unavailability are acceptable)
- [x] [integration] Confirm `ProjectPathDecoderTest`, `DirectConversationLogIndexTest`, `EffectfulConversationLogIndexTest` are still green and untouched

## Documentation

- [x] [impl] Verify all new public symbols (`ProjectPathEncoder`, `ClaudeProjects`, both new factories on each index, all four cwd methods) carry Scaladoc covering encoding convention + `CLAUDE_CONFIG_DIR` semantics (env replaces `~/.claude`, empty = unset, no `~` expansion)
- [x] [impl] Check `ARCHITECTURE.md` — if it enumerates core log utilities, add `ProjectPathEncoder` and `ClaudeProjects`; otherwise leave untouched
- [ ] [verify] Commit all changes

**Phase Status:** Complete
