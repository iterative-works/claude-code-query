<!-- PURPOSE: The value-axis compass for cc-chat's one-chat-vertical goal — what must change and what must hold -->
<!-- PURPOSE: Judges every slice. References ADR 0002 / ADR 0001; solution-shape is hypothesis, tested by slices -->

# cc-chat — Standing Intent: One chat vertical for every house project

*Sits within ADR 0002 (the chat UI architecture decision); references its principles, does not restate them. Solution-shape is hypothesis, tested by slices.*

## Goal — the measurable change

Any house project gets the agent chat by adding one dependency and embedding one element. The session-contract knowledge — the fold, resync, sub-agent demotion, completion rules — lives in this SDK exactly once and is never re-implemented per project.

**How we'll know it worked** (after real use): `procedures` runs its dashboard chat on `<cc-chat>` with **both** of its existing chat generations deleted, and survives a week of real use without anyone reaching back into hand-rolled DOM patching.

## Before → after

- **Before:** every consumer hand-rolls chat (`procedures` carries two coexisting generations); a dropped SSE connection silently loses bubbles; fold knowledge is re-learned per project.
- **After:** one versioned session-over-HTTP contract with a self-contained reference element, shipped from this repo; embedding is a dependency bump; resync is structural (history tail + resubscribe).

The delta above *is* the intent. Anything it doesn't name is out of scope by default.

## Invariants (must hold)

- **Conversation-data rule** *(stated)*: real conversation transcripts never enter the repo, published artifacts, bundled samples, or docs — fixtures and demos are synthetic; real transcripts touch the system only at runtime.
- Nothing that leaves a mark — publish, release, merge, push to a consumer repo — happens without Michal's approval.

## Preferences (matter, but negotiable)

- The CI bundle-size gate sits just above the measured size, never at the 400 KB design ceiling.
- Laminar stays inside the element while its delta stays trivial (~27 KB at adoption).

## Deliberately don't-care (delegated / deferred to slices)

*(agent-proposed)* Endpoint pagination shape, element styling/theming internals, markdown library choice, virtualization strategy.

These are solution-shape: discovered by building, not decided here.

## Fit — what we're listening for (found by reacting, not specifiable)

**Helpful** = a consumer developer embeds the element and the chat *just behaves* — bubbles fold correctly, resync is invisible, no session-contract knowledge needed. **Foreign** = a consumer reaching around the element (patching its DOM, re-parsing vendor JSONL) to get what they need. The tuning of what the element must expose versus own is fit — found by reacting to real slices, not written down now.

## Standing on (referenced, not restated)

- ADR 0002 — contract-first architecture, Scala.js reference element, asset-first delivery from the jar, measured ceilings, deferred decisions with triggers.
- ADR 0001 — the session-is-a-stream contract the fold implements.
- House TDD discipline — the spike's exemption is closed; the `ui` module is test-first.
- Thin spot: Scala.js custom-element patterns are not yet in the body of knowledge; the spike's findings live in ADR 0002 and should graduate to the guide as slices confirm them.
