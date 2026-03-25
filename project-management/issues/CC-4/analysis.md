# Technical Analysis: Add conversation log parsing support

**Issue:** CC-4
**Created:** 2026-03-24
**Status:** Draft

## Problem Statement

The SDK currently only parses real-time CLI stream output (`--output-format stream-json`). Claude Code also stores full conversation logs as JSONL files in `~/.claude/projects/`, containing richer data than the streaming format: thinking blocks (with signatures), token usage per message, model information, message threading (uuid/parentUuid), sidechain flags, and various metadata entries (progress, queue operations, file history snapshots).

Thinking blocks are not available through the streaming interface at all, making log parsing the only programmatic way to access this data. External tools that want to analyze past sessions, display thinking content, or compute per-message cost breakdowns have no way to do this through the current SDK.

## Proposed Solution

### High-Level Approach

Add a new `core.log` package containing pure parsing logic for JSONL conversation log files, with corresponding `direct` and `effectful` API surfaces. The log format is fundamentally different from the stream-json format: each JSONL line is a self-contained envelope carrying metadata (uuid, parentUuid, timestamp, sessionId, isSidechain, cwd, version, etc.) plus a type-specific payload. The parser must handle 7+ entry types rather than the 4 message types in stream-json.

The domain model introduces a `ConversationLogEntry` envelope type that wraps type-specific payloads, plus a new `ThinkingBlock` content block. The existing `ContentBlock` hierarchy is extended. Two new service traits are introduced: `ConversationLogIndex` (list/discover log files) and `ConversationLogReader` (parse a single log file into a stream of typed entries).

### Why This Approach

The conversation log format shares some structure with stream-json (content blocks, message roles) but differs significantly in envelope structure, entry types, and metadata richness. Extending the existing `ContentBlock` with `ThinkingBlock` maximizes reuse while keeping log-specific types (envelope, usage, model metadata) in a separate package. The index/reader split separates discovery concerns from parsing concerns, and both services naturally fit the existing dual-API pattern.

### Package Mapping

The codebase uses `core` (model + parsing), `direct`, and `effectful` — not traditional layered architecture packages. The conceptual layers map to actual packages as follows:

| Conceptual Layer | Actual Package(s) |
|---|---|
| Domain (types) | `core.log.model` (new log types), `core.model` (extended `ContentBlock`) |
| Parsing (pure logic) | `core.log.parsing` (new log parser), `core.parsing` (extended content block parsing) |
| Service traits | `core.log` (trait definitions for `ConversationLogIndex`, `ConversationLogReader`) |
| Direct API | `direct.log` (Ox-based implementations of service traits) |
| Effectful API | `effectful.log` (cats-effect/fs2 implementations of service traits) |

## Architecture Design

**Purpose:** Define WHAT components each layer needs, not HOW they're implemented.

### Domain Layer (`core.log.model` + `core.model`)

**Components:**
- `ThinkingBlock` — new `ContentBlock` variant for extended thinking content (added to `core.model.ContentBlock`)
- `ConversationLogEntry` — envelope type carrying common metadata (uuid, parentUuid, timestamp, sessionId, isSidechain, cwd, gitBranch, version, userType, entrypoint, slug)
- `LogEntryPayload` — sealed trait for type-specific payloads:
  - `UserLogEntry` — user messages with content blocks (not just strings, since log format has tool_result content blocks in user messages)
  - `AssistantLogEntry` — assistant messages with content blocks, model, usage metadata, requestId
  - `SystemLogEntry` — system events (subtype + data map, similar to existing SystemMessage)
  - `ProgressLogEntry` — progress updates (data map, parentToolUseID)
  - `QueueOperationLogEntry` — queue operations (operation type, content)
  - `FileHistorySnapshotLogEntry` — file state snapshots
  - `LastPromptLogEntry` — last prompt markers
- `TokenUsage` — structured token usage (input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens, service_tier)
- `LogFileMetadata` — basic metadata about a log file (path, sessionId, timestamps, size) for the index service

**Responsibilities:**
- Model the full conversation log structure as immutable types
- `TokenUsage` provides typed access to token counts rather than `Map[String, Any]`

**Estimated Effort:** 1-3 hours
**Complexity:** Low — many types but each is a straightforward case class/enum; fixing exhaustiveness warnings is the only non-trivial part

