# Phase 03: Direct API - Basic session lifecycle

**Issue:** CC-15 - Support persistent two-way conversations with a single Claude Code session
**Estimated Effort:** 12-16 hours
**Status:** Not started

## Goals

1. Create a `Session` trait in the direct module representing an active conversational session with `send` and `close` methods
2. Add a `ClaudeCode.session(SessionOptions)` factory method (both instance and companion object variants) that starts a CLI process and returns a `Session`
3. Implement `Session.send(prompt: String): Flow[Message]` that writes an `SDKUserMessage` to stdin and returns a streaming `Flow[Message]` terminating at `ResultMessage`
4. Implement `Session.close(): Unit` that terminates the underlying CLI process cleanly
5. The CLI process starts once at session creation and stays alive across `send` calls
6. Extract session ID from the CLI's initial `SystemMessage` (subtype `init`) for use in subsequent `SDKUserMessage` payloads

## Scope

### In scope

- `Session` trait with `send(prompt: String): Flow[Message]` and `close(): Unit`
- `SessionProcess` (or similar internal implementation) managing the long-lived CLI process, its stdin writer, and its stdout reader
- `ClaudeCode.session(SessionOptions): Session` factory method on the class (requires `Ox`, `Logger`)
- `ClaudeCode.session(SessionOptions)(using Logger): Session` blocking factory on the companion object
- Writing `SDKUserMessage` JSON to process stdin (newline-delimited)
- Reading stdout lines, parsing via `JsonParser`, and emitting as `Flow[Message]`
- Detecting `ResultMessage` as end-of-turn delimiter to complete the `Flow`
- Extracting `session_id` from the initial `SystemMessage` for use in `SDKUserMessage`
- Using `"pending"` as the session ID for the first `send` if no init message arrives before first send
- Process cleanup on `close()` (close stdin, wait briefly, destroy if needed)
- Re-exporting `Session` and `SessionOptions` in `direct` package object
- Mock CLI scripts for integration testing that simulate the stream-json protocol (read stdin, write stdout)

### Out of scope

- Turn sequencing enforcement (Phase 04 adds multi-turn; Phase 03 only needs single-turn to work, but does not prevent multiple sends)
- Client-side concurrency guards on `send` (per RESOLVED decision: delegate to CLI)
- Effectful API session (Phase 05)
- Error handling for process crashes mid-session (Phase 06 hardens error paths)
- `control_request` / `control_response` messages
- Timeout on individual `send` calls (session-level timeout can be added later)

## Dependencies

**Phase 01 delivered:**
- `SDKUserMessage` case class with circe `Encoder` at `core/src/works/iterative/claude/core/model/SDKUserMessage.scala`
- `KeepAliveMessage` and `StreamEventMessage` types in `Message` hierarchy at `core/src/works/iterative/claude/core/model/Message.scala`
- `ResultMessage` confirmed as end-of-turn delimiter
- `JsonParser.parseMessage` handles all message types including `keep_alive` and `stream_event`

**Phase 02 delivered:**
- `SessionOptions` case class at `core/src/works/iterative/claude/core/model/SessionOptions.scala`
- `CLIArgumentBuilder.buildSessionArgs(options: SessionOptions): List[String]` at `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala`
- Produces correct CLI flags including `--print --input-format stream-json --output-format stream-json`

## Approach

### 1. Define the `Session` trait

Create `direct/src/works/iterative/claude/direct/Session.scala`.

```scala
trait Session:
  def send(prompt: String): Flow[Message]
  def close(): Unit
  def sessionId: String
```

- `send` writes an `SDKUserMessage` to stdin and returns a `Flow[Message]` that emits messages until a `ResultMessage` is received
- `close` shuts down the underlying process
- `sessionId` exposes the session ID assigned by the CLI (or `"pending"` before the first init message)

### 2. Implement `SessionProcess` (internal)

Create `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala`.

This is the core implementation managing the long-lived process. Key design:

**Process lifecycle:**
- Constructor starts the CLI process using `ProcessBuilder` with args from `CLIArgumentBuilder.buildSessionArgs`
- Reuse the existing `ProcessManager.configureProcess` pattern for setting cwd, environment, etc. — but note that `configureProcess` currently takes `QueryOptions`. Create a new overload or extract a helper that takes the common fields (executable path, args, cwd, inheritEnvironment, environmentVariables). The simplest approach: create a `configureSessionProcess` method that takes `SessionOptions` directly, duplicating the small amount of process builder configuration.
- stdin is kept open (do NOT close it like `ProcessManager` does for queries)
- stdout is read via a `BufferedReader` stored as a field
- stderr is captured in a background fork (reuse pattern from `ProcessManager.captureStderrStream`)

