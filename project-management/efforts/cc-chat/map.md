<!-- PURPOSE: Work map for the cc-chat effort — candidates, active, done, parked -->
<!-- PURPOSE: Items reference issues, never restate them; no unmet after: + no shared surface = parallel-eligible -->

# cc-chat — Map

Item metadata: `type:` ag | wf | dx | sl | manual · `after:` dependency item
slugs · `repo:` when the work lands in a sibling repository · a reference once
active — `issue: <ISSUE-ID>`, or `slice: <NN>-<slice-slug>` for a local slice
under this effort's `slices/`.

## Items

- [ ] **transcript-viewer** (type: sl) — a human views an archived conversation
  in the browser through the published vertical: the `ui` module's normalized
  history endpoint plus the element rendering an archive page.
- [ ] **live-stream** (type: sl, after: transcript-viewer) — a human follows a
  running session live and can send/interrupt: the events SSE stream and
  control operations wired through the same contract.
- [ ] **procedures-migration** (type: sl, after: live-stream, repo:
  procedures) — `procedures` runs on `<cc-chat>`; both of its existing chat
  generations are deleted. This is the effort's success read.

## Parked

- Tool-approval UI — blocked: the SDK does not answer `can_use_tool` control
  requests (ADR 0002 trigger).
- Markdown / syntax-highlight facade — trigger: the first slice that renders
  markdown; escaping-by-default is non-negotiable when it lands.
- Transcript virtualization — trigger: a real transcript where render-all
  measurably hurts.
- `direct`/`effectful` live bindings — only `zio` has `Session`; the viewer
  path is effect-free and serves all three.
- Runtime JS renderer registry — trigger: a project needs client-side
  interactive rendering the server-rendered-HTML seam cannot express.
