# Story-Driven Analysis: Support persistent two-way conversations with a single Claude Code session

**Issue:** CC-15
**Created:** 2026-04-02
**Status:** Draft
**Classification:** Feature

## Problem Statement

Every API call currently spawns a new Claude Code CLI subprocess that runs to completion and exits. Continuing a conversation requires `--resume sessionId` or `--continue`, which starts a fresh process each turn. This means each turn pays full process startup cost, session state is serialized/deserialized between turns on the CLI side, and there is no way to maintain a live, interactive session from Scala code.

The Claude Code CLI supports `--input-format stream-json`, where JSON messages are written to stdin and JSON responses are read on stdout in a continuous loop. This feature enables a single long-lived process that handles multiple conversational turns without restart overhead.

## User Stories

### Story 1: Direct API - Basic session lifecycle (open, send, close)

```gherkin
Funkce: Interaktivni relace s Claude Code
  Jako vyvojar pouzivajici direct API
  Chci otevrit relaci, poslat zpravu a dostat odpoved
  Aby jsem nemusel platit startovaci naklady procesu pri kazdem dotazu

Scenar: Zakladni konverzace s jednou zpravou
  Pokud mam nakonfigurované SessionOptions
  Kdyz otevru novou relaci pres ClaudeCode.session
  A poslu zpravu "What files are in this project?"
  Pak dostanu Flow[Message] s odpovedi asistenta
  A po zavreni relace se podkladovy proces ukonci
```

**Estimated Effort:** 12-16h
**Complexity:** Complex

**Technical Feasibility:**
This is the core story and the hardest one. The existing ProcessManager closes stdin immediately after process start and waits for process exit. Session mode requires keeping stdin open for writing and stdout open for continuous reading, with a way to delimit response boundaries per turn. The main risk is understanding the exact stdin/stdout protocol of `--input-format stream-json` -- what JSON format stdin expects, and how responses on stdout are delimited between turns.

**Acceptance:**
- A session can be opened, a message sent, a streaming response received, and the session closed
- The underlying CLI process starts once and stays alive across send calls
- Process is terminated cleanly on close

---

### Story 2: Direct API - Multi-turn conversation

```gherkin
Funkce: Vicekolova konverzace
  Jako vyvojar pouzivajici direct API
  Chci poslat vice zprav v ramci jedne relace
  Aby Claude mel kontext predchozich odpovedi

Scenar: Dve navazujici otazky
  Pokud mam otevrenu relaci
  Kdyz poslu zpravu "What files are in this project?"
  A pockan na dokonceni odpovedi
  A poslu dalsi zpravu "Now explain the main entry point."
  Pak druha odpoved reflektuje kontext prvni otazky
  A obe odpovedi prisly ze stejneho procesu
```

**Estimated Effort:** 4-6h
**Complexity:** Moderate

**Technical Feasibility:**
Once Story 1 works, multi-turn is about correctly delimiting response boundaries. The key challenge is knowing when one turn's response is complete so the next send can proceed. This likely depends on a `ResultMessage` marking end-of-turn in the stream. Sequential-only semantics simplifies this -- no need for concurrent send coordination.

**Acceptance:**
- Multiple sequential send calls work within one session
- Each send returns a complete stream of messages for that turn
- Context is maintained across turns (CLI handles this internally)

---

### Story 3: Effectful API - Session lifecycle with Resource

```gherkin
Funkce: Efektova relace s automatickym uvolnenim
  Jako vyvojar pouzivajici cats-effect API
  Chci pouzit relaci jako Resource[IO, Session]
  Aby se proces automaticky ukoncil i pri chybe

Scenar: Relace s automatickym uvolnenim
  Pokud pouziji ClaudeCode.session(...).use
  Kdyz poslu zpravu a dostanu odpoved
  Pak se po opusteni bloku use proces automaticky ukonci
  A i pri vyjimce se proces korektne uklidi
```

**Estimated Effort:** 8-12h
**Complexity:** Complex

**Technical Feasibility:**
The effectful ProcessManager already uses `fs2.io.process.ProcessBuilder.spawn` as a `Resource`. The challenge is adapting this to keep the process alive and pipe multiple messages through stdin while reading responses from stdout as fs2 Streams. Resource semantics provide natural cleanup, but stdin writing needs careful coordination with stdout reading.

**Acceptance:**
- Session is exposed as Resource[IO, Session] or similar bracket pattern
- Process cleanup happens automatically on normal exit and on error
- send returns Stream[IO, Message] for each turn

---

### Story 4: SessionOptions configuration

