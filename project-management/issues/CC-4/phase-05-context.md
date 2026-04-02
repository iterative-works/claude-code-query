# Phase 5: Service implementations

## Goals

Implement `ConversationLogIndex` and `ConversationLogReader` in both `direct.log` and `effectful.log` packages. The direct implementations use os-lib for file I/O and Ox `Flow` for streaming. The effectful implementations use fs2.io.file for discovery and fs2.Stream for streaming. Both delegate to the pure `ConversationLogParser` for actual line parsing.

A key part of this phase is path decoding: the `~/.claude/projects/` directory structure uses path-encoded directory names (e.g., `-home-mph-Devel` for `/home/mph/Devel`). Both implementations must decode these paths to populate `LogFileMetadata.cwd`.

## Scope

### In Scope

1. **Direct `ConversationLogIndex`** (`works.iterative.claude.direct.log.DirectConversationLogIndex`):
   - Implements `ConversationLogIndex[[A] =>> A]` (identity effect)
   - Uses `os.list` / `os.walk` to find `.jsonl` files under a project path
   - Extracts `sessionId` from filename (filename minus `.jsonl` extension)
   - Populates `LogFileMetadata` with file stats (`os.stat`) and decoded `cwd` from path
   - `listSessions`: lists all `.jsonl` files in the project path
   - `forSession`: finds a specific session file by `sessionId`

2. **Direct `ConversationLogReader`** (`works.iterative.claude.direct.log.DirectConversationLogReader`):
   - Implements `ConversationLogReader[[A] =>> A]` with `EntryStream = ox.flow.Flow[ConversationLogEntry]`
   - `readAll`: reads all lines with `os.read.lines`, parses each with `ConversationLogParser.parseLogLine`, collects results
   - `stream`: returns an `ox.flow.Flow[ConversationLogEntry]` that reads lines lazily

3. **Effectful `ConversationLogIndex`** (`works.iterative.claude.effectful.log.EffectfulConversationLogIndex`):
   - Implements `ConversationLogIndex[IO]`
   - Uses `fs2.io.file.Files[IO]` for directory listing
   - Same discovery logic as direct but wrapped in `IO`

4. **Effectful `ConversationLogReader`** (`works.iterative.claude.effectful.log.EffectfulConversationLogReader`):
   - Implements `ConversationLogReader[IO]` with `EntryStream = fs2.Stream[IO, ConversationLogEntry]`
   - `readAll`: reads file, parses lines, collects to `List` in `IO`
   - `stream`: returns `fs2.Stream[IO, ConversationLogEntry]` using fs2 text pipeline

5. **Path decoding utility** — shared pure function to decode project directory names back to filesystem paths (e.g., `-home-mph-Devel` → `/home/mph/Devel`). This should live in `core.log` since both implementations need it.

### Out of Scope

- Re-exports (Phase 6)
- Documentation updates (Phase 6)
- Reading metadata from inside log files (summary, gitBranch, etc. — only file-level metadata from stat and path for now)
- Cross-project discovery (scanning all subdirectories of `~/.claude/projects/` to list sessions across all projects) — the current trait API requires a `projectPath` argument; cross-project enumeration can be added later as a higher-level utility

## Dependencies

- **Phase 1**: `ConversationLogEntry`, `LogFileMetadata`, all payload types
- **Phase 3**: `ConversationLogParser.parseLogLine` for pure parsing
- **Phase 4**: `ConversationLogIndex[F[_]]`, `ConversationLogReader[F[_]]` trait contracts
- **Libraries**: os-lib (file I/O), ox (Flow for direct streaming), cats-effect IO, fs2 (Stream + io.file for effectful)

## Approach

### Path Decoding

The `~/.claude/projects/` directory contains subdirectories with encoded paths. The encoding replaces `/` with `-` in the absolute path. For example:
- Directory name `-home-mph-Devel-projects-foo` → `/home/mph/Devel/projects/foo`

The encoding is ambiguous because path segments themselves can contain `-` (e.g., `-home-mph-my-project` could be `/home/mph/my-project` or `/home/mph/my/project`).

