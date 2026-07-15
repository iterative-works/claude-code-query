<!-- PURPOSE: Records the resume-identity probe: --resume keeps the session id and file; forking is opt-in and copies history -->
<!-- PURPOSE: Settles the consumer's "does resume fork the session id?" question raised against ADR 0001's archive design -->

# Probe: does `--resume` fork the session identity?

**Date:** 2026-07-15 · **CLI:** 2.1.210 · **Script:** `probe-resume-identity.sh` (isolated `CLAUDE_CONFIG_DIR`, real CLI, real login)

## Question

A consumer resuming evicted sessions with `--resume <id>` re-saves `response.sessionId` after
every turn, as though the id can change underneath it. If a resumed CLI minted a new
`<newid>.jsonl`, `ConversationArchive.entries(storedId)` would return one fragment of the
conversation and nothing would link the fragments.

## Findings

| # | Observation | Evidence |
|---|---|---|
| 1 | `--resume <id>` **keeps the same session id** and **appends to the same `<id>.jsonl`**. | Two consecutive resumes: `session_id` identical all three turns; exactly one transcript file, growing; context preserved across resumes. |
| 2 | Forking is **opt-in**: `--fork-session` mints a new id and a new file. | `--resume <id> --fork-session` returned a new `session_id` and created a second `.jsonl`. |
| 3 | A forked file **carries the full prior conversation content**, rewritten under the new id. | The forked transcript contained every prior `user`/`assistant` entry plus the fork's own turn; only bookkeeping entry types (`queue-operation`, `last-prompt`) differ. No in-file link back to the source session exists — and none is needed, since content is copied. |

## Consequences

- **No chain-walking is needed.** `entries(latestKnownId)` always yields the whole conversation,
  resumed or forked.
- Re-saving the result's `sessionId` after each turn remains correct belt-and-braces (and is what
  keeps the pointer current if anything ever forks).
- Continuation must not pass `--fork-session`; that flag is for deliberate branching.
- Version caveat: verified on CLI 2.1.210. The consumer's defensive re-save suggests older CLIs
  behaved differently; the re-save habit covers that either way.
