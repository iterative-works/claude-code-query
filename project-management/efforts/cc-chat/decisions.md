<!-- PURPOSE: Numbered decisions for the cc-chat effort — append-only, superseded never edited -->
<!-- PURPOSE: Each decision carries a propagation checklist; unchecked boxes are visible drift -->

# cc-chat — Decisions

IDs are stable (`CC-Dn`). Change a decision by appending a superseding one and
marking the old `SUPERSEDED by CC-Dn`; never edit an accepted entry.

## CC-D1 — History endpoint serves normalized entries, not raw vendor JSONL (2026-08-12) — ACTIVE

**Decision:** The history endpoint serves entries in a shape this contract
owns; raw Claude Code JSONL never crosses the wire to consumers.

**Why:** The endpoint is the versioned contract — serving vendor JSONL leaks
Anthropic's format and its drift to every consumer. Decisive addition from
Michal: the contract may later carry transcripts from agentic tools other
than Claude Code — different vendors, similar message kinds — so the entry
shape must be ours. The bundle-size argument for raw passthrough died with
the Moment re-basing (ADR 0002); what remained was contract hygiene, settled
here.

**Propagates to:**
- [ ] `docs/adr/0002-chat-ui-module.md` — close the normalized-vs-raw open
  question with this outcome.
- [ ] transcript-viewer slice card (when `sl-define` runs) — the first
  failing test is written against the normalized shape.