**Session ID extraction:**
- After process starts, read stdout lines until a `SystemMessage` with subtype `"init"` arrives
- Extract `session_id` from the init message's `data` map
- Store the session ID in a `@volatile var` (or `AtomicReference`) for thread safety
- If no init message arrives before the first `send`, use `"pending"`

**`send` implementation:**
- Construct an `SDKUserMessage(content = prompt, sessionId = currentSessionId)`
- Encode to JSON using the circe `Encoder`, append newline, write to stdin, flush
- Return a `Flow.usingEmit` that reads stdout lines, parses them via `JsonParser.parseJsonLineWithContextWithLogging`, and emits parsed `Message` values
- When a `ResultMessage` is encountered, emit it and then stop (complete the Flow)
- Update stored `sessionId` from the `ResultMessage.sessionId` field (it may differ from the init one)
- `KeepAliveMessage` and `StreamEventMessage` are emitted like any other message — the caller decides what to do with them

**`close` implementation:**
- Close the stdin `OutputStream`
- Wait briefly for the process to exit (e.g., `process.waitFor(5, TimeUnit.SECONDS)`)
- If still alive, call `process.destroyForcibly()`
- Close the stdout reader

### 3. Add `ClaudeCode.session` factory methods

Modify `direct/src/works/iterative/claude/direct/ClaudeCode.scala`.

**Instance method** (within supervised scope):
```scala
def session(options: SessionOptions): Session =
  ClaudeCode.createSession(options)
```

**Companion object method:**
```scala
def session(options: SessionOptions)(using Logger, Ox): Session
```

The factory method requires `(using Logger, Ox)` because the session's background forks (stderr capture) need a supervised scope. The caller must provide the `Ox` scope (e.g., inside `supervised { ... }`), since the session must outlive the factory call.

- **Instance method:** `def session(options: SessionOptions): Session` — uses the existing `Ox` scope from the class constructor
- **Companion object:** `def session(options: SessionOptions)(using Logger, Ox): Session` — requires the caller to provide an `Ox` scope

Validate the session options (cwd exists) using the same `validateWorkingDirectory` logic.

Resolve the executable path using the same `resolveClaudeExecutablePath` logic, adapted to read from `SessionOptions` fields.

Build CLI args via `CLIArgumentBuilder.buildSessionArgs(options)`.

Construct and return a `SessionProcess` instance.

### 4. Wire up process startup in `SessionProcess`

The constructor or a factory method should:
1. Build args: `CLIArgumentBuilder.buildSessionArgs(options)`
2. Resolve executable: `options.pathToClaudeCodeExecutable.getOrElse(CLIDiscovery.findClaude.getOrElse(throw ...))`
3. Create `ProcessBuilder` with `(executablePath :: args).asJava`
4. Set cwd from `options.cwd`
5. Configure environment from `options.inheritEnvironment` / `options.environmentVariables`
6. Start process
7. Fork stderr capture in background
8. Read initial messages from stdout to extract session ID
9. Return ready-to-use `Session`

### 5. Re-export types in package object

Add to `direct/src/works/iterative/claude/direct/package.scala`:
```scala
type Session = works.iterative.claude.direct.Session
type SessionOptions = works.iterative.claude.core.model.SessionOptions
val SessionOptions = works.iterative.claude.core.model.SessionOptions
```

### 6. Write tests (TDD)

Follow TDD: write failing test first, then implement.

**Order of implementation via TDD cycles:**

1. Test: `Session` trait compiles with expected method signatures → Implement trait
2. Test: `SessionProcess` can start a mock CLI process and extract session ID from init message → Implement process startup + init reading
3. Test: `send` writes correct JSON to stdin → Implement stdin writing
4. Test: `send` returns Flow that emits parsed messages up to ResultMessage → Implement stdout reading + Flow emission
5. Test: `close` terminates the process → Implement close logic
6. Test: `ClaudeCode.session` factory creates a working Session → Wire up factory
7. Integration test: full send/receive cycle with mock CLI script
8. E2E test: real CLI session (gated on CLI availability)

## Files to Modify/Create

### New files

- `direct/src/works/iterative/claude/direct/Session.scala` — Session trait definition
- `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` — internal implementation of Session backed by a long-lived CLI process
- `direct/test/src/works/iterative/claude/direct/SessionTest.scala` — unit tests for Session trait and SessionProcess
- `direct/test/src/works/iterative/claude/direct/SessionIntegrationTest.scala` — integration tests with mock CLI scripts
- `direct/test/src/works/iterative/claude/direct/SessionE2ETest.scala` — E2E tests with real CLI (gated on availability)
- `direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala` — mock CLI script generator for session protocol (reads stdin JSON, writes stdout JSON responses)

