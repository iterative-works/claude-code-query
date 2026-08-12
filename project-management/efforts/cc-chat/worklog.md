<!-- PURPOSE: Living thread of the cc-chat effort — READ FIRST when resuming this work -->
<!-- PURPOSE: Where we are + dated log; the conversation is disposable, this file is durable -->

---
title: cc-chat — Worklog
created: 2026-08-12
status: living — READ THIS FIRST when resuming this work
companion: intent.md, decisions.md, map.md (same directory)
---

# cc-chat — Worklog

## Where we are right now

Effort framed; no slice active yet. PR #60 (`spike/cc-chat-scalajs` → main) is
open and green: it carries the core split for cross-building, ADR 0002, the
throwaway `uispike` spike with measured bundles (217.9 KB gzip laminar,
gate 250 KB), the Moment re-basing onto released `iw-support-time:0.2.0`, and
this effort directory. Next unblocked step: merge #60, then `sl-define
"transcript-viewer"`.

## Log

- **2026-08-12 — Effort framed.** Standing Intent authored from ADR 0002
  through the sl-frame dialog. Settled: effort name `cc-chat`; trust boundary
  = conversation-data rule (synthetic fixtures only; real transcripts runtime
  only) plus the default leave-a-mark approval gate; success read =
  `procedures` migrated off both of its chat generations after a week of real
  use; CC-D1 = normalized history entries (Michal's multi-agent rationale
  recorded). Three candidate slices seeded (transcript-viewer → live-stream →
  procedures-migration); ADR 0002's deferred items carried into Parked.
  Prerequisites already in place: iw-support 0.2.0/0.2.1 released with the
  dependency-free `iw-support-time`; forge registry resolution verified in CI
  on PR #60.
