<!-- PURPOSE: ADR adopting the agent chat UI architecture: a versioned session-over-HTTP contract as the durable asset, and a self-contained Scala.js custom element as its reference client -->
<!-- PURPOSE: Records the 2026-08-07/08 design sessions, the spike's measured bundle numbers, the asset-first delivery rule, and the deferred decisions with triggers -->

# ADR 0002: A session-over-HTTP contract, rendered by a self-contained Scala.js chat element

**Status:** Accepted (2026-08-08) — decided with Michal across two design sessions and validated by the `uispike` spike on this branch
**Date:** 2026-08-08
**Author:** Michal Příhoda, with Claude (dev)
**Relates to:** [ADR 0001](0001-session-is-a-stream-not-turns.md) (the session contract this renders)

## Context

Every project that embeds the agent solves the same problems: render `Message` and
`ContentBlock`, stream them into a page, show what the agent is doing, and let the user
interrupt. That work is coupled to *this* SDK's model, not to any product domain. The goal is
one well-tuned chat component supplied by this library to all our projects — with the whole
vertical: the wire contract, the server endpoints, and the client.

### What the prior art teaches

The `procedures` dashboard is the first consumer. Its chat carries hard-won session-contract
knowledge (whole-message bubbles, sub-agent demotion by `parentToolUseId`, the
result-text-as-fallback rule, escaped markdown for untrusted transcripts, a drop-immune
spinner clear folding `stateChanges`). It also demonstrates the failure modes to design out:

1. **No resync.** The live view is built from append-only server-pushed DOM patches. A dropped
   SSE connection silently loses bubbles; the DOM is a materialized view that cannot be
   rebuilt without a full page reload. The "drop-immune spinner" exists precisely because the
   architecture loses events — it patches one symptom, not the class.
2. **DOM surgery by string.** Hand-rolled JSON in `patchSignalsRaw`, `executeScript` scroll
   calls, an implicit unversioned DOM contract (`#conversation-messages`, signal names).
3. **Protocol knowledge fused with presentation.** The fold logic lives inside a
   Tailwind-emitting renderer; every new consumer re-learns it.
4. **Rendering-stack churn.** Two chat generations coexist there (`SseEventMapper`/`chat.js`
   and `WorkerChatDatastarSse`); the house has already walked Web Awesome → HTMX → Datastar,
   and Datastar's SSE wire format broke once (`datastar-merge-*` → `datastar-patch-*`).
   The rendering layer is empirically the unstable layer. This component must not be bound to
   whichever patching library is current.

## Decision

### 1. The durable asset is the contract, not the pixels

The library defines a versioned session-over-HTTP contract, SDK-shaped and independent of any
rendering technology:

- **history** — pageable transcript entries (backed by `ConversationArchive` tail reads)
- **events** — a live stream (SSE) of `Message` JSON
- **send** / **interrupt** — control operations on the session
- later: the tool-approval round trip (blocked on SDK support, see Deferred)

Endpoints are defined with Tapir in a shared module: the server interprets them as routes, a
Scala.js client derives calls from the same definitions. The live stream is the exception —
Tapir's client streaming on Scala.js is weak, so the client wires browser-native
`EventSource` to the shared circe decoders: hand-wired, still typed.

Reconnect is structural, not heroic: resync = fetch the history tail + resubscribe. Wire
tolerance is inherited from the model's total parsing (`UnknownMessage`, `RawLogEntry`), so
version skew degrades gracefully instead of breaking.

### 2. The reference client is a self-contained custom element

`<cc-chat history-url=… events-url=…>` — a rich client-rendered web component that owns its
state: it loads history, follows the event stream, folds messages to view entries, and
renders them. The host page is plain SSR of any flavour (Datastar, htmx, SPA, static) and
does not care about the rich content; embedding is one script tag and one element.

The session-contract knowledge (the fold: result-text fallback, sub-agent demotion,
origin-aware completion) lives in one pure, shared place — never re-implemented per renderer
or per project.

### 3. Implemented in Scala.js, sharing the core model

The deciding argument over TypeScript: the *behavioural* logic is the SDK's hardest-won part,
and in Scala.js it is the same source compiled twice — the wire seam and the fold are
compiler-enforced, not codegen-synced. Our consumer apps are Scala; the vertical
(model + endpoints + client) ships from one codebase. The costs (bundle size, JS-library
facades, slower dev loop) were accepted with open eyes, ceiling below.

`core` is split accordingly: platform-free sources (model, parsing, `CLIError`, the log entry
model/parser) live in `core/shared/src`, compiled by both the JVM `core` module and the new
`core.js` Scala.js module. JVM-bound code (os-lib, file IO) stays in `core/src`.