```gherkin
Funkce: Konfigurace relace
  Jako vyvojar
  Chci nakonfigurovat relaci podobne jako QueryOptions
  Aby jsem mohl nastavit model, system prompt, povolene nastroje atd.

Scenar: Relace s vlastnim system promptem
  Pokud vytvorim SessionOptions se systemPrompt "You are a code reviewer"
  Kdyz otevru relaci s temito moznostmi
  Pak CLI proces dostane spravne argumenty vcetne system promptu
  A relace funguje s danou konfiguraci
```

**Estimated Effort:** 4-6h
**Complexity:** Straightforward

**Technical Feasibility:**
SessionOptions shares most fields with QueryOptions minus `prompt` (since prompts are sent per-turn). CLIArgumentBuilder needs minor adaptation to support `--input-format stream-json` and omit the trailing prompt argument. This is largely mechanical.

**Acceptance:**
- SessionOptions supports all relevant QueryOptions fields (model, systemPrompt, allowedTools, permissionMode, cwd, environment, etc.)
- CLIArgumentBuilder can build args for session mode
- prompt is NOT part of SessionOptions

---

### Story 5: Error handling - process crash and malformed JSON

```gherkin
Funkce: Zpracovani chyb behem relace
  Jako vyvojar
  Chci dostat typovane chyby kdyz se neco pokazi
  Aby jsem mohl chyby spravne osetrit

Scenar: Spadnuty proces behem relace
  Pokud mam otevrenu relaci
  Kdyz CLI proces neocekavane skonci
  Pak dalsi volani send vyhodi typovanou chybu
  A relace se oznaci jako neplatna

Scenar: Neplatny JSON na vystupu
  Pokud mam otevrenu relaci
  Kdyz CLI proces vypise neplatny JSON
  Pak chyba je zaznamenana
  A relace zustane funkcni pro dalsi zpravy
```

**Estimated Effort:** 6-8h
**Complexity:** Moderate

**Technical Feasibility:**
The existing CLIError hierarchy covers the error types needed. The main work is detecting process termination during an active session and propagating errors through the stream/Flow to the caller. Malformed JSON handling already exists in JsonParser; it just needs to work in the continuous-reading context.

**Acceptance:**
- Process crash surfaces as typed CLIError through send's return type
- Malformed JSON lines are skipped with logging (existing behavior)
- Session detects when underlying process has died
- Clear error message distinguishing session-level vs turn-level failures

---

### Story 6: Stdin message format and response delimiting

```gherkin
Funkce: Spravny format zprav na stdin
  Jako SDK
  Chci posilat zpravy ve spravnem JSON formatu na stdin procesu
  Aby CLI spravne interpretoval moje pozadavky

Scenar: Odeslani uzivatelske zpravy
  Pokud mam otevreny stream-json proces
  Kdyz zapisu JSON zpravu na stdin
  Pak CLI ji prijme a zacne streamovat odpoved
  A odpoved konci ResultMessage

Scenar: Rozpoznani konce odpovedi
  Pokud CLI streamuje odpoved
  Kdyz prijmu ResultMessage na stdout
  Pak vim ze odpoved je kompletni
  A mohu poslat dalsi zpravu
```

**Estimated Effort:** 6-8h
**Complexity:** Moderate

**Technical Feasibility:**
This is a research-heavy story. The exact stdin JSON format for `--input-format stream-json` needs investigation. The response end delimiter is likely a `ResultMessage` with `type: "result"` which the parser already handles. The risk is that the protocol may have undocumented nuances.

**Acceptance:**
- Stdin messages are written in the correct JSON format
- Response boundary is correctly detected (likely ResultMessage)
- Works with actual Claude Code CLI (integration test)

## Architectural Sketch

**Purpose:** List WHAT components each story needs, not HOW they're implemented.

### For Story 1: Direct API - Basic session lifecycle

**Domain Layer:**
- `SessionOptions` -- configuration value object (shared with Story 4)
- `Session` trait -- represents an active conversational session

**Application Layer:**
- `ClaudeCode.session(SessionOptions)` -- factory method returning a Session
- `Session.send(prompt: String): Flow[Message]` -- sends a turn, returns streaming response
- `Session.close(): Unit` -- terminates the session

**Infrastructure Layer:**
- New process management mode in direct ProcessManager that keeps stdin open
- Stdin writer component for sending JSON messages to process
- Stdout reader that continuously reads and can partition responses per turn

**Presentation Layer:**
- Extension to `works.iterative.claude.direct.ClaudeCode` with `session` method
- Re-exports in `direct` package object for Session types

---

### For Story 2: Direct API - Multi-turn conversation

**Domain Layer:**
- No new types; uses Session from Story 1