**Decision:** `ProjectPathDecoder` in `core.log` is a pure utility that stores the raw encoded directory name as-is in `LogFileMetadata.cwd`. It also provides a best-effort `decode(dirName: String): String` method that replaces leading `-` with `/` and subsequent `-` with `/` — this gives a plausible path that callers can verify against the filesystem if they need accuracy. The `cwd` field in `LogFileMetadata` stores this best-effort decoded string. No filesystem validation is done inside the decoder — it's a pure function.

### Direct Implementation Pattern

Follow the pattern from `direct.ClaudeCode`:
- Plain classes/objects, no effect wrappers
- Direct os-lib calls for file I/O
- Ox `Flow` for streaming (lazy line-by-line processing)
- `given Ox` context for Flow operations if needed

### Effectful Implementation Pattern

Follow the pattern from `effectful.ClaudeCode`:
- Wrap file operations in `IO`
- Use `fs2.io.file.Files[IO]` for directory listing and file reading
- Convert `os.Path` to `java.nio.file.Path` via `.toNIO` for fs2 compatibility
- Use `fs2.Stream` text processing pipeline for line-by-line parsing
- Return `IO[...]` for non-streaming operations

### LogFileMetadata Population

From file-level information only (no file content parsing):
- `path`: the `.jsonl` file path
- `sessionId`: filename without `.jsonl` extension
- `lastModified`: from file stat
- `fileSize`: from file stat
- `cwd`: decoded from parent directory name (best-effort)
- `summary`, `gitBranch`, `createdAt`: `None` (would require reading file contents)

## Files to Create/Modify

### New Files

- `works/iterative/claude/core/log/ProjectPathDecoder.scala` — pure path decoding utility
- `works/iterative/claude/direct/log/DirectConversationLogIndex.scala` — direct index implementation
- `works/iterative/claude/direct/log/DirectConversationLogReader.scala` — direct reader implementation
- `works/iterative/claude/effectful/log/EffectfulConversationLogIndex.scala` — effectful index implementation
- `works/iterative/claude/effectful/log/EffectfulConversationLogReader.scala` — effectful reader implementation

### Test Files

- `test/works/iterative/claude/core/log/ProjectPathDecoderTest.scala` — path decoding tests
- `test/works/iterative/claude/direct/log/DirectConversationLogIndexTest.scala` — direct index integration tests
- `test/works/iterative/claude/direct/log/DirectConversationLogReaderTest.scala` — direct reader integration tests
- `test/works/iterative/claude/effectful/log/EffectfulConversationLogIndexTest.scala` — effectful index integration tests
- `test/works/iterative/claude/effectful/log/EffectfulConversationLogReaderTest.scala` — effectful reader integration tests

### Modified Files

- None expected

## Testing Strategy

### Unit Tests

- `ProjectPathDecoder`: test various directory name patterns, edge cases (root path, single segment, empty)

### Integration Tests

All service tests use real file I/O with temporary directories:

- **Index tests**: Create temp directory structure mimicking `~/.claude/projects/`, populate with `.jsonl` files, verify `listSessions` finds them and `forSession` returns the right one
- **Reader tests**: Create temp `.jsonl` files with known content (representative log entries), verify `readAll` returns correctly parsed entries and `stream` produces the same results lazily
- Both direct and effectful implementations tested against the same scenarios

### Test Data

- Small `.jsonl` test files with representative entries (user, assistant, system, progress)
- Reuse entry JSON patterns from Phase 3 `ConversationLogParserTest`

## Acceptance Criteria

1. `DirectConversationLogIndex` lists and finds session files using os-lib
2. `DirectConversationLogReader` reads and streams log entries using os-lib + Ox Flow
3. `EffectfulConversationLogIndex` lists and finds session files using fs2.io.file
4. `EffectfulConversationLogReader` reads and streams log entries using fs2.Stream
5. `ProjectPathDecoder` correctly decodes project directory names to paths
6. All implementations pass integration tests with real temporary files
7. All existing tests still pass
8. All files have PURPOSE headers
9. No compilation warnings