---

### Parsing Layer (`core.log.parsing` + `core.parsing`)

**Components:**
- `ConversationLogParser` (in `core.log.parsing`) — pure parsing logic for log JSONL lines
  - `parseLogLine(line: String): Option[ConversationLogEntry]`
  - `parseLogEntry(json: Json): Option[ConversationLogEntry]`
  - Content block parsing for thinking blocks
- Extension of `core.parsing.JsonParser` — add `thinking` case to content block parsing

**Note:** `JsonParser.parseContentBlock` is currently `private`. Two options:
1. Make it package-private or public so `ConversationLogParser` can reuse it
2. Extract shared content block parsing into a common utility (e.g., `core.parsing.ContentBlockParser`)

Option 2 is cleaner — it avoids coupling the log parser to the stream-json parser and creates a reusable content block parsing utility that both parsers consume.

**Responsibilities:**
- Pure JSON-to-type conversion for all log entry types
- Content block parsing (text, thinking, tool_use, tool_result) shared between stream and log parsers
- Graceful handling of malformed/unknown entries

**Estimated Effort:** 3-5 hours
**Complexity:** Moderate — many entry types to handle; the bulk of implementation and testing work

---

### Service Layer (`core.log` traits + `direct.log` + `effectful.log`)

**Components:**
- `ConversationLogIndex` trait (in `core.log`) — lists available log files with metadata
  - `list(projectPath: Path): Seq[LogFileMetadata]` — scan a project directory for JSONL files
  - `forSession(projectPath: Path, sessionId: String): Option[LogFileMetadata]` — find a specific session
- `ConversationLogReader` trait (in `core.log`) — reads and parses a single log file
  - `read(path: Path): Stream[ConversationLogEntry]` — returns a stream of parsed entries
  - `readAll(path: Path): List[ConversationLogEntry]` — convenience for loading entire file
- Direct implementations (in `direct.log`) — uses os-lib for file I/O, Ox Flow for streaming
- Effectful implementations (in `effectful.log`) — uses fs2.io.file for file I/O, fs2.Stream for streaming

**Responsibilities:**
- File I/O for listing and reading JSONL files
- Line-by-line reading delegating to pure `ConversationLogParser`
- Extracting basic metadata (sessionId from filename, file size, modification time)

**Estimated Effort:** 3-4 hours
**Complexity:** Moderate — dual API implementations follow existing patterns but still require writing and testing both

---

### Re-exports

Both `direct.package` and `effectful.package` (or equivalent re-export objects) need to expose the new types: `ThinkingBlock`, log entry types, `ConversationLogIndex`, `ConversationLogReader`.

---

## Technical Decisions

### Patterns

- Extend existing `ContentBlock` sealed trait with `ThinkingBlock` — keeps one content block hierarchy
- New `ConversationLogEntry` envelope is separate from `Message` — the log format is structurally different (envelope + payload vs. flat message)
- Extract shared content block parsing into `core.parsing.ContentBlockParser` — avoids coupling between log and stream parsers
- `ConversationLogIndex` and `ConversationLogReader` as traits in `core.log` with implementations in `direct.log` and `effectful.log`

### Technology Choices

- **Parsing**: circe (already used) for JSON parsing of log lines
- **File I/O (direct)**: `os-lib` (already a dependency) for file listing and reading
- **File I/O (effectful)**: `fs2.io.file` (already a dependency) for streaming file reads
- **Data Storage**: Read-only access to existing JSONL files on disk; no writes
- **Time parsing**: `java.time.Instant` for ISO-8601 timestamps

### Integration Points

- `core.model.ContentBlock` — extended with `ThinkingBlock`
- `core.parsing.JsonParser.parseContentBlock` — refactored: shared logic extracted to `ContentBlockParser`, `parseContentBlock` made non-private or replaced
- `direct.package` and `effectful.package` — re-export new types
- New `core.log.model` package depends on `core.model` (for `ContentBlock`)
- New `core.log.parsing` package depends on `core.parsing.ContentBlockParser` (for shared content block parsing)

## Technical Risks & Uncertainties

### RESOLVED: Log entry type exhaustiveness

**Decision:** Option B — Capture unknown types as `RawLogEntry(entryType: String, json: Json)`.

