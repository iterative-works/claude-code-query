# Phase 05: Effectful API - Session lifecycle with Resource

**Issue:** CC-15 - Support persistent two-way conversations with a single Claude Code session
**Estimated Effort:** 8-12 hours
**Status:** Not started

## Goals

1. Expose an effectful `Session` trait where `send` returns `Stream[IO, Message]` and the session is acquired as `Resource[IO, Session]`
2. Implement session process management using `fs2.io.process.ProcessBuilder.spawn` with Resource-based cleanup
3. Add `ClaudeCode.session(SessionOptions): Resource[IO, Session]` factory to the effectful API
4. Re-export `SessionOptions` in the effectful package object
5. Validate multi-turn conversation support from the start (lessons from Phase 4)
6. Provide full test coverage: unit, integration, and E2E

## Scope

### In scope

- `Session` trait in `effectful` package with `send(prompt: String): Stream[IO, Message]` and `sessionId: IO[String]`
- Internal `SessionProcess` implementation using `fs2.io.process.Process[IO]` from `ProcessBuilder.spawn`
- `Resource[IO, Session]` lifecycle: process start in acquire, process kill + stdin close in release
- Stdin writing via `process.stdin` pipe (write SDKUserMessage JSON, flush)
- Stdout reading via `process.stdout` pipe, parsed line-by-line through the effectful `JsonParser`, terminated at `ResultMessage`
- Stderr capture as a background fiber (matching the existing effectful `ProcessManager` pattern)
- Session ID extraction from init `SystemMessage` and update from each `ResultMessage`
- Multi-turn support: sequential `send` calls reuse the same process, each returning its own `Stream[IO, Message]`
- `ClaudeCode.session(SessionOptions)(using Logger[IO]): Resource[IO, Session]` factory method
- `SessionOptions` re-export in `effectful/package.scala`

### Out of scope

- Concurrent `send` calls (per RESOLVED decision: no client-side enforcement, CLI handles queueing)
- Turn-level timeouts (can be composed by caller via `stream.timeout`)
- Error handling for process crashes mid-conversation (Phase 06)
- `--continue` / `--resume` validation for session mode
- Control messages (`control_request` / `control_response`)

## Dependencies

**Prior phases delivered:**

- **Phase 01:** `SDKUserMessage` with circe `Encoder` in `core/src/.../model/SDKUserMessage.scala` -- ready for stdin writing. `KeepAliveMessage`, `StreamEventMessage` types and parser branches in core `JsonParser`. `ResultMessage` confirmed as end-of-turn delimiter.
- **Phase 02:** `SessionOptions` in `core/src/.../model/SessionOptions.scala` with fluent builders. `CLIArgumentBuilder.buildSessionArgs` producing `--print --input-format stream-json --output-format stream-json` plus all option flags.
- **Phase 03:** Direct `Session` trait and `SessionProcess` implementation -- reference for session lifecycle, init message reading, stdin/stdout protocol, stderr capture, close semantics.
- **Phase 04:** Multi-turn test patterns -- `SessionMockCliScript` with `TurnResponse` cycling, stdin capture verification, context-dependent E2E tests. Key lesson: the infrastructure from Phase 3 handled multi-turn correctly without code changes; thorough testing is what matters.

**Existing effectful patterns to follow:**

- `effectful/src/.../ClaudeCode.scala` -- query methods returning `Stream[IO, Message]`, CLI discovery, argument building, configuration validation
- `effectful/src/.../internal/cli/ProcessManager.scala` -- `ProcessBuilder.spawn[IO]` as `Resource`, stderr capture via fiber, stdout line parsing through effectful `JsonParser`
- `effectful/src/.../internal/parsing/JsonParser.scala` -- wraps core `JsonParser` with IO effects and logging
- `effectful/src/.../package.scala` -- type/value re-exports for single-import usage
- `effectful/test/.../internal/testing/MockScriptResource.scala` -- classpath-based mock script extraction

## Approach

### 1. Create the effectful Session trait

Define `Session` in `effectful/src/works/iterative/claude/effectful/Session.scala`:

```scala
trait Session:
  def send(prompt: String): Stream[IO, Message]
  def sessionId: IO[String]
```

Key differences from the direct `Session`:
- `send` returns `Stream[IO, Message]` instead of `Flow[Message]`
- `sessionId` returns `IO[String]` instead of `String` (since reading a `Ref` is an effect)
- No `close()` method -- cleanup is handled by the `Resource` finalizer

### 2. Implement SessionProcess

Create `effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala`:

- Use `ProcessBuilder(executablePath, args).spawn[IO]` to get `Resource[IO, Process[IO]]`
- In the Resource acquire phase:
  1. Spawn the process
  2. Start a background fiber for stderr capture (same pattern as existing `ProcessManager.captureStderr`)
  3. Read init messages from stdout to extract session ID into a `Ref[IO, String]`
  4. Return the `Session` instance
- In the Resource release phase:
  1. Write EOF to stdin (close the stdin pipe)
  2. Wait briefly for process exit
  3. Cancel the stderr fiber
- `send` implementation:
  1. Encode `SDKUserMessage` to JSON using circe
  2. Write JSON + newline to `process.stdin` via `Stream.emit(bytes).through(process.stdin).compile.drain`
  3. Return a `Stream` that reads `process.stdout`, decodes UTF-8, splits lines, parses via effectful `JsonParser`, and completes when a `ResultMessage` is emitted (using `takeThrough` or similar)
  4. Update the session ID `Ref` when `ResultMessage` is encountered

Key implementation detail: stdout must be read as a continuous stream across turns. The `send` method should not re-create the stdout stream each time. Instead, use a shared `Queue[IO, Option[String]]` or similar mechanism where a single background fiber reads stdout lines into the queue, and each `send` call pulls from the queue until it sees a `ResultMessage`. Alternatively, use `fs2.concurrent.Topic` or simply rely on the process stdout pipe being consumed incrementally across `send` calls.

The simplest approach: keep a `Ref` to the current state of the stdout byte stream. Each `send` call reads lines from it until `ResultMessage`. Since sends are sequential, there is no contention.

### 3. Add factory method to ClaudeCode

Add to `effectful/src/works/iterative/claude/effectful/ClaudeCode.scala`:

```scala
def session(options: SessionOptions)(using logger: Logger[IO]): Resource[IO, Session] =
  // validate config, discover executable, build args, delegate to SessionProcess
```

Follow the same pattern as `query`: validate configuration, discover executable path, build CLI arguments, then delegate to `SessionProcess.start`.

### 4. Update package re-exports

Add `SessionOptions` type and value aliases to `effectful/package.scala`, matching the existing re-export pattern.

### 5. Create mock session script infrastructure for effectful tests

Port or share `SessionMockCliScript` from the direct module. Since the script generation is pure (it creates bash scripts on disk), consider either:
- Extracting `SessionMockCliScript` to a shared test-support location, or
- Creating a thin effectful wrapper that creates scripts as `Resource[IO, Path]` for automatic cleanup

### 6. Write tests at all three levels

Follow the test patterns established in Phases 3-4, adapted for cats-effect and fs2.

## Files to Modify/Create

### New files

- `effectful/src/works/iterative/claude/effectful/Session.scala` -- Session trait
- `effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala` -- Process-backed implementation
- `effectful/test/src/works/iterative/claude/effectful/SessionTest.scala` -- Unit tests
- `effectful/test/src/works/iterative/claude/effectful/SessionIntegrationTest.scala` -- Integration tests with mock scripts
- `effectful/test/src/works/iterative/claude/effectful/SessionE2ETest.scala` -- E2E tests with real CLI
- `effectful/test/src/works/iterative/claude/effectful/internal/testing/SessionMockCliScript.scala` -- Mock script utilities (or shared from direct module)

### Files to modify

- `effectful/src/works/iterative/claude/effectful/ClaudeCode.scala` -- add `session` factory method
- `effectful/src/works/iterative/claude/effectful/package.scala` -- add `SessionOptions` re-export

### Reference files (no changes)

