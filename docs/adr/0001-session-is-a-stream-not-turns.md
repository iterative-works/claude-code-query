<!-- PURPOSE: ADR replacing the turn-shaped Session API with a stream + injection + readable state, after probes proved the CLI has no turns; keeps the turn framing for one-shot queries where it is true -->
<!-- PURPOSE: Records the 2026-07-15 contract decisions — total parsing, fire-and-forget send, real interrupt, structured user input, transcript custody -->

# ADR 0001: A session is a stream; turns belong to one-shot queries

**Status:** Accepted (2026-07-15) — decided with Michal in an interactive design session, from a consumer's production incident plus five probes against the real CLI
**Date:** 2026-07-15
**Author:** Michal Příhoda, with Claude (dev)

## Context

A consumer (the `procedures` dashboard) hit a defect that took a week to understand: in a chat backed
by a long-lived session, the reply to message *N* only became visible after the user sent message
*N+1*. Permanently. The reply was not lost — it was **persisted against the wrong message**.

The consumer's code correlated a turn's result to its `send` **positionally**: `send(prompt)`, then
read `stream` to the next `ResultMessage`, and treated that as the answer. That is exactly what this
library's API invites. It is also wrong.

### What we promise, and what the CLI does

`Session.stream`'s scaladoc:

> *"Reads stdout messages **for the current turn** until a ResultMessage. […] **Must be called after
> `send`**."*

The implementation is `ZStream.fromQueue(messageQueue, maxChunkSize = 1).collectWhile(...)
.takeUntil(_.isInstanceOf[ResultMessage])` — **a view over a shared, long-lived queue.** `send` and
`stream` are correlated by **nothing**: no id, no token, no sequence. The only coupling is the
caller's hope that they alternate.

**The internals are honest.** `SessionProcess` says it plainly — *"the message queue is shared and
long-lived across turns"* — and `maxChunkSize = 1` exists precisely so `takeUntil` cannot over-pull
and swallow the next turn's messages. The people who wrote the internals knew there are no turns.

**The public contract is the lie**, and the docstring is the artifact that misled a competent
consumer for a week.

How it got there is worth recording, because it is not incompetence: **this shape is correct for a
one-shot `query`** — spawn a process, send a prompt, read to the result, the process dies. The
session API was extrapolated from it, without noticing that a long-lived CLI **talks when it was not
asked to**.

### What we probed (real CLI, exact session flags)

| # | Question | Answer |
|---|---|---|
| 1 | Does the CLI emit output with no turn in flight? | **Yes.** One user message → `result` #1 at 35.3s, `result` #2 at 42.6s. A background `Agent`/`Task` finishing resumes the main loop and emits a full second result. |
| 2 | Is there a discriminator? | **Yes, one.** In-turn results have **no `origin` key at all** (3 samples). Out-of-turn: `{"kind": "task-notification"}` (2 samples). We currently drop it. |
| 3 | Does stdin accept `message.content` as an array of blocks? | **Yes.** Accepted, and the **transcript preserves the list**. Text containing `</interactive>` round-tripped untouched inside its own block. |
| 4 | What happens to a user message sent mid-turn? | **It is merged.** Two sends → **one** `result` (`num_turns=2`). Confirmed twice (cancelling and additive phrasings). **The first send never gets its own result.** |
| 5 | Can a running turn be interrupted? | **Yes.** `control_request`/`interrupt` → instant `success` (with `still_queued`); the turn ends `error_during_execution`, `is_error=true`, `result=null`; **the session survives**. |

And by reading a real transcript tree:

- **No `result` entries exist in the transcript.** Types are `user`, `assistant`, `attachment`,
  `last-prompt`, `queue-operation`.
- **Assistant entries carry full `usage`** (input/output/cache tokens, `iterations`, `service_tier`),
  plus `model`, `stop_reason`, `stop_details`, `diagnostics`. A `toolDenialKind` key exists.
- The record is a **tree**: `<session>.jsonl` + `<session>/subagents/agent-*.jsonl` + `.meta.json`.
- The projects-dir path encoding is **lossy**: `/` → `-`, so `/a-b` and `/a/b` collide.

**Probe 4 is decisive. The CLI decides turn boundaries, and a turn is not a function of a `send`.**
Any API promising one result per send promises what the protocol refuses to honour.

## Decision

**A session is a stream of messages plus a way to inject user input. Turns are not modelled.
One-shot queries keep their turn framing, because there it is true.**

1. **`Session` drops turns.** `send(input)` is **fire-and-forget**: it writes and returns. No `Turn`,
   no `TurnId`, no per-send promise, no correlation, **no `await` primitive**. Mid-turn injection —
   which the CLI supports, and which users expect from the terminal — is the reason a per-send
   promise cannot exist: probe 4 shows it would never resolve for the first send.
2. **`ClaudeCode.query` / `ask` / `queryResult` keep awaiting a single result.** One process, one
   prompt, one result, then death. The framing is honest there. **The error was inheriting it across
   the session boundary, not the framing itself.**
3. **Completion is readable state, not an awaited event.** The reader folds `origin`-absent results
   into a monotone counter behind a `Ref`; consumers read or observe it. A monotone predicate over
   always-readable state has **no window to race** and **needs no consumer to leak**. That drop-
   immunity is what a per-turn `Promise` was providing; a `Promise` was one implementation of the
   property, not the property itself.