The format is undocumented and will evolve. Silently dropping data means consumers can't detect they're missing something. A `RawLogEntry` is cheap to implement and gives downstream code a way to handle new types without SDK updates.

---

### RESOLVED: User message content representation

**Decision:** Option A — `UserLogEntry` uses `List[ContentBlock]` for content, separate from `UserMessage(String)`.

Log types are a separate hierarchy (see next decision). User messages in logs genuinely contain content block arrays (including `tool_result` blocks), so `UserLogEntry` should faithfully represent the log format.

---

### RESOLVED: Relationship between log types and existing Message types

**Decision:** Option A — Entirely separate type hierarchies.

Investigation of the TypeScript Claude Agent SDK (`@anthropic-ai/claude-agent-sdk`) confirms this approach. The TS SDK defines `SDKMessage` (a 23+ variant union) for stream output and a separate `SessionMessage` type for JSONL log reading. `SessionMessage` has `message: unknown` — they don't even try to share types between stream and log formats. `getSessionMessages()` returns `SessionMessage[]`, while `query()` yields `SDKMessage`. The two concerns are fully decoupled.

Our Scala SDK can be richer than the TS SDK by actually typing the log message payloads (they leave it as `unknown`), but the type hierarchy separation is the same. Shared parts are at the `ContentBlock` level (reused by both hierarchies), not at the `Message` level.

---

### RESOLVED: Log file discovery heuristics

**Decision:** Option B — Full discovery with path decoding.

The primary use cases require being able to: (1) list all sessions across all projects, (2) list sessions for a specific project directory, (3) get a specific session by ID searching across projects. This matches the TS SDK's `listSessions({ dir? })` and `getSessionInfo(sessionId, { dir? })` API surface.

The `~/.claude/projects/` directory structure uses path-encoded directory names (e.g., `-home-mph-Devel` for `/home/mph/Devel`). The `ConversationLogIndex` needs to decode these back to filesystem paths, enumerate JSONL files within them, and extract metadata (sessionId from filename, file size, modification time). The TS SDK's `SDKSessionInfo` includes: `sessionId`, `summary`, `lastModified`, `fileSize`, `customTitle`, `firstPrompt`, `gitBranch`, `cwd`, `tag`, `createdAt` — we should aim for comparable metadata in `LogFileMetadata`.

---

### RESOLVED: ThinkingBlock signature optionality

**Decision:** Option A — `ThinkingBlock(thinking: String, signature: String)` with both fields required.

The official Anthropic API SDK (`@anthropic-ai/sdk`) defines `ThinkingBlock` with both `signature: string` and `thinking: string` as required (non-optional) fields. This is the canonical type for completed thinking blocks.

Additionally, the API defines `RedactedThinkingBlock { data: string, type: 'redacted_thinking' }` for cases where thinking content is hidden for safety reasons. We should add this as a separate `ContentBlock` variant as well.

---

### RESOLVED: Timestamp handling for log entries

**Decision:** Option B — `timestamp: Option[Instant]`, entries always included.

The TS SDK marks `timestamp` as optional on `SDKUserMessage` (`timestamp?: string`), confirming it can be absent. Parsing to `Instant` gives consumers typed access, and `Option` ensures entries are never silently dropped due to timestamp issues.

---

## Total Estimates

**Per-Layer Breakdown:**
- Domain Layer: 1-3 hours
- Parsing Layer: 3-5 hours
- Service Layer: 3-4 hours

**Total Range:** 7-12 hours

**Confidence:** Medium

**Reasoning:**
- The log format is observable from real files but undocumented; surprises are possible
- The dual-API pattern is well-established, reducing uncertainty for the service layer
- Many new types to define but each is mechanically straightforward
- The CLARIFY items around type relationships could shift the design significantly

## Testing Strategy

### Per-Layer Testing

**Domain Layer:**
- Unit: Verify type construction and field access for all new types
- Unit: Verify `TokenUsage` correctly represents token count structures

**Parsing Layer:**
- Unit: `ConversationLogParser.parseLogLine` with representative JSON strings for each entry type
- Unit: `ConversationLogParser` handling of malformed/unknown entries
- Unit: `ContentBlockParser` with thinking blocks (and existing block types for regression)
- Integration: Full parse of real log file samples (complete JSONL files as test resources)

