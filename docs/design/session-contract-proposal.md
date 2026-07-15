# claude-code-query — public contract proposal

**Status:** design proposal (no code). Grounded in the PROC-603 incident and a survey of every
production call site in `procedures`. All claims verified against source; citations are `file:line`
at today's state of both repos (library `~/Devel/iw/claude-code-query` @ 0.4.1-era main; consumer
`/home/mph/ops/procedures-PROC-603` branch PROC-603, post-fix).

---

## 0. Verification of the framing

I checked every specific claim before designing. All four findings hold, plus two more of the same
species:

1. **`messageQueue` is a `Queue`, not a `Hub`** — confirmed. `Queue.unbounded[Option[Message]]` at
   `zio/src/.../internal/cli/SessionProcess.scala:40`, consumed via `ZStream.fromQueue` at `:238`.
   Competing consumers would silently split the stream; nothing documents the
   exactly-one-consumer-at-a-time constraint. (The `direct` and `effectful` modules have the same
   shape: `direct/Session.scala:18,25`, `effectful/Session.scala:20,28`.)
2. **`maxChunkSize = 1` is load-bearing** — confirmed, `SessionProcess.scala:233–238` with the
   explicit comment about `takeUntil` over-pulling. Looping `stream` is lossless only because of it.
3. **The parser destroys data** — confirmed, `core/src/.../parsing/JsonParser.scala`:
   `parseResultMessage` (`:78–101`) never reads `origin` (the sole protocol-level in-turn/out-of-turn
   discriminator, proven by the PROC-603 probe — `analysis.md:185–189`); `parseAssistantMessage`
   (`:37–46`) drops `parent_tool_use_id` (subagent chatter renders as main-agent output —
   phase-01-context.md §5); `parseMessage` ends in `case _ => None` (`:31`), silently discarding
   unknown message types. **Bonus destruction:** `extractUsageData` (`:103–109`) parses `usage` to
   `Map.empty` — literally "Simplified for now"; token counts never survive.
4. **`core/log` is the better-designed sibling** — confirmed. `ConversationLogReader[F[_]]`
   (`core/src/.../log/ConversationLogReader.scala`), a rich `LogEntryPayload` ADT with
   **`RawLogEntry(entryType, json)`** as a lossless catch-all
   (`core/src/.../log/model/LogEntryPayload.scala:53–56`), plus
   `ClaudeProjects`/`ProjectPathEncoder`/`ConversationLogIndex` to locate transcripts. **The
   dashboard uses none of it today** (zero imports outside the library itself).

Two framing corrections (flagged, per your ask, §7):

- **"ZIO + a tagless core" undersells the cost surface — but less than I first claimed.** `core` is
  effect-free; tagless appears only in the `log` readers. There are **three parallel effect
  modules** — `direct` (Ox), `effectful` (cats-effect/fs2), `zio` — each with its **own `Session`
  and its own `SessionProcess`**, so a *Session* redesign is 3× unless sequenced (§4). But the
  *parser* is not: all three module parsers **delegate** to `CoreJsonParser.parseMessage`
  (`direct/.../internal/parsing/JsonParser.scala:67,79`; `effectful/:15,30`; `zio/:14,28`). §2.1's
  "parse totally, destroy nothing" therefore lands in **one** implementation and all four modules
  inherit it. An earlier draft of this section priced that as 3×; the count in §4 corrects it.
- **"The turn result is load-bearing in far fewer places than it appears" is half-right.** True:
  `WorkerReactiveTurnRunner` discards it (`dashboard/src/events/WorkerReactiveTurnRunner.scala:156`
  `onMessage = _ => ZIO.unit`, `:164` `.unit`). But the *completion signal* and two of its fields
  are load-bearing in every driver: `AgentPipeline.execute` persists `resultText` + `sessionId`
  after awaiting it (`dashboard/src/chat/AgentPipeline.scala:74–83`); `WorkerOpenEndpoints`
  extracts `sessionId` from it (`:137`); the chat `done` SSE event carries `resultText`
  (`dashboard/src/chat/AgentChatRunner.scala:66–75`).

  > **SUPERSEDED 2026-07-15.** This bullet originally concluded *"a turn should exist in the
  > contract — as a completion future the library correlates."* **Probes #7/#8 falsified the
  > premise:** the CLI merges a mid-turn send into the running turn and emits **one** result for two
  > sends, so a per-send completion future is not merely unnecessary — it is **unimplementable**,
  > and would hang. What survives is the weaker, true half: **a completion *signal* is load-bearing;
  > per-send *correlation* is a fiction.** §2.2 is rebuilt on that distinction. The consumers listed
  > above still need to know "done"; none of them can be told "done *with your message*."

---

## 1. Consumer inventory — what is actually consumed

