---
generated_from: 0d388a8f2ec36d3dce3891701cf0057028d0832a
generated_at: 2026-04-08T00:00:00Z
branch: CC-34-phase-01
issue_id: CC-34
phase: 1
files_analyzed:
  - core/src/works/iterative/claude/core/log/ClaudeProjects.scala
  - core/src/works/iterative/claude/core/log/ProjectPathEncoder.scala
  - core/test/src/works/iterative/claude/core/log/ClaudeProjectsTest.scala
  - core/test/src/works/iterative/claude/core/log/ProjectPathEncoderTest.scala
  - direct/src/works/iterative/claude/direct/log/DirectConversationLogIndex.scala
  - direct/test/src/works/iterative/claude/direct/log/DirectConversationLogIndexCwdTest.scala
  - effectful/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndex.scala
  - effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndexCwdTest.scala
  - effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndexTest.scala
  - effectful/test/src/works/iterative/claude/effectful/EffectfulPackageReexportTest.scala
---

# Review Packet: CC-34 — Lib-owned project dir resolution

## Goals

Move the Claude Code on-disk project-directory convention inside the SDK so
consumers no longer have to duplicate two pieces of logic:

1. **Base directory resolution** — defaulting to `~/.claude/projects/` but
   honoring the `CLAUDE_CONFIG_DIR` environment variable when set.
2. **cwd-to-project-dir encoding** — converting an absolute path like
   `/home/mph/ops/kanon` to the dash-encoded directory name
   `-home-mph-ops-kanon` that Claude Code stores on disk.

Key objectives:

- Add `ProjectPathEncoder` (pure, in `core`) as the symmetric counterpart to the
  existing `ProjectPathDecoder`.
- Add `ClaudeProjects` (pure, in `core`) with parameterized `baseDir` and
  `projectDirFor` — no env I/O inside the core module.
- Add cwd-based convenience methods (`listSessionsFor`, `forSessionAt`) on both
  `DirectConversationLogIndex` and `EffectfulConversationLogIndex`.
- Introduce constructor injection so the env read is testable without touching
  real `$HOME` contents.
- Preserve the existing path-based API verbatim (purely additive change), with
  one approved breaking change: `EffectfulConversationLogIndex.apply()` now
  returns `IO[EffectfulConversationLogIndex]` so the env read is deferred inside
  `IO`.

## Scenarios

- [ ] `ProjectPathEncoder.encode` converts `/home/mph/ops/kanon` to
      `-home-mph-ops-kanon` (slashes become dashes).
- [ ] `ProjectPathEncoder.encode` handles the root path `/` producing `-`.
- [ ] Paths with existing dashes encode correctly (all `/` are replaced; no
      special handling needed).
- [ ] `ClaudeProjects.baseDir` with no override returns
      `home / ".claude" / "projects"`.
- [ ] `ClaudeProjects.baseDir` with an override returns
      `override / "projects"`.
- [ ] `ClaudeProjects.resolveConfigDir` treats an empty string as unset.
- [ ] `ClaudeProjects.projectDirFor` composes `baseDir` with the encoded cwd.
- [ ] `DirectConversationLogIndex.listSessionsFor(cwd)` returns the same
      metadata as `listSessions(projectDir)` for the same cwd.
- [ ] `DirectConversationLogIndex.forSessionAt(cwd, id)` returns the same
      `Option` as `forSession(projectDir, id)`.
- [ ] `DirectConversationLogIndex` falls back to `home / ".claude" / "projects"`
      when `configDirOverride` is `None`.
- [ ] `EffectfulConversationLogIndex.listSessionsFor(cwd)` and
      `forSessionAt(cwd, id)` produce the same results as their path-based
      counterparts, inside `IO`.
- [ ] `EffectfulConversationLogIndex.apply(): IO[...]` defers the env read and
      returns a working instance.
- [ ] All existing path-based call sites compile and behave unchanged.
- [ ] Call sites of the old synchronous `EffectfulConversationLogIndex()` factory
      have been migrated to the IO-returning version.

## Entry Points