**Application Layer:**
- Turn sequencing logic in Session (ensure previous turn is complete before next send)

**Infrastructure Layer:**
- Response boundary detection (watching for ResultMessage to signal end-of-turn)
- Stdin flushing between turns

---

### For Story 3: Effectful API - Session lifecycle with Resource

**Domain Layer:**
- `Session[F[_]]` trait (or effectful-specific Session trait)

**Application Layer:**
- `ClaudeCode.session(SessionOptions): Resource[IO, Session]`
- `Session.send(prompt: String): Stream[IO, Message]`

**Infrastructure Layer:**
- Adapted effectful ProcessManager that keeps process alive
- fs2 Pipe or Stream composition for stdin writing
- Resource finalizer for process cleanup

**Presentation Layer:**
- Extension to `works.iterative.claude.effectful.ClaudeCode`

---

### For Story 4: SessionOptions configuration

**Domain Layer:**
- `SessionOptions` case class in `core.model`

**Application Layer:**
- Fluent builder methods on SessionOptions (matching QueryOptions style)

**Infrastructure Layer:**
- `CLIArgumentBuilder.buildSessionArgs(options: SessionOptions)` 
- Adds `--input-format stream-json` and `--verbose --output-format stream-json`
- Omits trailing prompt argument

---

### For Story 5: Error handling

**Domain Layer:**
- Possibly new error subtypes: `SessionTerminatedError`, `SessionSendError`

**Infrastructure Layer:**
- Process liveness check before/during send
- Error propagation through Flow/Stream when process dies mid-turn

---

### For Story 6: Stdin message format

**Domain Layer:**
- `StdinMessage` -- representation of JSON message written to CLI stdin

**Infrastructure Layer:**
- JSON encoder for stdin messages (likely circe Encoder)
- Newline-delimited JSON writing to process stdin
- Response boundary detection based on ResultMessage arrival

## Technical Decisions (Resolved)

### RESOLVED: Exact `--input-format stream-json` protocol

Investigated via the `@anthropic-ai/claude-agent-sdk` TypeScript SDK (v0.1.0).

**Stdin format** — `SDKUserMessage`:
```json
{"type": "user", "message": {"role": "user", "content": "..."}, "parent_tool_use_id": null, "session_id": "..."}
```

**Session lifecycle:**
1. CLI sends `system`/`init` message on stdout with assigned `session_id`
2. Client sends `SDKUserMessage` on stdin using that `session_id` (first message can use `"pending"`)
3. CLI responds with interleaved `stream_event`, `assistant`, and `keep_alive` messages
4. `ResultMessage` with `type: "result"` signals end-of-turn
5. Process stays alive for next turn

**Additional message types discovered:**
- `stream_event` — partial streaming events (content deltas), new type not in current model
- `keep_alive` — periodic heartbeat messages
- `control_request` / `control_response` — bidirectional control (interrupt, model switch, etc.) — out of scope for now

**CLI flags:** `--print --input-format stream-json --output-format stream-json` (all three required)

---

### RESOLVED: Session trait design — separate per API style (Option B)

Separate Session traits for direct and effectful APIs, with shared `SessionOptions` in `core.model`. This mirrors the existing `ClaudeCode` / `QueryOptions` split. `Flow` and `Stream` are fundamentally different types, making a shared trait impractical without unnecessary complexity.

---

### RESOLVED: Turn sequencing — delegate to CLI (Option C)

No client-side enforcement. The CLI supports queueing messages, so if the user calls `send` before the previous turn is fully consumed, the CLI handles it. This simplifies the implementation and avoids silently discarding messages.

---

### RESOLVED: CLI argument compatibility with session mode

- `--print` is **required** for `--input-format stream-json` (non-interactive mode prerequisite)
- `--continue` and `--resume` **are valid** in session mode (resume a previous conversation in streaming mode)
- All other flags (model, system-prompt, allowed-tools, permission-mode, max-turns, etc.) are valid as startup flags
- `SessionOptions` should include everything from `QueryOptions` **except `prompt`** (prompts are sent per-turn via stdin)

## Total Estimates

**Story Breakdown:**
- Story 1 (Direct API - Basic session lifecycle): 12-16 hours
- Story 2 (Direct API - Multi-turn conversation): 4-6 hours
- Story 3 (Effectful API - Session lifecycle): 8-12 hours
- Story 4 (SessionOptions configuration): 4-6 hours
- Story 5 (Error handling): 6-8 hours
- Story 6 (Stdin message format and response delimiting): 6-8 hours

**Total Range:** 40 - 56 hours

**Confidence:** Medium