**Service Layer:**
- Integration: `ConversationLogIndex` listing real JSONL files in temp directories
- Integration: `ConversationLogReader` reading and parsing real sample JSONL files end-to-end
- Integration: Both direct and effectful implementations tested with real file I/O

**Test Data Strategy:**
- Embed sample JSONL snippets as string constants in test files (one per entry type)
- Include a small but complete sample JSONL file as a test resource for integration tests
- Sample data derived from real log files (with sensitive content redacted)

**Regression Coverage:**
- Existing `JsonParser` tests must still pass after extracting shared content block parsing
- Existing `ContentBlock` pattern matches in the codebase must be checked for exhaustiveness warnings after adding `ThinkingBlock`

## Deployment Considerations

### Database Changes
None. Read-only access to existing files.

### Configuration Changes
None required. Log file paths are passed by the caller.

### Rollout Strategy
Library release — consumers opt in by calling the new API.

### Rollback Plan
Revert to previous SDK version. No persistent state changes.

## Dependencies

### Prerequisites
- Resolve CLARIFY markers (especially type relationship decisions) before implementation
- Sample JSONL test fixtures prepared from real log files

### Layer Dependencies
- Domain types must be defined first (types used by all other layers)
- Content block parsing extraction/extension can happen in parallel with domain types
- Log parser depends on domain types and shared content block parsing
- Service trait definitions depend on domain types
- Service implementations (direct + effectful) depend on service traits and log parser
- Direct and effectful implementations can be developed in parallel

### External Blockers
- None. Log format is already stable in current Claude Code versions.

## Risks & Mitigations

### Risk 1: Log format changes in future Claude Code versions
**Likelihood:** Medium
**Impact:** Medium
**Mitigation:** Design parser to skip unknown entry types gracefully; use Option types for fields that may not be present in all versions. Include Claude Code version from log metadata in parsed entries.

### Risk 2: Exhaustiveness warnings from adding ThinkingBlock to ContentBlock
**Likelihood:** High
**Impact:** Low
**Mitigation:** Adding a new case to `ContentBlock` will cause match exhaustiveness warnings wherever it is pattern-matched. These are compile-time warnings and must be fixed as part of this work. The existing code uses `case _ => None` or `.collectFirst` patterns that should handle this gracefully.

### Risk 3: Large log files causing memory pressure
**Likelihood:** Low
**Impact:** Medium
**Mitigation:** The streaming reader design (fs2.Stream / ox.flow.Flow) processes entries lazily. The `readAll` convenience method should document that it loads everything into memory.

---

## Implementation Sequence

**Recommended Order:**

1. **Domain types** (`core.log.model` + `ThinkingBlock` in `core.model`) — Define all new types. Add `ThinkingBlock` to existing `ContentBlock` sealed trait. Fix any exhaustiveness warnings.
2. **Shared content block parsing** — Extract `ContentBlockParser` from `JsonParser.parseContentBlock`. Add thinking block support. Verify existing stream-json tests still pass.
3. **Log parser** (`core.log.parsing`) — Build `ConversationLogParser` using `ContentBlockParser` for content blocks. Pure parsing for all entry types.
4. **Service traits** (`core.log`) — Define `ConversationLogIndex` and `ConversationLogReader` trait interfaces.
5. **Service implementations** (`direct.log` + `effectful.log`) — File I/O and wiring. Direct and effectful variants can be parallelized.
6. **Re-exports and integration** — Update package objects, update ARCHITECTURE.md.

**Ordering Rationale:**
- Domain types are the foundation; everything depends on them
- Content block parsing extraction is a prerequisite for the log parser
- Core parser is pure and testable in isolation; it should be solid before wiring up I/O
- Service traits define the contract before implementations fill it in
- Direct and effectful variants within each layer can be parallelized

## Documentation Requirements

- [ ] Code documentation (inline comments for complex parsing logic, PURPOSE headers on all new files)
- [ ] API documentation (scaladoc on public traits and methods)
- [ ] Architecture decision record (update ARCHITECTURE.md with log parsing section)
- [ ] Update README.md with log parsing usage examples

---

**Analysis Status:** Ready for Review

**Next Steps:**
1. Resolve CLARIFY markers with stakeholders
2. Run **wf-create-tasks** with the issue ID
3. Run **wf-implement** for layer-by-layer implementation
