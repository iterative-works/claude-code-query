#!/usr/bin/env python3
# PURPOSE: Probe whether the Claude CLI emits stream-json output out-of-turn (with no send in flight).
# PURPOSE: Answers PROC-603 CLARIFY 1 by timestamping every raw stdout line from a real session.

import json, subprocess, sys, threading, time, os

# Exact session flags: SessionProcess.scala:36 prepends --verbose to
# CLIArgumentBuilder.buildSessionArgs (claude-code-query 0.4.1).
ARGS = [
    "claude",
    "--verbose",
    "--print",
    "--input-format", "stream-json",
    "--output-format", "stream-json",
    "--permission-mode", "bypassPermissions",
]

LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "raw_stdout.jsonl")

# A prompt engineered to end the turn while work continues in the background.
PROMPT = (
    "Launch a background Agent (subagent_type 'general-purpose') with this exact task: "
    "'Sleep about 45 seconds by running: sleep 45. Then reply with exactly the word BANANA.' "
    "Do NOT wait for it. The moment the Agent tool returns its launch confirmation, "
    "immediately end your turn by replying exactly: 'STARTED, will report back.' "
    "Do not say anything else."
)

t0 = time.time()
lines = []


def stamp():
    return round(time.time() - t0, 3)


proc = subprocess.Popen(
    ARGS, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    text=True, bufsize=1,
)


def read_stdout():
    for line in proc.stdout:
        rel = stamp()
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
            typ = obj.get("type")
            sub = obj.get("subtype", "")
        except Exception:
            typ, sub, obj = "UNPARSEABLE", "", {"raw": line[:200]}
        lines.append({"t": rel, "type": typ, "subtype": sub, "obj": obj})
        preview = ""
        if typ == "assistant":
            try:
                blocks = obj["message"]["content"]
                preview = " | ".join(
                    (b.get("text", "")[:60] if b.get("type") == "text"
                     else "TOOL_USE:" + b.get("name", "?"))
                    for b in blocks
                )
            except Exception:
                preview = "?"
        elif typ == "result":
            preview = str(obj.get("result", ""))[:60]
        ptui = " parent_tool_use_id=" + str(obj.get("parent_tool_use_id")) if "parent_tool_use_id" in obj else ""
        print(f"[{rel:8.3f}s] {typ:<12} {sub:<18} {preview}{ptui}", flush=True)


def read_stderr():
    for line in proc.stderr:
        if line.strip():
            print(f"[{stamp():8.3f}s] STDERR       {line.strip()[:160]}", flush=True)


threading.Thread(target=read_stdout, daemon=True).start()
threading.Thread(target=read_stderr, daemon=True).start()

# Wait for the CLI's init message so we can learn the real session_id.
deadline = time.time() + 30
session_id = None
while time.time() < deadline:
    for rec in lines:
        if rec["type"] == "system" and rec["subtype"] == "init":
            session_id = rec["obj"].get("session_id")
            break
    if session_id:
        break
    time.sleep(0.1)

print(f"\n>>> init session_id={session_id}\n", flush=True)

msg = {
    "type": "user",
    "message": {"role": "user", "content": PROMPT},
    "parent_tool_use_id": None,
    "session_id": session_id or "pending",
}
print(f">>> [{stamp():.3f}s] SENDING single user message; nothing further will be sent.\n", flush=True)
proc.stdin.write(json.dumps(msg) + "\n")
proc.stdin.flush()

# Watch for 150s WITHOUT sending anything else. Any `result` after the first is
# by definition out-of-turn output: nobody asked a second question.
WATCH = 150
print(f">>> Watching {WATCH}s with NO further input. A 2nd 'result' == out-of-turn emission.\n", flush=True)
time.sleep(WATCH)

with open(LOG, "w") as f:
    for rec in lines:
        f.write(json.dumps(rec) + "\n")

results = [r for r in lines if r["type"] == "result"]
print("\n" + "=" * 72)
print(f"VERDICT: {len(results)} 'result' message(s) seen from ONE user message.")
for i, r in enumerate(results, 1):
    print(f"  result #{i} @ {r['t']}s  subtype={r['subtype']!r}  "
          f"num_turns={r['obj'].get('num_turns')}  text={str(r['obj'].get('result',''))[:70]!r}")
if len(results) > 1:
    print("\n  => CONFIRMED: CLI emits a full 'result' out-of-turn. runOnce's next")
    print("     send would consume result #2 and return it as that turn's answer.")
else:
    print("\n  => No out-of-turn result observed in this run.")
print(f"\nRaw lines: {LOG}")
print("=" * 72)

proc.kill()