**Reasoning:**
- Protocol is now well-understood from the TypeScript Agent SDK
- Ox Flow and fs2 Stream composition for long-lived bidirectional I/O is non-trivial
- No prior art in this codebase for keeping a process alive across multiple interactions
- Story 6 effort may decrease since protocol research is complete

## Testing Approach

**Per Story Testing:**

Each story should have:
1. **Unit Tests**
2. **Integration Tests**
3. **E2E Scenario Tests**

**Story-Specific Testing Notes:**

**Story 1 (Direct session lifecycle):**
- Unit: Session state management (open/closed/error states), turn sequencing logic
- Integration: Mock CLI script that reads stdin JSON and writes stdout JSON to verify protocol
- E2E: Real Claude Code CLI session with actual send/receive

**Story 2 (Multi-turn):**
- Unit: Response boundary detection (ResultMessage signals end-of-turn)
- Integration: Mock CLI script simulating multi-turn conversation
- E2E: Real multi-turn conversation verifying context retention

**Story 3 (Effectful session):**
- Unit: Resource cleanup semantics, error propagation
- Integration: Mock process with fs2 Process abstraction
- E2E: Real cats-effect session with Resource.use

**Story 4 (SessionOptions):**
- Unit: CLIArgumentBuilder produces correct args for session mode
- Unit: SessionOptions fluent API
- Integration: Verify CLI receives expected arguments

**Story 5 (Error handling):**
- Unit: Error type construction and message formatting
- Integration: Mock CLI that crashes mid-session, produces malformed JSON
- E2E: Verify error propagation with real CLI edge cases

**Story 6 (Stdin format):**
- Unit: JSON encoder produces correct stdin messages
- Integration: Round-trip test with mock CLI
- E2E: Verify with real Claude Code CLI

**Test Data Strategy:**
- Mock CLI scripts (bash/python) that simulate stream-json protocol for integration tests
- Extend existing `MockCliScript` pattern from direct tests
- Real Claude Code CLI for E2E tests (gated on CLI availability, like existing integration tests)

**Regression Coverage:**
- Existing query/querySync/queryResult tests must continue passing
- ProcessManager changes must not break one-shot query mode
- CLIArgumentBuilder changes must not affect existing argument building

## Deployment Considerations

### Database Changes
None -- this is a library, no persistent storage.

### Configuration Changes
- New `SessionOptions` type added to public API
- New `session` method on both ClaudeCode entry points
- No environment variable or config file changes

### Rollout Strategy
- Session API is purely additive -- existing query API unchanged
- Can ship incrementally: direct API first, effectful API second
- Feature is opt-in (users must explicitly use `session` method)

### Rollback Plan
- Remove session-related classes; existing API is unchanged
- No migration needed since this is new functionality

## Dependencies

### Prerequisites
- Claude Code CLI version that supports `--input-format stream-json`

### Story Dependencies
- Story 6 (stdin format) should be investigated first as it unblocks all others
- Story 4 (SessionOptions) is independent and can be done in parallel
- Story 1 depends on Story 4 and Story 6
- Story 2 depends on Story 1
- Story 3 depends on Story 4 and Story 6 (parallel to Story 1)
- Story 5 depends on Stories 1 and 3

### External Blockers
- Claude Code CLI must support `--input-format stream-json` in the installed version

---

## Implementation Sequence

**Recommended Story Order:**

1. **Story 6: Stdin message format and response delimiting** - Must understand the protocol before building anything
2. **Story 4: SessionOptions configuration** - Independent, provides foundation for session creation
3. **Story 1: Direct API - Basic session lifecycle** - Core functionality, validates the approach
4. **Story 2: Direct API - Multi-turn conversation** - Builds on Story 1, proves the value proposition
5. **Story 3: Effectful API - Session lifecycle** - Parallel implementation using learnings from direct API
6. **Story 5: Error handling** - Hardens both APIs

**Iteration Plan:**

- **Iteration 1** (Stories 6, 4): Protocol investigation + SessionOptions -- establishes foundation
- **Iteration 2** (Stories 1, 2): Direct API session with multi-turn -- delivers usable feature
- **Iteration 3** (Stories 3, 5): Effectful API + error hardening -- completes the feature

## Documentation Requirements

- [ ] Gherkin scenarios serve as living documentation
- [ ] API documentation (new session methods on both ClaudeCode objects)
- [ ] ARCHITECTURE.md update with session mode data flow diagram
- [ ] README.md update with session usage examples
- [ ] Domain model documentation (SessionOptions, Session trait)

---

**Analysis Status:** All CLARIFYs Resolved

**Next Steps:**
1. Run **ag-create-tasks** with the issue ID
2. Run **ag-implement** for iterative story implementation