| File | Method / Object | Why Start Here |
|------|-----------------|----------------|
| `core/src/works/iterative/claude/core/log/ProjectPathEncoder.scala` | `ProjectPathEncoder.encode` | Foundational pure encoding step; everything else delegates to it |
| `core/src/works/iterative/claude/core/log/ClaudeProjects.scala` | `ClaudeProjects.resolveConfigDir`, `baseDir`, `projectDirFor` | Central logic for directory resolution — the single place that owns the on-disk convention |
| `direct/src/works/iterative/claude/direct/log/DirectConversationLogIndex.scala` | `DirectConversationLogIndex.apply()`, `listSessionsFor`, `forSessionAt` | Direct (synchronous) consumer of `ClaudeProjects`; shows the injection pattern for eagerly-read env |
| `effectful/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndex.scala` | `EffectfulConversationLogIndex.apply(): IO[...]`, `make`, `listSessionsFor`, `forSessionAt` | IO (effectful) consumer; illustrates deferred env read and approved breaking-change migration |

## Diagrams

### Component Relationships

```
core module (pure)
┌─────────────────────────────────────────────────────┐
│  ProjectPathEncoder                                  │
│    encode(path: os.Path): String                    │
│       └─ replaces '/' with '-'                      │
│                                                     │
│  ClaudeProjects                                     │
│    resolveConfigDir(env): Option[os.Path]           │
│    baseDir(override, home): os.Path                 │
│    projectDirFor(cwd, override, home): os.Path      │
│       └─ delegates to baseDir + ProjectPathEncoder  │
└─────────────────────────────────────────────────────┘
           ▲                          ▲
           │                          │
direct module                effectful module
┌─────────────────────┐   ┌──────────────────────────┐
│  DirectConversation │   │  EffectfulConversation    │
│  LogIndex           │   │  LogIndex                 │
│  ─────────────────  │   │  ──────────────────────   │
│  apply()            │   │  apply(): IO[...]         │
│  apply(ovr, home)   │   │  make(ovr, home)          │
│  listSessionsFor ───┼──▶│  listSessionsFor: IO[...] │
│  forSessionAt    ───┼──▶│  forSessionAt:    IO[...] │
└─────────────────────┘   └──────────────────────────┘
```

### `CLAUDE_CONFIG_DIR` Resolution Flow

```
caller constructs index
       │
       ▼
   [direct]  sys.env.get  ──eager──▶  ClaudeProjects.resolveConfigDir
   [effectful] IO { sys.env.get } ──deferred──▶ ClaudeProjects.resolveConfigDir
                                                        │
                                          filter(_.nonEmpty).map(os.Path(_))
                                                        │
                                       None  ◄──────────┴──────────► Some(path)
                                        │                                │
                               home / ".claude"                     configDir
                                  / "projects"                      / "projects"
                                        │                                │
                                        └──────────── baseDir ───────────┘
                                                         │
                                            + ProjectPathEncoder.encode(cwd)
                                                         │
                                                   projectDirFor
```

## Test Summary

### Unit Tests (core)

| File | Test | Type |
|------|------|------|
| `ProjectPathEncoderTest` | encodes typical multi-segment path | Unit |
| `ProjectPathEncoderTest` | encodes root path as single dash | Unit |
| `ProjectPathEncoderTest` | path with existing dashes encodes verbatim | Unit |
| `ClaudeProjectsTest` | baseDir with no override uses home / .claude / projects | Unit |
| `ClaudeProjectsTest` | baseDir with Some(override) uses override / projects | Unit |
| `ClaudeProjectsTest` | projectDirFor composes baseDir with encoded cwd | Unit |
| `ClaudeProjectsTest` | projectDirFor with override uses custom base | Unit |
| `ClaudeProjectsTest` | resolveConfigDir treats empty string as unset | Unit |
| `ClaudeProjectsTest` | resolveConfigDir resolves non-empty value to os.Path | Unit |
| `ClaudeProjectsTest` | resolveConfigDir returns None when env var is unset | Unit |

### Integration Tests (direct)

| File | Test | Type |
|------|------|------|
| `DirectConversationLogIndexCwdTest` | listSessionsFor returns same metadata as path-based listSessions | Integration |
| `DirectConversationLogIndexCwdTest` | forSessionAt returns same Option as path-based forSession | Integration |
| `DirectConversationLogIndexCwdTest` | forSessionAt returns None for missing session | Integration |
| `DirectConversationLogIndexCwdTest` | configDirOverride = None falls back to home / .claude / projects | Integration |
| `DirectConversationLogIndexCwdTest` | no-arg apply() returns a working index (smoke test) | Integration |