### 4. Laminar inside the element

Measured delta: **27 KB gzip** — under the agreed 40 KB rule, so house fluency and velocity
win over raw DOM. The element boundary contains the choice; internals can change without any
consumer noticing.

### 5. Asset-first delivery, from the jar

The linked bundle ships **inside the published jar** as a classpath resource under a
content-hashed immutable path, served by the host app. The asset version is therefore the
dependency version — client/server lockstep by construction, no separately-pinnable asset,
and the hash kills stale browser caches. The `_sjs1_3` library artifact is published from the
same codebase for consumers that want link-time extension in their own Scala.js frontend.

Two Scala.js bundles on one page cannot share Scala types, so any *runtime* extension API is
JS-typed by nature; the *primary* extension seam is instead the protocol: an entry can carry
optional server-rendered HTML for a block, and projects extend in server-side Scala. The
element guards `customElements.define` against double registration.

## What the spike measured

Three link targets (`uispike`, Scala.js 1.20.2, `fullLinkJS`, gzip), agreed ceiling 400 KB:

| target | gzip | reading |
|---|---|---|
| bare element (floor) | 7.9 KB | custom element + registration, no model |
| + shared model, circe, both parsers | 347 KB | of which **~178 KB is scala-java-time** |
| + Laminar | 375 KB | **Laminar delta: 27 KB** |
| realistic (no scala-java-time, see below) | ~197 KB | before a markdown/highlight facade |

Verified in Chrome against a toy host: the element registers (guarded), renders a canned
history and live SSE frames with correct fold semantics (no result double-render, fallback
renders, sub-agent demoted), renders a **real 1,128-line transcript** (122 rows, clean
console, tolerant parsing), and survives DOM moves without re-wiring.

### Spike findings that became decisions

- **The history endpoint serves normalized entries, not raw vendor JSONL.** `Instant` in
  `ConversationLogEntry` drags `scala-java-time` into the browser: ~178 KB gzip, half the
  bundle. Serving a wire DTO (timestamps as strings/epoch) removes it — and is better
  contract design anyway: the browser is shielded from vendor-format drift, which stays a
  server-side concern where the tolerant parsers live.
- **The host must serve the bundle compressed.** 375 KB gzip is 2.4 MB raw; the delivery path
  needs gzip/brotli, not just the hashed name.
- **`connectedCallback` re-fires on DOM moves**; the element wires itself once behind a flag.

## Consequences

- New artifacts: `claude-code-query-core_sjs1_3` now; the `ui` module (element + endpoint
  definitions + server glue) as the real successor of the spike.
- The repo takes on a Scala.js toolchain. Dev-loop costs are real and accepted: `fastLinkJS`
  dev bundles are huge (complicates remote dev), JS libraries we adopt (markdown, syntax
  highlighting) need hand-maintained facades.
- Consumer upgrades are one dependency bump; the compiler checks their server-side
  extensions against the new model. An SDK model change relinks the frontend bundle at build
  time instead of breaking a deployed UI at runtime.
- `procedures` adopts the component by replacing both of its chat generations; two stacks in
  one application must not survive the migration.
- The spike code (`uispike/*`) is throwaway: it skipped TDD deliberately and is superseded by
  the `ui` module, which is built test-first like everything else.

## First slice

The transcript viewer: the `ui` module with the normalized-history endpoint and the element
rendering archive pages (no live stream, no session lifecycle). It proves the contract, the
delivery path, and the shared fold in a consumer. The live stream (events/send/interrupt) is
the second slice.

## Deferred decisions (with triggers)

- **Tool approval UI** — blocked: the SDK does not answer `can_use_tool` control requests.
  Trigger: SDK support lands (ADR 0001's control-protocol machinery is the natural home).
- **Runtime JS renderer registry** — deferred. Trigger: a project needs client-side
  interactive rendering for a custom tool that the server-rendered-HTML seam cannot express.
- **Markdown / syntax-highlight facade choice** — deferred to the first slice that renders
  markdown; the escaping-by-default rule from the prior art is non-negotiable when it lands.
- **Transcript virtualisation** — deferred. Trigger: real transcripts where render-all
  measurably hurts (the 122-row real page renders instantly today).
- **`direct`/`effectful` live bindings** — only `zio` has `Session`; the viewer path is
  effect-free and serves all three.

## References

- Spike: commits `6d5ebd3` (core split), `db8027f` (element variants + measurements),
  `f24a49a` (toy host + browser verification) on `spike/cc-chat-scalajs`
- Prior art: `procedures` `dashboard/src/chat/*`, ADR 0005 (Datastar SSR stack — the host
  pages this element embeds into; the element itself is host-stack agnostic)
- ADR 0001 — the session contract the fold implements
