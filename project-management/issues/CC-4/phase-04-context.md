# Phase 4: Service traits

## Goals

Define `ConversationLogIndex` and `ConversationLogReader` as abstract trait contracts in `core.log`, establishing the interface that Phase 5 implementations (direct and effectful) will satisfy.

## Scope

### In Scope

1. `ConversationLogIndex` trait in `works.iterative.claude.core.log` — session discovery:
   - `listSessions(projectPath: os.Path): F[Seq[LogFileMetadata]]` — all sessions for a project directory
   - `forSession(projectPath: os.Path, sessionId: String): F[Option[LogFileMetadata]]` — find a specific session

2. `ConversationLogReader` trait in `works.iterative.claude.core.log` — log file reading:
   - `readAll(path: os.Path): F[List[ConversationLogEntry]]` — load all entries from a log file
   - `stream(path: os.Path): S` — streaming read, where `S` is a type member (each implementation provides its own stream type)

3. Both traits use `os.Path` throughout since `LogFileMetadata.path` is already `os.Path`; the effectful implementation can adapt internally.

### Out of Scope

- Implementations (Phase 5)
- File I/O
- Filesystem discovery logic

## Dependencies

- **Phase 1**: `ConversationLogEntry`, `LogFileMetadata`
- No external library dependencies beyond what is already imported

## Approach

Follow the existing codebase pattern: `direct.ClaudeCode` and `effectful.ClaudeCode` are independent implementations with no shared abstract base. Apply the same pattern here — define the traits with a higher-kinded effect type parameter `F[_]` so both styles can implement them cleanly:

```scala
trait ConversationLogIndex[F[_]]:
  def listSessions(projectPath: os.Path): F[Seq[LogFileMetadata]]
  def forSession(projectPath: os.Path, sessionId: String): F[Option[LogFileMetadata]]
```

For `ConversationLogReader`, the streaming return type differs fundamentally between direct (`ox.flow.Flow`) and effectful (`fs2.Stream[IO, _]`), so use a type member for the stream type alongside `F[_]`:

```scala
trait ConversationLogReader[F[_]]:
  type EntryStream
  def readAll(path: os.Path): F[List[ConversationLogEntry]]
  def stream(path: os.Path): EntryStream
```

This keeps the traits in `core` free of direct/effectful dependencies while giving implementations full control over their stream type.

## Files to Create/Modify

### New Files

- `works/iterative/claude/core/log/ConversationLogIndex.scala` — index/discovery trait
- `works/iterative/claude/core/log/ConversationLogReader.scala` — reader trait

### Modified Files

- None

## Testing Strategy

Traits are abstract contracts with no logic to test directly. Tests for this phase verify:

- The traits compile with the expected method signatures
- A minimal anonymous implementation compiles for both `F = [A] =>> A` (identity/direct) and `F = cats.effect.IO` (effectful)

These are compilation-only tests, placed in a single test file:
- `test/works/iterative/claude/core/log/ServiceTraitTest.scala`

## Acceptance Criteria

1. `ConversationLogIndex[F[_]]` exists in `works.iterative.claude.core.log` with `listSessions` and `forSession`
2. `ConversationLogReader[F[_]]` exists in `works.iterative.claude.core.log` with `readAll` and `stream` (using an `EntryStream` type member)
3. Both traits use `os.Path` and reference `LogFileMetadata` / `ConversationLogEntry` from `core.log.model`
4. Compilation test confirms both traits can be implemented with identity `F` (direct style) and `IO` (effectful style)
5. All existing tests still pass
6. Both files have PURPOSE headers
7. No compilation warnings