- `core/src/works/iterative/claude/core/model/SessionOptions.scala` -- session configuration
- `core/src/works/iterative/claude/core/model/SDKUserMessage.scala` -- stdin message type with Encoder
- `core/src/works/iterative/claude/core/model/Message.scala` -- message hierarchy including ResultMessage, KeepAliveMessage, StreamEventMessage
- `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala` -- `buildSessionArgs` for CLI flags
- `core/src/works/iterative/claude/core/parsing/JsonParser.scala` -- core pure parsing logic
- `effectful/src/works/iterative/claude/effectful/internal/parsing/JsonParser.scala` -- effectful parsing wrapper
- `effectful/src/works/iterative/claude/effectful/internal/cli/ProcessManager.scala` -- reference for fs2 process patterns
- `effectful/src/works/iterative/claude/effectful/internal/cli/CLIDiscovery.scala` -- executable path resolution
- `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` -- reference implementation for session protocol
- `direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala` -- reference for mock script generation

## Testing Strategy

### Unit tests (`SessionTest.scala`)

1. **SDKUserMessage encoding for send** -- verify the JSON written to stdin matches the protocol format
2. **Session ID defaults to "pending"** -- verify initial session ID before any send
3. **Session ID extracted from init message** -- verify session ID updates from init SystemMessage
4. **Session ID updates from ResultMessage** -- verify session ID updates after send completes
5. **send returns messages up to and including ResultMessage** -- verify stream terminates correctly at end-of-turn
6. **KeepAlive and StreamEvent pass through** -- verify non-terminal message types are emitted
7. **Two sequential sends return isolated streams** -- verify messages from turn 1 do not bleed into turn 2
8. **Session ID propagates from first turn to second send** -- verify the second SDKUserMessage uses the updated session ID
9. **Three-turn cycling** -- verify three sequential sends work correctly

### Integration tests (`SessionIntegrationTest.scala`)

1. **Full session lifecycle with Resource** -- acquire session, send one message, verify response, release (process cleanup)
2. **Resource cleanup on normal exit** -- verify process is terminated after Resource.use completes
3. **Resource cleanup on error** -- verify process is terminated when an exception occurs inside Resource.use
4. **Stdin JSON verification** -- use captureStdinFile to verify SDKUserMessage JSON format
5. **Session ID from init message** -- verify session ID is set from the mock init message
6. **Two-turn lifecycle** -- send two prompts with different configured responses, verify correct mapping
7. **Session ID progression across turns** -- verify stdin capture shows correct session ID in each SDKUserMessage
8. **Variable message counts per turn** -- turn 1 with 2 messages, turn 2 with 4 messages (including keepalive/stream_event)
9. **ClaudeCode.session factory** -- verify the factory method produces a working session Resource

### E2E tests (`SessionE2ETest.scala`)

1. **Single-turn session with real CLI** -- gated on CLI availability; open session, send one prompt, verify AssistantMessage and ResultMessage in response stream, verify Resource cleanup
2. **Multi-turn with context dependency** -- send "Remember the number 42" then "What number did I ask you to remember?", verify the second response references 42
3. **Session ID is valid after real CLI interaction** -- verify session ID is non-empty and not "pending" after a send

## Acceptance Criteria

1. `Session` trait exists in `effectful` package with `send(prompt: String): Stream[IO, Message]` and `sessionId: IO[String]`
2. `ClaudeCode.session(SessionOptions)(using Logger[IO]): Resource[IO, Session]` factory method works
3. Resource acquire starts the CLI process; Resource release terminates it
4. Process cleanup happens on both normal exit and error (Resource finalizer)
5. `send` returns a `Stream[IO, Message]` that completes after the turn's `ResultMessage`
6. Multiple sequential `send` calls within one session each return their own complete stream
7. Session ID is extracted from init message and updated from each ResultMessage
8. Messages from one turn do not appear in another turn's stream
9. `SessionOptions` is re-exported in the effectful package object
10. All unit, integration, and E2E tests pass with no compilation warnings
11. All existing effectful query tests continue to pass (no regressions)
12. All existing direct session tests continue to pass (no regressions)