### Files to modify

- `direct/src/works/iterative/claude/direct/ClaudeCode.scala` — add `session(SessionOptions): Session` factory methods
- `direct/src/works/iterative/claude/direct/package.scala` — add `Session` and `SessionOptions` re-exports

### Reference files (no changes)

- `core/src/works/iterative/claude/core/model/SDKUserMessage.scala` — stdin message format and Encoder
- `core/src/works/iterative/claude/core/model/SessionOptions.scala` — session configuration
- `core/src/works/iterative/claude/core/model/Message.scala` — Message hierarchy (ResultMessage as delimiter)
- `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala` — `buildSessionArgs` for CLI flag generation
- `core/src/works/iterative/claude/core/parsing/JsonParser.scala` — message parsing logic
- `direct/src/works/iterative/claude/direct/internal/cli/ProcessManager.scala` — reference for process management patterns (configureProcess, stderr capture, stdout reading)
- `direct/src/works/iterative/claude/direct/internal/parsing/JsonParser.scala` — direct-style JSON parsing with logging
- `direct/src/works/iterative/claude/direct/Logger.scala` — Logger trait
- `direct/src/works/iterative/claude/direct/internal/testing/MockCliScript.scala` — existing mock CLI script patterns
- `direct/src/works/iterative/claude/direct/internal/testing/TestConstants.scala` — test constants
- `direct/test/src/works/iterative/claude/direct/ClaudeCodeIntegrationTest.scala` — reference for integration test patterns

## Testing Strategy

### Unit tests (`SessionTest.scala`)

1. **Session trait has expected method signatures** — compile-time verification that `send` returns `Flow[Message]` and `close` returns `Unit`
2. **SDKUserMessage is correctly encoded for stdin** — verify JSON output matches expected format with session ID and content
3. **ResultMessage signals end of Flow** — given a sequence of messages including a ResultMessage, verify the Flow emits up to and including ResultMessage then completes
4. **Session ID is extracted from SystemMessage init** — given a SystemMessage with subtype "init" and session_id in data, verify extraction
5. **Session ID defaults to "pending" when no init received** — verify fallback behavior
6. **Session ID is updated from ResultMessage** — after a send completes, verify sessionId reflects the ResultMessage's sessionId
7. **Close terminates process** — verify that close destroys the process

### Integration tests (`SessionIntegrationTest.scala`)

1. **Full single-turn session lifecycle** — start session with mock CLI, send one message, receive streaming response ending with ResultMessage, close session, verify process exited
2. **Mock CLI receives correct stdin JSON** — mock CLI script captures stdin input to a temp file; verify the written JSON matches `SDKUserMessage` format
3. **Session extracts session ID from init message** — mock CLI outputs a system/init message with session_id; verify `session.sessionId` returns it
4. **KeepAlive and StreamEvent messages are emitted in Flow** — mock CLI outputs keep_alive and stream_event messages interleaved with assistant messages; verify all appear in the collected Flow
5. **ClaudeCode.session factory creates working session** — use `ClaudeCode.session(options)` to create session, send a message, verify response

### E2E tests (`SessionE2ETest.scala`)

1. **Real CLI session with single turn** — gated on CLI availability (same pattern as `ClaudeCodeIntegrationTest`); open session, send "What is 1+1?", verify response contains assistant message and ResultMessage, close session
2. **Session ID is a valid non-pending value after first turn** — after completing a turn with real CLI, verify `session.sessionId` is not "pending"

## Acceptance Criteria

1. `Session` trait exists at `works.iterative.claude.direct.Session` with `send(prompt: String): Flow[Message]`, `close(): Unit`, and `sessionId: String`
2. `ClaudeCode.session(SessionOptions): Session` factory method exists on the `ClaudeCode` class (requires `Ox`, `Logger`)
3. `ClaudeCode.session(SessionOptions)(using Logger, Ox): Session` factory method exists on the `ClaudeCode` companion object
4. `send` writes a correctly-formatted `SDKUserMessage` JSON line to the process stdin
5. `send` returns a `Flow[Message]` that streams parsed messages from stdout and completes after emitting a `ResultMessage`
6. The underlying CLI process starts once at session creation and remains alive after `send` completes
7. `close` terminates the CLI process (stdin closed, process destroyed if necessary)
8. Session ID is extracted from the CLI's initial `SystemMessage` and used in `SDKUserMessage` payloads
9. `Session` and `SessionOptions` are re-exported in the `direct` package object
10. All new files start with the required two-line PURPOSE comments
11. All unit, integration, and E2E tests pass with no compilation warnings
12. All existing tests continue to pass (no regressions)
13. Mock CLI script for session testing reads stdin and writes stdout, simulating the stream-json protocol
