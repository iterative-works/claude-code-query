<!-- PURPOSE: Library maintainers' response to the consumer's session-history gap report (read path, mirror, resume, legacy decode) -->
<!-- PURPOSE: States which gaps are accepted for library work, what ships in 0.5.0-SNAPSHOT, and what stays consumer-side -->

# Response: session-history gaps in `claude-code-query` 0.5.0-SNAPSHOT

To the agent integrating the `procedures` dashboard — your gap report was reviewed against the
code and, where it asserted CLI behaviour, probed against the real CLI. Verdicts and next
actions per gap:

## Gap 1 (no tail/pagination) — ACCEPTED, library fix in progress

Real and structural, as you said. Landing in the next `0.5.0-SNAPSHOT` publish:

- **Tail read**: fetch the last N main-thread entries without parsing the file from byte 0
  (backward block read from EOF).
- **Continuation for older pages**: each page carries an opaque offset token; "load older" reads
  backward from it. Offset-token paging, not `entriesBefore(uuid)` — uuid-keyed pagination would
  need an index the chat use case doesn't require.

Consumer impact: drop the ring-buffer fold plan; page-load cost becomes O(page), independent of
transcript size. Exact signatures will be in the `ConversationArchive` scaladoc when the snapshot
republishes.

## Gap 2 (write-only mirror, flat layout) — ACCEPTED, library fix in progress

Confirmed exactly as you described, including the layout mismatch (`archiveDir/<sid>.jsonl`,
encoded-cwd segment lost). Landing in the same publish:

- `mirror` preserves the vendor projects layout under `archiveDir` (`<encoded-cwd>/<sid>.jsonl`
  plus the subagent tree).
- The read path (`forSession` / `entries` / `subagentEntries`, and the new tail reads) falls back
  vendor-first-then-mirror, so a `cleanupPeriodDays`-pruned session stays readable through the
  same API with no second `ArchiveConfig` gymnastics.
- A pruned vendor tree never causes the mirror to be truncated — custody is one-directional.

Consumer impact: configure the archive on your sessions (`ClaudeCode.session(options,
archive = Some(ArchiveConfig(...)))`) and history survives vendor pruning end-to-end.

## Gap 3 (resume forks the session id) — PROBED; NOT REAL on current CLI, no action needed

Probed against CLI 2.1.210 (`docs/design/probes/resume-session-identity.md`):

- `--resume <id>` **keeps the session id and appends to the same transcript file** (verified
  across consecutive resumes; context preserved).
- Forking requires the explicit `--fork-session` flag — and even then the forked file carries the
  **full copied conversation content** under the new id, so `entries(latestKnownId)` always
  renders the whole conversation. No chain-walking exists or is needed.

Consumer actions: keep re-saving the result's `sessionId` after every turn (correct
belt-and-braces, and it keeps your pointer current if anything ever forks); never pass
`--fork-session` when continuing a conversation.

## Gap 4 (legacy entries don't decode) — BY DESIGN, consumer-side fallback is correct

`UserInput.decode` returning `None` for anything `encode` didn't produce is the round-trip law
doing its job; a lenient decode would reintroduce guessing about the legacy concat encoding. One
correction to your sketch: on `None`, render the entry's **full content**, not `blocks.last` —
identical for legacy single-block entries, but `blocks.last` silently drops blocks on any future
non-decodable multi-block entry.

## Sequencing reminders

- Keep `FileMessageStorage` until your sends go through `UserInput` in production — raw
  pre-enrichment user text exists only in your copy until then (proposal §4 migration order).
- The result envelope (cost, `usage` totals, `origin`, `permission_denials`, timings) remains
  **stream-only** by ADR decision: capture it live from `state`/`events` if you want per-result
  cost history; no transcript or mirror will ever reconstruct it.

A note will follow when the snapshot with Gaps 1+2 is republished to the local repository.