### Integration Tests (effectful)

| File | Test | Type |
|------|------|------|
| `EffectfulConversationLogIndexCwdTest` | listSessionsFor returns same session IDs as path-based listSessions | Integration |
| `EffectfulConversationLogIndexCwdTest` | forSessionAt returns same Option as path-based forSession | Integration |
| `EffectfulConversationLogIndexCwdTest` | forSessionAt returns None for missing session | Integration |
| `EffectfulConversationLogIndexCwdTest` | configDirOverride = None falls back to home / .claude / projects | Integration |
| `EffectfulConversationLogIndexCwdTest` | production apply() returns a working index (smoke test) | Integration |

### Regression / Migration Tests

| File | Test | Type |
|------|------|------|
| `EffectfulConversationLogIndexTest` | (existing tests) migrated from synchronous `apply()` to `.make(None, os.home)` | Regression |
| `EffectfulPackageReexportTest` | (existing tests) migrated to `for index <- EffectfulConversationLogIndex()` binding; resource-leak-on-failure bugs fixed via `.guarantee` | Regression |

**Total new tests added: 20** (10 unit, 10 integration)
**Full suite at merge: 397/397 unit, 550/550 integration, zero compile warnings**

## Files Changed

| File | Status | Summary |
|------|--------|---------|
| `core/src/works/iterative/claude/core/log/ProjectPathEncoder.scala` | Added | Pure `encode(path)` replacing `/` with `-`; symmetric partner to `ProjectPathDecoder` |
| `core/src/works/iterative/claude/core/log/ClaudeProjects.scala` | Added | Pure `resolveConfigDir`, `baseDir`, `projectDirFor`; owns the CLAUDE_CONFIG_DIR convention |
| `core/test/.../ProjectPathEncoderTest.scala` | Added | 3 unit tests covering typical path, root, and dash-containing paths |
| `core/test/.../ClaudeProjectsTest.scala` | Added | 7 unit tests covering baseDir/projectDirFor variants and resolveConfigDir edge cases |
| `direct/src/.../DirectConversationLogIndex.scala` | Modified | Private constructor with injected config; two new factory overloads; `listSessionsFor` + `forSessionAt` |
| `direct/test/.../DirectConversationLogIndexCwdTest.scala` | Added | 5 integration tests using tmp dir fixtures and test seam |
| `effectful/src/.../EffectfulConversationLogIndex.scala` | Modified | Private constructor; `apply(): IO[...]` (breaking change); `make` test seam; `listSessionsFor` + `forSessionAt` |
| `effectful/test/.../EffectfulConversationLogIndexCwdTest.scala` | Added | 5 integration tests (IO-based, CatsEffectSuite) |
| `effectful/test/.../EffectfulConversationLogIndexTest.scala` | Modified | Migrated from old synchronous factory to `.make(None, os.home)` |
| `effectful/test/.../EffectfulPackageReexportTest.scala` | Modified | Migrated to IO-binding factory; fixed two resource-leak-on-failure bugs with `.guarantee` |
| `ARCHITECTURE.md` | Modified | Added entries for `ProjectPathEncoder` and `ClaudeProjects` under core log utilities |
| `README.md` | Modified | Updated example to use IO-returning factory |

<details>
<summary>Notes for reviewers</summary>

- The only **breaking change** is `EffectfulConversationLogIndex.apply()` now
  returning `IO[EffectfulConversationLogIndex]` instead of a bare instance. This
  was explicitly approved. All in-repo call sites are updated in this branch.
- `resolveConfigDir` is defined once in `ClaudeProjects` (core) and called from
  both `DirectConversationLogIndex.apply()` (eagerly) and
  `EffectfulConversationLogIndex.apply()` (inside `IO`). No duplication.
- The test seam naming asymmetry (`apply(ovr, home)` on Direct vs. `make(ovr,
  home)` on Effectful) is intentional: Effectful's `apply` is already taken by
  the IO factory.
- Promoting `listSessionsFor`/`forSessionAt` to the `ConversationLogIndex[F]`
  trait was deferred and is tracked in the implementation log as future work.

</details>