| Consumer | Library surface used | What it actually needs |
|---|---|---|
| `WorkerAgentSessions` (`dashboard/src/agent/WorkerAgentSessions.scala:603`) | `ClaudeCode.session`, `Session.send`/`.stream`, `CLIError` | One warm process per worker; a completion signal (it *believes* it needs per-send completion — probe #7 proves that unobtainable, §2.2.0); **out-of-turn output routed elsewhere**; deterministic death signal (it hand-rolls `death: Promise` at `:174,271`); eviction-safe teardown. ~350 of its ~620 lines (readerLoop/routeMessage/bufferOutOfTurn/outOfTurnConsumerLoop/awaitTurnBatch + their hazard essays, `:221–440`) exist **only** to impose a correct contract over `Session.stream`. |
| `ClaudeCodeAgentSession` (`dashboard/src/agent/ClaudeCodeAgentSession.scala:105–113`) | `ClaudeCode.query(opts).tap(onMessage).runCollect` | One-shot turn: stream deltas to a callback, then the final `ResultMessage` (captured via a `var` at `:45–52` — the awkwardness is the contract's fault). Resume-fallback keys off `ProcessExecutionError.stderr` text matching (`:178–181`) — stringly error taxonomy. |
| `ClaudeCodeWorkerSdkSession` (`dashboard/src/agent/ClaudeCodeWorkerSdkSession.scala:39–57`) | via port | Bootstrap turn → needs only `sessionId` from the result. |
| `WorkerReactiveTurnRunner` (`:141–172`) | via `workerSessions.portFor` | Fire a synthetic prompt, await completion, discard content. Persists **only** the assistant reply via `AssistantOnlyMessageStorage`. |
| `WorkerChatEndpoints` (`:174–213`) / `WorkerOpenEndpoints` (`:108–138`) | via port + `AgentChatRunner` | Per-message deltas for SSE; completion event; the out-of-turn live channel (`WorkerOutOfTurnDelivery.scala:39–67`) consumes `(NodeId, AgentResponse)` batches. |
| `EventSessionCoordinator` (`:155–165`), `HeartbeatSessionFactory` (`:103–111`) | via port (query path) | Headless one-shot turns; discard deltas. |
| `AgentPipeline` (`:24–31,57`) | none directly — **but it defines the enrichment problem** | `wrapInteractive` + `enrichWithContext` are ad-hoc string concat; `<reactive-input>` (`WorkerReactiveTurnRunner:96–101`) and `<matter-instructions>` (`:92`) are the same pattern; `promptSafe` (`:126–129`) exists *because* the encoding is injectable. |
| `FileMessageStorage` (`dashboard/src/storage/FileMessageStorage.scala`) | none — **it is the second copy** | The attributed transcript copy the incident corrupted. Keyed on ms timestamps (`:127–128`), tiebreak `:68–75`. |
| `MessageConverter` (`dashboard/src/agent/MessageConverter.scala:28–66`) | full `Message` ADT | The SDK-type quarantine. Drops ToolResult/Thinking; **cannot** distinguish subagent chatter (no `parentToolUseId` upstream). |

Not a consumer: the gladys/gladys-offline profiles drive the `claude` CLI directly via `start.sh`;
the harness exercises the library only through the dashboard.

**Every proposed method below traces to a row of this table.**

---

## 2. The proposed contract

### 2.1 Core model: parse totally, destroy nothing (`core`)

The rule `core/log` already follows, applied to the live parser: **unknown data survives; known
data is complete.**

#### 2.1.0 How much is destroyed — measured, not asserted

Probe #2's `raw_stdout.jsonl` (22 messages, one send, one background Task) settles the scale. **The
`result` message carries 21 fields on the wire; the library models 9.** Wire keys:

```
api_error_status, duration_api_ms, duration_ms, fast_mode_state, is_error, modelUsage,
num_turns, origin, permission_denials, result, session_id, stop_reason, subtype,
terminal_reason, time_to_request_ms, total_cost_usd, ttft_ms, ttft_stream_ms, type, usage, uuid
```

Destroyed today: `origin`, `uuid`, `stop_reason`, `terminal_reason`, `api_error_status`,
`permission_denials`, `modelUsage`, `ttft_ms`, `ttft_stream_ms`, `time_to_request_ms`,
`fast_mode_state`. Two stand out:

- **`uuid`** — the live↔transcript join key. Established, not speculative (§2.2.2).
- **`permission_denials`** — *potentially* significant, **pending probe #6**. Its name suggests what
  the agent tried to do and was refused, which would be exactly the signal ADR 0007 (the
  approval/autonomy boundary) and ADR 0009 (proposal calibration: "rejected and parked commands are
  first-class signal") are built around, and which we have never been able to observe. **But I have
  only ever seen it as `[]`** — on both results, in a probe with no denials — so I do not know what
  populates it or what shape a populated entry takes. Recorded as a lead, not as a load-bearing
  argument. **The case for §2.1 does not rest on it** and should not be written down as if it does.

**The argument stands without that field, on whole message types being silently discarded** — not
just fields. The probe's stdout contains
`rate_limit_event` and system subtypes `hook_started`, `hook_response`, `background_tasks_changed`,
`task_started`, `task_progress`, `task_updated`, `task_notification`. `rate_limit_event` matches no
case in `parseMessage` and hits `case _ => None` (`JsonParser.scala:31`) — **it is dropped before
anything can see it.** That is not a hypothetical: it is a live demonstration of the flaw, in a
22-message sample. `UnknownMessage` is what stops it.

Two protocol details worth encoding precisely:

- **`origin` is key-absent on in-turn results, not `null`** (verified across 3 in-turn samples: this
  probe, probe #1, the original incident probe; vs 2 out-of-turn carrying
  `{"kind":"task-notification"}`). Absence is meaningful → `Option[ResultOrigin]` is the correct
  shape, and the decoder must distinguish absent from null.
- **`ResultOrigin` stays open** (`Other(kind: String)`): we have sampled **one** `kind`, not
  enumerated the space. `RawLogEntry` doctrine applies to enums too.
- **`system/task_notification` arrives at t=40.28, before the out-of-turn result at t=42.58** — a
  *second*, earlier signal of the same event. Not proposed as the discriminator (`origin` on the
  result is more direct and structurally attached), but worth modelling rather than dropping, and a
  possible future cross-check.

```scala
// core.model — changed/added types (breaking)
opaque type MessageId = String            // NEW — the vendor `uuid`, on EVERY message (§2.2.2)

case class AssistantMessage(
    content: List[ContentBlock],
    id: MessageId,                        // NEW — vendor uuid; live↔transcript join key
    parentToolUseId: Option[String],      // NEW — subagent axis AND sidechain join key (§2.4)
    model: Option[String]                 // NEW — present on the wire, currently dropped
) extends Message

case class ResultMessage(
    subtype: String, durationMs: Int, durationApiMs: Int, isError: Boolean,
    numTurns: Int, sessionId: String,
    id: MessageId,                        // NEW — vendor uuid (stream-only; see §2.4)
    totalCostUsd: Option[Double],
    usage: Option[TokenUsage],            // CHANGED — real TokenUsage (reuse log model), not Map.empty
    result: Option[String],
    origin: Option[ResultOrigin],         // NEW — the discriminator; ABSENT (not null) when in-turn
    stopReason: Option[String],           // NEW
    terminalReason: Option[String],       // NEW
    permissionDenials: List[PermissionDenial],  // NEW — the ADR 0007 signal (§2.1.0)
    apiErrorStatus: Option[String],       // NEW
    timings: ResultTimings                // NEW — ttftMs, ttftStreamMs, timeToRequestMs
) extends Message

enum ResultOrigin:
  case TaskNotification                   // "origin": {"kind": "task-notification"}
  case Other(kind: String)                // open — we have sampled ONE kind, not enumerated them

case class UnknownMessage(messageType: String, json: Json) extends Message
// parseMessage: no more `case _ => None` — unknown types become UnknownMessage,
// exactly RawLogEntry's move. Only blank/non-JSON lines yield None.
// This alone rescues `rate_limit_event` + 7 system subtypes observed in a 22-message probe.

// --- The control protocol (probe #8). A whole channel the library does not have today. ---
case class ControlResponse(                 // arrives on STDOUT, correlated by requestId
    requestId: String,                      // VENDOR-supplied correlation — unlike TurnId, real
    subtype: String,                        // "success" | …
    payload: Json                           // e.g. {"still_queued": []}
) extends Message

// Written to stdin. The library currently models SDKUserMessage and NOTHING else.
case class ControlRequest(requestId: String, request: ControlRequestBody)
enum ControlRequestBody:
  case Interrupt
  case Other(subtype: String, payload: Json)   // open — we have exercised exactly one subtype
```

**On `ControlResponse` being a `Message`:** it genuinely arrives interleaved on stdout, so the
reader must see it and route it to its waiting `request_id`. That is a small correlated
request/response layer — and it is legitimate precisely where `TurnId` was not: **the vendor
supplies the key.** We are adopting correlation where it exists and refusing to invent it where it
doesn't.

Rationale: `origin` is what makes turn attribution *checkable* rather than purely structural
(§2.2); `parentToolUseId` is what lets the UI stop rendering subagent chatter as main-agent deltas
(explicit Michal ask: present subagent activity nicely); `UnknownMessage` is the vendor-drift
insurance the log module already has and the live path lacks. `MessageConverter` migrates trivially
(new fields are additive information; `UnknownMessage → AgentMessage.Other`).

### 2.2 The session: no turns. The CLI owns turn boundaries; we observe them.

> **REWRITTEN 2026-07-15.** Probes #7/#8 invalidated this section's previous core (`send → Turn`,
> per-send `Turn.result`). Michal ruled: **drop turns; `send` is fire-and-forget.** His instinct from
> the start — *"I didn't understand why we play with some turns"* — is now backed by evidence. What
> follows is the replacement, plus the new problem it creates and the structural answer to it.

#### 2.2.0 The two probes that killed turns

**Probe #7 — mid-turn send is accepted and MERGED. Two sends → one result.** A 25s task, then a
second message 4s later while it ran; run twice (the second with additive phrasing to remove the
first run's cancelling-instruction confound). Both: no error, no rejection, message folded into the
**current** turn, **exactly one `result` for two sends.** *The first send never gets a result of its
own.*

This is fatal to `Turn.result` as a per-send promise: allow a second send while a turn is open and
turn A's Promise **never resolves** — a permanent hang, the precise failure class this redesign
exists to remove. My `TurnInFlight` fail-fast avoided the hang by **forbidding the feature Michal
uses constantly** in the terminal and explicitly wants. Between "a turn abstraction that forbids
mid-turn injection" and "no turn abstraction," the evidence says the abstraction was the fiction:
**per-send correlation does not exist at the protocol level.** A `TurnId` is us inventing an
identity the CLI does not have and cannot honour.

**Probe #8 — a control protocol exists, and we never had it.**

```
→ {"type":"control_request","request_id":"req_probe_1","request":{"subtype":"interrupt"}}
← {"type":"control_response","response":{"subtype":"success","request_id":"req_probe_1",
                                          "response":{"still_queued":[]}}}
```

Accepted instantly; the in-flight turn ended **immediately** with `subtype='error_during_execution'`,
`is_error=True`, `result=None`, `origin=None`; **the session survived** (a follow-up answered
`ALIVE`). Three consequences:

- **The library models `SDKUserMessage` on stdin and nothing else.** There is no control channel at
  all. `control_request`/`control_response` correlated by `request_id` is a whole protocol we don't
  have — a **capability gap**, not a nicety.
- **What the dashboard calls "interrupt" today is fake, and always has been.** Interrupting the ZIO
  fiber stops *us reading*; the CLI keeps working, keeps burning tokens, and may keep taking real
  actions. Its `ResultMessage` arrives later with nobody expecting it.
- **This corrects phase-01 §4's claim that the reader "subsumes Hypothesis 2 for free."** It does
  not subsume it — **it catches the debris.** The abandoned `ResultMessage` is consumed as
  out-of-turn instead of poisoning the next turn, which fixes *our* corruption but leaves the agent
  running. H2's actual mechanism is that we never had a real interrupt. Real interrupt stops the
  agent. Record the correction.

**The irony worth stating once:** we invented `TurnId` where the vendor offers no correlation and it
was a fiction; we ignored `request_id` where the vendor offers real correlation. This proposal
reverses both.

#### 2.2.1 The surface

```scala
// zio module
trait Session:
  def info: IO[CLIError, SessionInfo]

  /** Fire-and-forget. Writes the input at the CLI's next opportunity — terminal-style
    * injection. Returns when written, NOT when answered. The CLI decides whether this
    * starts a new turn or merges into the running one (probe #7); we do not model that
    * decision because we cannot observe it. No TurnId, no promise, no correlation. */
  def send(input: UserInput): IO[CLIError, Unit]

  /** Vendor-correlated control request (request_id). Stops the agent for real. */
  def interrupt: IO[CLIError, InterruptOutcome]

  /** Live message view. Lossy, bounded sliding: for RENDERING only (§2.2.3).
    * Nothing that must not be lost may come from here. */
  def events: ZStream[Any, Nothing, Message]

  /** The session's ACTIVITY STATE. Always readable, never delivered — so never dropped.
    * This, not `events`, is the completion signal (§2.2.2). */
  def state: UIO[SessionState]
  def stateChanges: ZStream[Any, Nothing, SessionState]  // may skip intermediates; `state` is truth

  def terminated: IO[Nothing, SessionEnd]

case class SessionState(
    resultsSeen: Long,                    // MONOTONE. Real results (origin ABSENT) only.
    notificationsSeen: Long,              // MONOTONE. Background-task results (origin PRESENT).
    lastResult: Option[ResultRecord],
    lastNotification: Option[ResultRecord],
    ended: Option[SessionEnd]
)

case class InterruptOutcome(stillQueued: List[String])   // vendor tells us what survived
```

`ResultRecord` is `TurnResult` renamed — the name was the last trace of the fiction.

#### 2.2.2 The completion problem, and the structural answer

**The problem, stated exactly.** With turns gone, `events` is the only live message surface. But
`result` is **stream-only** (§2.2.4) — it exists nowhere in the transcript. So a `result` dropped
from a lossy `events` is **unrecoverable**, and any consumer awaiting completion hangs forever.
`WorkerReactiveTurnRunner` does precisely send → await completion → proceed
(`WorkerReactiveTurnRunner.scala:141–172`). Previously `Turn.result` was a Promise the reader
fulfilled directly — that *was* the one-lossless-promise invariant, and dropping turns removes it.

**"Make the buffer big enough" is not an answer** — it is the same assumption species as "the CLI
won't emit between turns" and "the CLI won't emit that fast." Both have now cost us real bugs. I am
not proposing a third.

**The answer: completion is STATE, not an EVENT.** Events must be delivered, and delivery can drop.
State is *read*, and reading cannot drop. The reader already sees every message; it need not
*deliver* a result to make it observable — it can **fold it into a monotone counter that is always
readable**:

```scala
/** Await the next real result after the observed point. */
def awaitResultAfter(seen: Long): IO[CLIError, ResultRecord]

/** The consumer's whole need (WorkerReactiveTurnRunner, AgentPipeline, bootstrap). */
def sendAndAwait(input: UserInput): IO[CLIError, ResultRecord] =
  for
    s0 <- state              // read the counter BEFORE sending
    _  <- send(input)
    r  <- awaitResultAfter(s0.resultsSeen)
  yield r
```

**Why this is a guarantee, not a likelihood** — the question put to me directly. Subscribe-before-send
over a *stream* gives only a likelihood: it narrows a window, it does not close one, and a slow
subscriber can still drop. This closes it outright, because `awaitResultAfter` waits on a **monotone
predicate over always-readable state** (`resultsSeen > seen`):

- The counter never decreases, so the predicate is **stable once true**.
- The state is a `Ref`, so it is **readable at any time, arbitrarily late**.
- Therefore *when* you ask is irrelevant. If the result lands 1µs after `send` and before
  `awaitResultAfter` even runs, the counter has already advanced and the call **returns
  immediately**. There is no window to lose a race in — **not a narrow one, none.**

**Monotone predicates over monotone state are drop-immune.** That is the structural property, and
it is what `Turn.result`'s Promise was providing all along; the Promise was one implementation of it,
and not the only one. Dropping turns costs us the Promise, not the property.

**And it does not leak — which is what killed `outOfTurn`.** `outOfTurn` was an unbounded *queue
awaiting a consumer that might never come*. State needs **no consumer at all**: a `Ref` is O(1) and
a counter is 8 bytes whether anyone reads them or not. Nothing accumulates. That is the structural
difference, and it is why this answer is available here and was not available there.

**`origin` is now load-bearing for completion, not defence-in-depth.** `resultsSeen` counts only
origin-**absent** results; a background task finishing bumps `notificationsSeen` instead, so it
**cannot spuriously wake** a waiter. The discriminator probe #2 found has been promoted from "nice
assertion" to "the thing that makes completion correct." Note also that an interrupted turn's result
(`error_during_execution`, `is_error=true`, `result=None`, **origin absent**) *does* bump
`resultsSeen` — correctly: an interrupt **is** a completion, a failed one, and the waiter must wake
with `is_error=true` rather than hang.

**Merged sends resolve together, and that is correct.** If B merges into A's turn, one result
arrives and both waiters wake on it. Under merging semantics **nobody owns a result** — so waking
both is not a bug, it is the honest reading of what the CLI did.

#### 2.2.3 Result envelopes need a durable sink — the gap I flagged is now load-bearing

The monotone counter makes the **completion signal** reliable with no durability at all. It does
**not** make **result envelopes** durable: if two results land between observations, `lastResult`
holds only the latter and the earlier envelope — its cost, `usage`, `permission_denials`, timings —
is **gone forever**, because the transcript never had it (§2.2.4).

Previously each envelope was caught by its turn's Promise. **Dropping turns removes that, so the
durable sink I flagged as "ADR 0009 territory, out of library scope" is now load-bearing.** I am
reversing that recommendation, and the reason is Michal's own ruling on §2.4: the library guards
against vendor gaps so consumers don't have to. **The vendor not persisting results at all is a
worse gap than pruning.**

**Proposal: the archive gains a result journal.** `<archive>/<session-id>.results.jsonl`, appended
by the reader **before** it bumps the counter, so a visible counter always implies a durable record:

```
archive/<session-id>.jsonl          # mirrored vendor transcript  (content the vendor keeps)
archive/<session-id>/subagents/…    # mirrored sidechains
archive/<session-id>.results.jsonl  # OUR journal (envelopes the vendor keeps nowhere)
```

The library persists **exactly what the vendor doesn't**, and the in-memory state becomes an index
over a durable log — the ordinary shape. Losslessness from the log; liveness from the state; join to
the transcript via `MessageId`.

**Costs, plainly:** the library starts **writing**, not just mirroring — a genuinely larger claim
than §2.4 made, and the reason I am flagging rather than assuming it. Ordering (append-then-bump) is
now a correctness invariant. And it is **opt-in**: with no `ArchiveConfig`, completion still works
(the counter needs no disk), but envelopes may be lost between observations. That split is honest
and should be stated in the scaladoc:
**completion signal = always reliable; envelope durability = requires the journal.**

#### 2.2.4 What "attribution" means now — and the §5.1 race dissolving

The old `Attribution.InTurn(TurnId)`/`OutOfTurn` is **deleted**. With no turns there is nothing to
attribute *to*: messages are just the session's output, in order, each with a vendor `uuid`. What
survives is what the vendor actually marks:

- `origin` on a result → real turn ended vs background task ended.
- `parentToolUseId` on a message → subagent chatter vs main-agent output (and the sidechain join key).

**This dissolves §5.1's residual race rather than mitigating it.** That race was: an out-of-turn
assistant message landing in the send window gets mis-attributed to the turn. **With no turns, there
is no attribution to get wrong.** Nobody claims "this message answered your send," so no claim can be
false. The race does not shrink — its *subject* ceases to exist. I think this is the strongest
evidence for Michal's ruling: the turn abstraction was not merely a fiction, it was **the thing
generating the bug class**, and PROC-603 was one instance of it.

**Consequently `outOfTurn`-as-a-batch dies too**, and its replacement is simpler than either prior
design: a background task finishing **is** a result with `origin` present, and that result carries
its own `result` text. `WorkerOutOfTurnDelivery`'s push becomes "on a notification, push its text" —
no batching, no grouping, no buffer. The incident's own evidence confirms the text is there.

### 2.3 Structured user input — round-trippable by law

Replaces `wrapInteractive`/`enrichWithContext` string concat (`AgentPipeline.scala:24–31`) and the
`<reactive-input>` template (`WorkerReactiveTurnRunner.scala:96–101`):

```scala
case class UserInput(
    text: String,                          // the human's verbatim text — never entangled with context
    context: List[ContextItem] = Nil,      // per-message, since it changes as the user browses
    channel: Channel = Channel.Interactive // open vocabulary: Interactive / Reactive / Custom(name)
)
enum ContextItem:
  case Viewing(url: String)
  case Labeled(name: String, value: String)  // matter-instructions, email envelopes, …

object UserInput:
  def encode(input: UserInput): WireContent            // canonical, injection-safe
  def decode(entry: UserLogEntry | String): Option[UserInput]
  // LAW (property-tested, adversarial text incl. "</interactive>"): decode(encode(i)) == Some(i)
```

**Wire form: content blocks. CONFIRMED — the fallback is deleted, not deferred.** Probe #1 sent
`message.content` as a 2-element array of text blocks on stdin with the exact session flags. The
CLI **accepted it** (responded in 2.4s, no error), and **the transcript preserved the structure**:
the user entry's `content` is a list with both blocks intact and separate. Critically, verbatim
text containing **`</interactive>`** — the exact sequence that breaks today's concat encoding
(`AgentPipeline.scala:24–31`) — **round-tripped untouched inside its own block.**

So each `ContextItem` becomes its own block and `text` its own block: verbatim user text never
concatenates with context, **injection is impossible by construction rather than by escaping**, and
the transcript's `UserLogEntry(content: List[ContentBlock])` preserves the structure for free —
that *is* the round trip, end to end, with no encoding of ours in the middle. §2.3's `encode`/
`decode` law holds by construction.

This was the proposal's riskiest unknown and it resolved in favour of the clean answer. The earlier
fallback (a single string with a length-delimited or random-boundary header) is **deleted** — no
grammar of ours to specify, test, or get wrong. `promptSafe` (`WorkerReactiveTurnRunner:126–129`)
survives as defence-in-depth for untrusted header text, but it stops being load-bearing.

This is also what makes "UI parses and presents what the agent actually received" real: the worker
chat renders `decode(...)` as text + context chips; reactive prompts (`Channel.Reactive`) render as
badges or fold away — replacing today's `AssistantOnlyMessageStorage` suppression hack
(`WorkerOpenEndpoints:117`, `WorkerReactiveTurnRunner:144`) with an honest record.

### 2.4 History: the transcript is the record **of conversation content**; the library guards it

> **Scope, stated up front because the probes narrowed it.** "The transcript is the record" is true
> for **conversation content** — user/assistant messages, main-thread and subagent. That is what the
> incident corrupted, what the UI renders, and what the second copy must stop duplicating. It is
> **false as a blanket claim**: the `result` envelope (cost, usage, timings, `origin`,
> `permission_denials`) is **stream-only** and no amount of transcript mirroring reconstructs it
> (§2.2.2). Anyone reading this section as "the transcript holds everything" will lose that data.

Decision taken as given: live rendering ≠ history; the second attributed copy dies. The library
surface (promoting `core/log` from bystander to primary):

**Three probe findings reshape this section.** (i) A session's record is a **tree, not a file**;
(ii) the transcript and the stream are **overlapping, neither a superset**; (iii) the project-path
encoding is **lossy in the decode direction**.

```scala
// zio module (tagless core contract stays in core/log)
trait ConversationArchive:
  /** Locate by SDK session id. Resolves by ENCODING a known cwd — never by
    * decoding a directory name (see the lossy-encoding note below). */
  def forSession(sessionId: String): IO[ArchiveError, Option[SessionRecord]]
  /** Main-thread entries. RawLogEntry tolerance means vendor drift degrades to
    * "renders as raw", never to data loss. */
  def entries(sessionId: String): ZStream[Any, ArchiveError, ConversationLogEntry]
  /** A subagent's own sidechain entries, joined by parent_tool_use_id (§2.1). */
  def subagentEntries(sessionId: String, parentToolUseId: String)
      : ZStream[Any, ArchiveError, ConversationLogEntry]
  /** Idempotent, append-aware mirror of the whole session TREE — the guard
    * against vendor pruning. */
  def mirror(sessionId: String): IO[ArchiveError, MirrorReport]

object ConversationArchive:
  def live(config: ArchiveConfig): ULayer[ConversationArchive]
  // ArchiveConfig(vendorProjectsDir /* CLAUDE_CONFIG_DIR convention */, archiveDir, cwd)
```

**(i) The record is a tree.** Verified on the probe session:

```
<projects>/<encoded-cwd>/
  0d43043b-….jsonl                      # main thread
  0d43043b-…/subagents/
      agent-af346d0ac30f337e0.jsonl     # subagent transcript
      agent-af346d0ac30f337e0.meta.json # carries the toolu_… id
```

The subagent messages missing from the main transcript (stream lines 10/12/14/15, all bearing
`parent_tool_use_id: toolu_01RKf…`) are **all present in the sidechain file** — I located them by
that uuid. So the vendor record *is* complete; it is simply **spread across a tree**. Two
consequences: `mirror` must copy the tree (main + `subagents/*.jsonl` + `*.meta.json`), not one
file — an earlier draft said "the vendor jsonl" and would have **silently dropped every subagent
transcript**; and `parent_tool_use_id` is not merely a rendering nicety but **the join key** from a
live subagent message to its sidechain record. That is now a third, independent argument for §2.1
adding it.

**(ii) Neither record contains the other.** The transcript holds entry types the stream never emits
(`queue-operation`, `attachment`, `last-prompt`); the stream carries messages the transcript never
records (**all `result` messages**, every `system` subtype, `rate_limit_event`). So "the transcript
is the record" is true **for conversation content** — which is what the incident corrupted and what
the UI renders — and **false as a blanket claim**. The `ResultRecord` envelope (cost, usage, timings,
`permission_denials`, `origin`) is **stream-only and unrecoverable if not captured live** (§2.2.2).
If we want denial/cost history — and ADR 0007/0009 say we do — **that specific data needs its own
durable sink**, because no amount of transcript mirroring will reconstruct it. I am not proposing
that sink here (it is graph/event-store territory per ADR 0009, not library territory); I am
flagging that §2.4's "stop keeping a second copy" applies to *conversation content*, and does not
license discarding the result envelope.

**(iii) Encoding is lossy — one safe direction only.** `ProjectPathEncoder` is just `/` → `-`, so
`/a-b` and `/a/b` both encode to `-a-b`. `forSession` encodes a **known** cwd (the safe direction);
any *decode*-direction use is guessing, and `ProjectPathDecoder` cannot be correct in general — its
own scaladoc admits the ambiguity. Constraint on this contract: **locate by encoding a known cwd,
never by decoding a directory name.** `ArchiveConfig.vendorProjectsDir` is configurable because
`CLAUDE_CONFIG_DIR` is real and in use here (`/home/mph/.claude-iw`, not `~/.claude`) — confirmed,
not assumed.

Plus one wiring hook: a `Session` opened with an archive configured mirrors **after each result and
on close**, and appends every result envelope to the result journal (§2.2.3). That replaces the
consumer's independent transcript backup and makes "the library owns persistence" concrete without
the library inventing its own storage format — **the vendor's jsonl, mirrored and tolerantly
parsed, IS the storage format.** Where I push back on the framing (§7): the library owns transcript
*access + custody + format tolerance*; **domain attribution stays consumer-side** (which worker,
which Matter, what's visible — `ConversationId.forWorkerSession`, graph links). The library must
not grow a `NodeId`.

### 2.5 The seam for other agents

The **shape** — `send(input) → Turn`, `events`, `outOfTurn`, `terminated`, `ConversationArchive` —
is the portable idea. The **types** (`Message`, `ContentBlock`, `ConversationLogEntry`,
`SessionOptions`) are Claude's wire format and stay Claude-named in `works.iterative.claude.*`. A
second agent (pi, opencode) gets a sibling module with its own types mirroring the shape; common
traits get extracted only when two implementations exist and their transcripts have actually been
read. Concretely: **no `agent-query` module, no common `Message` supertype, no adapter — now.** The
consumer's own `AgentSessionPort`/`AgentMessage` (`core/src-jvm/ports/AgentSessionPort.scala:88`,
`MessageConverter`) already is the app-level seam and keeps that job.

---

## 3. What each consumer need maps to

| Need (from §1) | Contract element |
|---|---|
| Warm process per worker, await completion | `sendAndAwait` = read counter → `send` → `awaitResultAfter` (§2.2.2). Drop-immune by monotone predicate, not by buffer size |
| SSE deltas (`AgentChatRunner:48–49`) | `events` — lossy, bounded sliding, rendering only |
| Out-of-turn **live push** (`WorkerOutOfTurnDelivery:59–67`) | `Session.outOfTurn` — derived from `events`; registry holds one standing subscription per session |
| Out-of-turn **persist** (`WorkerOutOfTurnDelivery:48–58`) | **Nothing — deleted.** The CLI already wrote it; the transcript is the record (§2.4) |
| Page-level live channel, multiple observers (phase-01 §11) | `Session.events` (Hub) |
| Death without hanging a waiter (phase-01 §12 C1/C2) | `awaitResultAfter` races `terminated`; `SessionState.ended` is also readable |
| `sessionId` for resume (`WorkerOpenEndpoints:137`, `AgentPipeline:81–83`) | `ResultRecord.sessionId`, `SessionInfo` |
| One-shot query path (heartbeat, events, bootstrap) | `ClaudeCode.query` kept as-is, but returning the richer parsed `Message`s; the `var sdkResult` dance dies once callers read the final `ResultMessage` from the stream's last element (guaranteed by the same total parser) |
| History render on page load (`FileMessageStorage.loadRecentMessages`) | `ConversationArchive.entries` + `UserInput.decode` |
| Context chips / subagent rendering (Michal ask) | `UserInput` round-trip; `AssistantMessage.parentToolUseId`; `ProgressLogEntry`/`isSidechain` in the transcript |
| Reactive turn fire-and-await (`WorkerReactiveTurnRunner:141`) | `sendAndAwait(...).unit` — nothing else |

---

## 4. Sequencing and what gets DELETED

**Michal's decision (given): ZIO first.** `direct`/`effectful` get the same treatment *after* the
ZIO contract is implemented and proven — not dropped, not now. The complication that decision has
to face: **the model lives in `core`, which all four modules share**, so §2.1 is not zio-local.
Here is the honest split, with counts rather than adjectives.

### Wave 1 — `core` model totality. The only cross-module wave.

**What lands:** `UnknownMessage`, `MessageId` (the vendor `uuid`), `ResultMessage.origin` + the
other ~10 unmodelled result fields (`permission_denials`, `stop_reason`, `terminal_reason`,
timings, …), `AssistantMessage.parentToolUseId`/`model`, real `TokenUsage`, the `ControlRequest`/
`ControlResponse` types (probe #8), and the parser rewrite.

**The probes grew Wave 1's scope without changing its cost.** Adding 10 fields breaks exactly the
same sites as adding 2: positional patterns break on *any* arity change, and defaulted trailing
fields keep constructions compiling regardless of count. So the number below holds as measured.

**Why it's cheaper than §0 feared — the parser delegates.** `direct`, `effectful` and `zio` each
have an `internal/parsing/JsonParser.scala`, but all three are thin wrappers that call
`CoreJsonParser.parseMessage` (`direct/:67,79`; `effectful/:15,30`; `zio/:14,28`). Parse-totally is
written **once**, in `core`, and all four modules inherit it. No duplication to chase.

**The exhaustivity/arity story, precisely.** Two different mechanisms, and defaults only fix one:

- **Construction survives.** Adding *trailing defaulted* fields keeps every construction site
  source-compatible: `ResultMessage("s",1,1,false,1,"id")` (`zio/test/SessionTest.scala:38`,
  `ZioPackageReexportTest:22`) and `AssistantMessage(List(TextBlock("hi")))` still compile, because
  `totalCostUsd`/`usage`/`result` are already defaulted and the new fields append. **This is the
  answer to "defaulted fields?" — they save construction, not pattern matching.**
- **Positional patterns break.** `case AssistantMessage(content)` cannot match a 3-field case class.
  Production sites: **exactly two** — `effectful/ClaudeCode.scala:54` and `zio/ClaudeCode.scala:61`.
  The fix is a **proven in-repo idiom**, not an invention: `direct/ClaudeCode.scala:162` already
  writes `case assistant: AssistantMessage => assistant.content`, which is immune to added fields.
  So `direct` needs **zero** changes here. Test sites: ~8–12 positional patterns across
  `core/test`, `direct/test` (`ClaudeCodeTest:115` destructures `ResultMessage` 9-positionally),
  `zio/test`.
- **`UnknownMessage` breaks exhaustive matches only** — and the library has none in production: every
  `Message` match is either a `collectFirst` (partial, no exhaustivity obligation) or has a
  catch-all (`SessionProcess.scala:124–126`, `readInitMessage:166–173`). **Zero library production
  breaks.** It breaks exactly one consumer site — `MessageConverter.convert`
  (`dashboard/src/agent/MessageConverter.scala:28`), which is **desirable**: it forces a decision
  about unknown types rather than silently dropping them.
- **One open question, not a break:** `TokenUsage` already exists at `core/log/model/TokenUsage.scala`.
  Reusing it from `core.model.Message` points `model` at `log.model`. Cleanest is to move
  `TokenUsage` to `core.model` and alias it from `log.model`; worth deciding, not worth agonising.

**The number, plainly: ~2 production lines + ~10 test sites; one compile-fix pass across three
modules; no behaviour change in `direct`/`effectful`.** They keep their existing `Session` and
simply see richer messages. That is the whole cross-module cost. It is small because the parser
delegates and because `direct` already uses the safe idiom.

**Cheap honesty to include in Wave 1:** put a scaladoc **hazard warning** on `direct`/`effectful`
`Session.stream` pointing at the PROC-603 failure mode. Those modules keep the turn-shaped lie
until their wave arrives; a doc line costs nothing and stops it misleading the next reader the way
it misled us.

### Wave 2 — `Session` (no turns) + `interrupt`. Zio-local, but now COUPLED to Wave 4.

**Scope:** fire-and-forget `send`, `SessionState` + the monotone counter, `events`, the control
channel and a **real** `interrupt` (probe #8 — a capability we have never had; what the dashboard
calls interrupt today only stops us reading while the agent keeps working and spending).

**⚠ Coupling introduced by the no-turns ruling (§5.8 item 2): Wave 4 must land with or before this.**
Fire-and-forget removes the ability to pair a reply with its message, so the archive has to be the
record *before* pairing is removed — otherwise we ship a store we've just proved is a fiction. These
two waves are no longer independently shippable. Wave 3 (`UserInput`) also wants to be near them,
since raw user text lives only in the dashboard's copy until it lands (the ordering constraint that
has been in this document since the first draft).

No `core` change beyond Wave 1's. `direct`/`effectful` `Session` untouched. **Stated cost:** the flawed contract
survives in two modules we don't consume, for as long as it takes to reach them — anyone using
`direct`/`effectful` `Session` remains exposed to PROC-603's mechanism. That is the price of
ZIO-first, and it should be written down rather than discovered.

### Wave 3 — `UserInput`. Core-additive; zio-local wiring.

`SDKUserMessage(content: String, …)` is constructed by all three `SessionProcess` impls
(`zio/SessionProcess.scala:212`). **Keep that constructor intact** and add the structured encoding
as a separate smart constructor/encoder, so `direct`/`effectful` don't break. **Zero breaks outside
zio.**

### Wave 4 — `ConversationArchive`. Core-additive.

A new trait in `core/log` alongside the existing `ConversationLogReader`/`Index` + a zio impl.
**Zero breaks.**

**Summary: only Wave 1 touches three modules, and it is ~2 production lines.** Waves 2–4 are
zio-local or purely additive. Each wave ships independently; the offline harness verifies 2–4.

### What gets deleted

**Library (breaking; version 0.5.0):**
- `Session.stream` — the turn-shaped view over a turn-less process. The docstring that caused the
  incident goes with it.
- `Session.send(prompt: String): IO[Unit]` — replaced by `send(UserInput): IO[CLIError, Turn]`.
  No `String` overload kept (a String overload would silently reintroduce the injectable encoding).
- The `"pending"` sessionId sentinel (`Session.scala:33–39`) — replaced by `info`.
- `parseMessage`'s `case _ => None` and `extractUsageData`'s `Map.empty`.
- **Not deleted:** `direct`/`effectful` Session. Per Michal's decision they follow after the ZIO
  contract is proven (§4 Wave 2). Nothing here is priced as a drop.

**Consumer (consequential, phased):**
- `WorkerAgentSessions` `:221–440` — `runOnce`/`awaitTurnBatch`/`readerLoop`/`routeMessage`/
  `bufferOutOfTurn`/`outOfTurnConsumerLoop`, the `sink`/`outOfTurnBuffer`/`death`/
  `outOfTurnDeliveries` Entry fields, and their four documented residual races. The registry keeps
  what is genuinely domain: id→session map, idle reap, recreate-on-eviction, `portFor`. Roughly
  350 lines of the hardest concurrency code in the dashboard, plus the `QueueCliSession`-family
  test doubles that phase-01 §10/§12 spent days making honest.
- `WorkerOutOfTurnDelivery`'s **persist half** (`:48–58`) — the CLI already wrote it; the transcript
  is the record. Its **push half** (`:59–67`) survives, fed by `Session.outOfTurn`.
- `ClaudeCodeAgentSession`'s mutable `var sdkResult` capture (`:45–52`) and the stderr-string
  session-invalid sniffing (`:178–181`) once errors are typed (add `InvalidResumeSession` to
  `CLIError` while we're in there — same release).
- `FileMessageStorage` as the worker-conversation store, `AssistantOnlyMessageStorage`, and the
  ms-timestamp pairing convention — **only in phase 3** (see ordering below). Gladys' non-worker
  chat migrates on its own schedule; nothing forces it.
- `AgentPipeline.wrapInteractive`/`enrichWithContext` → `UserInput` construction;
  `WorkerReactiveTurnRunner.composePrompt`'s tag soup → `Channel.Reactive` + `ContextItem`s
  (`promptSafe` survives — flattening untrusted header text is still right, it just stops being
  the *only* defense).

**Migration order is load-bearing:** (1) parser + model fixes; (2) new `Session`, registry
shrinks; (3) `UserInput` encoding lands **and only then** (4) FileMessageStorage retires — because
today the *raw* pre-enrichment user text exists **only** in the dashboard's copy
(`AgentPipeline:69–73` saves `message`, not `messageWithContext`); the CLI transcript holds the
enriched form. Delete the copy before the round-trip encoding exists and raw user text becomes
unrecoverable. Each step ships and runs independently; the offline harness verifies 2–4.

---

## 5. Trade-offs and residual risks (the honest section)

1. ~~**The send-window race survives, smaller.**~~ **DISSOLVED by dropping turns (§2.2.4).** This
   entry described an out-of-turn assistant message leaking into a turn's attribution. With no
   turns there is **no attribution to get wrong** — nobody claims "this message answered your
   send," so no claim can be false. The race's *subject* ceased to exist. Kept in the list because
   the reasoning matters for the ADR: the turn abstraction was not merely a fiction, **it was the
   thing generating this bug class**, and PROC-603 was one instance of it.
2. **Library-owned routing = consumer loses the escape hatch.** Today the dashboard can hotfix
   attribution logic in its own repo in an afternoon; after this, every attribution bug is a
   library release round-trip. That is the price of "stop making every consumer build it" — and it
   makes the library's own test battery (a `SessionProcess` double with out-of-turn injection —
   the §7.1 double, moved upstream where it belongs) non-negotiable.
3. **Transcript-as-history has a latency and a coupling cost — larger than I first priced.** Page
   load goes from "read ≤N small md files" to "locate + parse a **tree** (main jsonl + N sidechain
   jsonls) that grows monotonically" — the subagent finding (§2.4) added a dimension; needs
   measurement (probe #5);
   mitigable (mirror + tail-seek + cache) but not free. And the record's *format* is now
   vendor-owned: `RawLogEntry` guarantees we never lose bytes, but a semantic rename (e.g.
   `parentUuid` changing meaning) degrades rendering silently. Wants a canary test pinned to the
   installed CLI version.
4. **Everything live is lossy; the reliable signals are not delivered at all.** `events` is
   bounded-sliding; the lossless record is the transcript (content) plus the result journal
   (envelopes, §2.2.3); the reliable completion signal is **read from state**, not delivered
   (§2.2.2). Two consequences worth stating rather than burying: (a) an
   out-of-turn *live notification* can in principle be dropped — the user then sees the output only
   on reload, a small real UX regression vs an unbounded queue, accepted because the drop requires
   outpacing a 1024-deep ring with a server-side memory-to-memory consumer; (b) the buffer size is
   now a **contract parameter, not a tuning knob** — it should be stated in the scaladoc and
   defaulted conservatively. If a future consumer genuinely needs lossless multi-subscriber live
   delivery, that is a new requirement with a new surface, not a default — and the honest answer
   then is a durable queue, not an unbounded in-memory one.
5. ~~**Fail-fast `TurnInFlight`**~~ **DELETED — Michal rejects fail-fast; mid-turn injection is a
   feature he uses constantly.** Probe #7 shows the CLI accepts and merges it, so forbidding it was
   the library inventing a restriction the vendor does not impose. But this removal has a real
   downstream cost the ruling implies and nobody has priced yet — see **§5.8, the bill**.
6. **Session pooling stays consumer-side.** The registry's remaining half (id-keyed pool, idle
   reap, recreate-with-resume) is arguably generic. Not proposing it now — one consumer, and it's
   entangled with domain policy (what counts as activity, when to resume). Trigger to lift it:
   a second in-house consumer of warm sessions.
7. **`WorkerProposalEndpoints:377` — RULED (Michal, 2026-07-15): a real turn.** Not a residual risk;
   recorded here only because this is where it was raised. See §5.7a.

### 5.7a The contract forces a latent bug into the open — and Michal ruled to fix it

**Decision (Michal, 2026-07-15): a real turn.** *"The fact that we only save it and do not let the
agent know is a bug, so yes, we want a real turn."*

`WorkerProposalEndpoints:377` writes a proposal-rejection notice with `saveUserMessage` **only**,
never driving a turn — so the worker never learns its proposal was rejected. It has always been a
defect (`incident-evidence.md:78–82`, where it is also a diagnostic red herring). Under this
contract a user message exists **only** as `send(UserInput)`, and the record is the CLI's
transcript — so "append to the conversation without telling the agent" becomes **unrepresentable**.

**Frame this as a feature of the contract, not a cost of it.** The fake-message trick was only
writable because our own second copy could be written to without the agent's knowledge; removing
the second copy removes the ability to lie to ourselves about what the agent has seen. The fix —
drive a real turn — is what the code should always have done. The old shape becomes uncodeable
exactly when Wave 3 lands.

Two consequences, stated rather than buried:

- **It costs an LLM turn per rejection.** Rejections are user-initiated and infrequent; acceptable.
- **Clicking Reject now spawns agent activity.** That brushes **ADR 0007**'s outward-act boundary —
  but on the **worker's own Matter, in a session the user is already in**, which is the *same
  surface* Michal already approved for out-of-turn output (phase-01 §9). Flagged for the ADR's
  record; not relitigated here.

### 5.8 The bill for dropping turns — itemised, because Michal chose the shape on a summary

He chose correctly on the evidence he had, and §2.2.4 argues the ruling is *better* than my design.
But it has consequences the summary didn't carry. Itemised, cheapest first:

**1. The reply↔message pairing is dead, and that is a UI change, not just a storage change.**
This is the big one. `AgentPipeline:69–83` saves the user message, awaits the reply, saves the reply
beside it — a **Q&A ledger**. Probe #7 proves the pairing is not a fact the CLI can honour: inject
mid-turn and two messages share one result. So **you cannot have both terminal-style injection and
"this reply answers that message."** Michal wants injection; therefore the ledger goes, and the
worker chat becomes a **stream** (like a terminal) rather than paired bubbles.

In practice the damage is confined: single-sender, no-injection flows still look exactly like Q&A,
because one send → one result. It degrades **only when the user opts into injection** — precisely
where they'd expect terminal-ish behaviour. So I think this is right. But it should be *chosen*, not
discovered when the UI looks wrong. **This is the item I'd most want Michal to confirm explicitly.**

**2. Sequencing changes: Wave 4 (archive) must land with or before Wave 2 (Session).** Previously
independent. Now: the moment `send` becomes fire-and-forget, `FileMessageStorage`'s pairing is
knowingly a guess — so the archive must be there to be the record before we remove the ability to
pair. Shipping Wave 2 alone would mean **persisting a fiction we've just proved false.** §4 updated.

**3. `WorkerAgentSessions.turnLock` loses its meaning — and `reapIdle` is built on it.** The lock
serializes turns; with merging, serialization is neither needed nor wanted (it would block the
injection feature). But `reapIdle`'s cooperative `tryWithPermit` idleness check
(`WorkerAgentSessions:445–458`) *is* that lock. Removing it breaks idle detection. Replacement is
available and arguably better — idleness becomes "`resultsSeen` hasn't moved and nothing is
pending," derived from the CLI's actual behaviour rather than our lock — but it is a **real change
to the reaper**, which has a documented history of races (phase-01 §6.3, §12). Not free.

**4. `AgentSessionPort.sendMessage` keeps its shape but weakens its promise.** Every consumer
(heartbeat, event coordinator, bootstrap, chat) is `prompt → AgentResponse`. It survives as
`sendAndAwait`, but its contract becomes *"returns the next result, which may be a merged result
answering more than your message."* True for all of them today (single sender at those call sites),
false the moment anything injects. **Document the weakening; don't let the type keep implying a
promise the protocol won't make.**

**5. We lose the ability to say "these deltas belong to your message"** — restated at the UI level
from item 1. There is no per-send delta filter because there is no per-send anything. The chat
renders the session's output; the terminal frame (`origin`-absent result) ends the spinner.

**Not on the bill:** the completion signal itself (§2.2.2 makes it a *guarantee*, stronger than the
Promise it replaces) and the §5.1 race (dissolved, §2.2.4). Dropping turns is a net win. It is just
not free.

## 6. Probes/measurements — status

**Done — all three resolved in favour of the design:**

1. ~~**Content blocks on stdin?**~~ **ACCEPTED.** CLI accepts a block array; transcript preserves
   the list; `</interactive>` round-trips untouched. §2.3's fallback grammar **deleted**. ✅
2. ~~**`origin` behaviour?**~~ **Clean discriminator.** Key-*absent* on in-turn (3 samples),
   `{kind: task-notification}` out-of-turn (2 samples). Encoded as `Option[ResultOrigin]`, kept
   open. Sufficient to hard-override attribution for `ResultMessage`; the assistant-message half of
   the §5.1 race remains undiscriminated. ✅
3. ~~**Does the stream `uuid` match the transcript's?**~~ **Yes, on 5/22 — the right 5.** Adopted as
   `MessageId`. (`TurnId` has since been deleted outright — probes #7/#8.) Surfaced that `result` is stream-only and
   the record is a tree (§2.4). ✅

**Outstanding:**

4. ~~**Does the chat `done` handler replace or append?**~~ **REPLACE — the collapse precondition
   holds.** `patchReplyDone` uses `ElementPatchMode.Outer` with the authoritative `resultText`
   (`WorkerChatDatastarSse:59–64`); deltas patch `Inner` with the accumulated string (`:71–75,146`),
   never an incremental append. Verified by reading, not probing. Now a **stated coupling** (§2.2.1):
   the safety belongs to the render path, so it goes in the buffer's scaladoc. ✅

7. ~~**Is a mid-turn send accepted?**~~ **ACCEPTED AND MERGED — killed turns.** Two sends → one
   result, `num_turns=2`, first send never gets its own result. Falsified per-send correlation
   outright; `Turn`/`TurnId`/`TurnInFlight` deleted (§2.2.0). ✅
8. ~~**Is there a control protocol / real interrupt?**~~ **YES, and we never had it.**
   `control_request`/`control_response` correlated by vendor `request_id`; interrupt is instant,
   yields `error_during_execution`/`is_error=true`/`result=None`/`origin` absent, and the session
   survives. Corrects phase-01 §4: the reader **catches H2's debris, it does not subsume H2** — the
   agent kept running the whole time. ✅

**Implementation-time, not contract-shaping — do not block the ADR on these:**

5. **Transcript read latency** for a realistic worker session (locate + parse + last-40 entries)
   vs today's md-file reads — now measured over a **tree** (main + sidechains), which raises the
   cost above my original estimate. Decides whether `entries` needs an index/offset cache in v1.
6. **What populates `permission_denials`?** Observed `[]` on both results; shape of a populated
   entry unknown. §2.1.0 is written so nothing depends on the answer.
4. **Does the chat `done` handler replace or append the assistant bubble?** (`WorkerChatDatastarSse`
   / `SseEventMapper` against `AgentChatRunner:66–75`.) A **precondition for accepting the
   collapse** (§2.2.1): the terminal frame must be authoritative for a dropped delta to be
   self-correcting. Cheap to check; if it appends, fix it — correct regardless of this proposal.
5. ~~Maven Central users of `direct`/`effectful`~~ — **moot.** Michal's call is ZIO-first, not drop
   (§4); nothing is priced as a deletion.

## 7. Pushback on the framing (asked for)

- **"Library owns persistence" — agreed, with a boundary.** Custody, access, and format-drift
  tolerance: yes (§2.4). But if "own history" is read as "own a *rendered/attributed* store", no —
  attribution to Matters/workers is the graph's job (ADR 0009: the graph is the projection layer;
  a library-side attributed store would be a *third* copy). The proposal's mirror-the-vendor-jsonl
  is deliberately the weakest ownership that satisfies "don't push pruning risk onto consumers".
- **The three-module structure was the unpriced item; it is now priced and it is small.** §4 counts
  it: ~2 production lines + ~10 test sites, because the parsers delegate to `core` and `direct`
  already uses the field-safe idiom. My own §0 draft called this 3× and was wrong. The remaining
  3× exposure is confined to `Session` (Wave 2), which Michal's ZIO-first decision defers
  deliberately rather than pays now.
- **I over-built the lossless surfaces and the coordinator's collapse was right** (§2.2.1). The
  tell was in my own text: `outOfTurn` was justified as a delivery surface "someone persists" while
  §2.4 had already removed the persister. Two unbounded queues went with it.
- **And then I was wrong about turns, which the collapse debate never questioned.** Both the
  original design and the collapse assumed per-send correlation existed; we argued about how to
  *deliver* a turn's result, never whether a turn was real. Probe #7 answered that in one shot.
  The lesson worth carrying into the ADR: **we spent two rounds refining a delivery mechanism for
  an identity the protocol does not have.** Michal's "I didn't understand why we play with some
  turns" preceded all of it. The eager-attach argument I defended was correct *about its own
  premise* — subscribe-after-send is a timing race — and is now moot; §2.2.2 replaces it with
  something strictly better, a monotone predicate that has no window at all rather than a narrow
  one.
- Everything else in the framing checked out against source (§0), including the parts I tried to
  falsify: the docstring really does promise turns over a turn-less process, the internals really
  do say so honestly, and the incident's "CLI-side state was correct throughout" is visible in the
  transcript evidence (`incident-evidence.md:71–74`).