4. **One internal reader, public fan-out via a `Hub`.** `messageQueue` is a `Queue`, so
   `ZStream.fromQueue` gives *competing* consumers: `stream` today has an **undocumented
   exactly-one-consumer constraint**, and two concurrent readers would silently split the stream.
   The library owns the single reader and broadcasts. This resolves the constraint by construction
   rather than by documentation.
5. **Real `interrupt`, via the control protocol.** `control_request`/`control_response` with
   `request_id` correlation is a channel we do not model at all. Without it a consumer can only stop
   *reading* while the agent keeps working — which looks like an interrupt and is not one.
6. **Parse totally, destroy nothing.** The `result` message carries **21 fields; we model 9**. Add
   `UnknownMessage(type, json)` — today `parseMessage`'s `case _ => None` silently discards
   `rate_limit_event` and 7 system subtypes (observed in a 22-message sample). Add `origin`
   (key-absent ≠ null; keep `ResultOrigin` open), `MessageId` (the vendor's `uuid`),
   `parentToolUseId` (the join key to subagent transcripts), and a real `TokenUsage` — `usage` is
   currently flattened to `Map.empty`, so **token counts have never survived**. `core/log`'s
   `RawLogEntry` is the pattern: unknown data survives as raw rather than vanishing.
7. **Structured, round-trippable user input.** `UserInput(text, context, channel)` encoded as
   **content blocks** — one per item — so verbatim user text never concatenates with caller-supplied
   context. Probe 3 proves the transcript preserves the structure, so `decode(encode(i)) == Some(i)`
   holds **by construction**: no grammar of ours, no escaping, injection impossible. (A consumer's
   ad-hoc `s"<interactive>$msg</interactive>"` breaks the moment a user types `</interactive>`.)
8. **`ConversationArchive`: custody, not attribution.** Locate (**by encoding a known cwd — never by
   decoding**, the encoding is lossy), read, and mirror the session **tree** (a file-only mirror
   would silently drop every subagent transcript). Tolerate vendor drift via `RawLogEntry`. **No
   attributed store and no result journal**: assistant entries already carry usage, model and stop
   reasons, so the result envelope is not the unique record it appeared to be. **The library must not
   grow a consumer's domain identifiers.**
9. **ZIO module first.** `direct`/`effectful` follow once the ZIO contract is proven. Cross-module
   cost is **~2 production lines + ~8–12 test sites, one compile-fix pass**: all three parsers
   delegate to `CoreJsonParser`, and `direct` already uses a field-safe pattern idiom. Only the
   `core` model wave touches three modules. Defaulted fields save *construction* sites, not
   positional patterns.
10. **No multi-agent generalization yet.** The shape is portable; types stay Claude-named. A second
    agent (pi, opencode) is the forcing function. An abstraction with one implementation is a guess;
    with two it is a design.

## Consequences

- **Breaking change** for session consumers: `Session.stream` and `send(String)` are deleted. The
  one-shot `query` API is unaffected.
- **The docstring goes first, today.** `"Must be called after send"` is the sentence that cost a
  consumer a week. Correct it (and add a hazard note to `direct`/`effectful`'s `Session.stream`)
  before the modules that carry it are rewritten.
- **The library gains a writer** — but only for its own mirror of the vendor's tree, never for an
  attributed store.
- **Consumers stop building the reader.** The routing, sink and out-of-turn machinery a consumer had
  to bolt onto a foreign stream — and the three separate races that grew out of it — move inside,
  where the reader and the routing state share a fiber and the races are unreachable rather than
  fixed.
- **`origin` is promoted from unparsed to load-bearing**: it is what keeps a background task's result
  from being mistaken for a turn's completion.

## What this ADR does NOT settle

- **`permission_denials` as a payoff.** Observed only as `[]`. The name suggests it; we do not know
  what populates it or a populated entry's shape. Decision 6 stands without it.
- **The `events` buffer size, and "drops are implausible".** Reasoned, never measured under load. A
  stated contract parameter, not a proven bound.
- **Whether transcript-as-history is affordable.** The latency probe is unrun, and the cost rose once
  we found the record is a tree. The direction is settled; the number is not.
- **`ResultOrigin`'s domain.** One `kind` sampled. Keep the enum open; do not enumerate.
- **`ConversationArchive`'s signatures.** The *constraints* are settled (tree not file; encode never
  decode; `result` is stream-only). The method shapes are a sketch, and `mirror`'s append-awareness
  assumes vendor append-only behaviour we have not verified.
- **The residual send-window race.** `origin` closes the `ResultMessage` half only; out-of-turn
  *assistant* messages carry no discriminator (probe-verified). Narrower than a positional
  correlation, not zero.

## Rejected alternatives

- **Fail-fast when a turn is in flight.** Preserves a per-send promise by forbidding what the CLI
  allows, and kills mid-turn injection.
- **A library-owned send queue** (hold the message, write at turn end). Preserves pairing but injects
  at turn *end* rather than the CLI's next opportunity — losing the point of the feature.
- **Keep turns, document the merge hazard.** A contract with a hang in it. "Usually resolves" is how
  the original defect happened.
- **Lossless `outOfTurn` / per-turn message queues.** An unbounded queue awaiting a consumer who may
  never come is a leak; and under transcript-as-record nothing needs to persist them.
- **A durable result journal in the library.** Withdrawn on evidence: the transcript's assistant
  entries already carry usage, model and stop reasons.

## Artifacts

`docs/design/session-contract-proposal.md` — the full 882-line proposal this compresses, with
per-consumer `file:line` grounding, the sequenced wave plan and the trade-off analysis.
`docs/design/probes/` — the probe scripts behind the table above.
