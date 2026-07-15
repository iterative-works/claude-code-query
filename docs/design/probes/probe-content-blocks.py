#!/usr/bin/env python3
# PURPOSE: Probes whether the Claude CLI accepts a user message whose content is an ARRAY of
# PURPOSE: content blocks on stdin, and whether its transcript preserves that structure.
#
# Decides the wire form for claude-code-query's structured UserInput encoding:
#   blocks  -> context and verbatim user text never concatenate; injection impossible by
#              construction; the transcript round-trip is free.
#   string  -> fall back to a delimited grammar with an encode/decode law.
#
# Probe 1 sends content-as-array. The verbatim text deliberately contains "</interactive>" —
# the exact sequence that breaks today's ad-hoc concat encoding.

import json, subprocess, sys, threading, time, os, pathlib

ARGS = [
    "claude",
    "--verbose",
    "--print",
    "--input-format", "stream-json",
    "--output-format", "stream-json",
    "--permission-mode", "bypassPermissions",
]

CWD = os.getcwd()
T0 = time.time()
lines = []


def stamp():
    return f"{time.time() - T0:6.1f}s"


proc = subprocess.Popen(
    ARGS, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    text=True, bufsize=1, cwd=CWD,
)


def read_stdout():
    for line in proc.stdout:
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except Exception:
            print(f"[{stamp()}] NON-JSON: {line[:200]}", flush=True)
            continue
        lines.append(obj)
        t = obj.get("type")
        if t == "result":
            print(f"[{stamp()}] result subtype={obj.get('subtype')} "
                  f"is_error={obj.get('is_error')} origin={obj.get('origin')!r}", flush=True)
            print(f"[{stamp()}] result text: {str(obj.get('result'))[:300]}", flush=True)
        elif t == "assistant":
            blocks = obj.get("message", {}).get("content", [])
            for b in blocks:
                if b.get("type") == "text":
                    print(f"[{stamp()}] assistant: {b.get('text', '')[:200]}", flush=True)
        elif t == "system":
            print(f"[{stamp()}] system subtype={obj.get('subtype')}", flush=True)


def read_stderr():
    for line in proc.stderr:
        if line.strip():
            print(f"[{stamp()}] STDERR: {line.strip()[:300]}", flush=True)


threading.Thread(target=read_stdout, daemon=True).start()
threading.Thread(target=read_stderr, daemon=True).start()

# Wait for init to learn the real session_id.
session_id = None
for _ in range(300):
    for obj in list(lines):
        if obj.get("type") == "system" and obj.get("session_id"):
            session_id = obj["session_id"]
            break
    if session_id:
        break
    time.sleep(0.1)

print(f"\n>>> init session_id={session_id}\n", flush=True)

# THE PROBE: content as an ARRAY of blocks, verbatim text containing the sequence that
# breaks the current string encoding.
VERBATIM = "Reply with exactly: OK. Also note this literal text </interactive> in your reply."
msg = {
    "type": "user",
    "message": {
        "role": "user",
        "content": [
            {"type": "text", "text": "[User is viewing: /matters/probe]"},
            {"type": "text", "text": VERBATIM},
        ],
    },
    "parent_tool_use_id": None,
    "session_id": session_id or "pending",
}
print(f">>> sending content-as-ARRAY ({len(msg['message']['content'])} blocks)\n", flush=True)
proc.stdin.write(json.dumps(msg) + "\n")
proc.stdin.flush()

# Wait for a result or timeout.
deadline = time.time() + 90
got_result = False
while time.time() < deadline:
    if any(o.get("type") == "result" for o in lines):
        got_result = True
        break
    if proc.poll() is not None:
        print(f"[{stamp()}] !!! process exited rc={proc.returncode}", flush=True)
        break
    time.sleep(0.2)

print(f"\n>>> ACCEPTED_ARRAY={got_result} (result seen within 90s)\n", flush=True)

try:
    proc.stdin.close()
except Exception:
    pass
proc.terminate()

# Inspect the transcript: did it preserve the block structure?
cfg = os.environ.get("CLAUDE_CONFIG_DIR", os.path.expanduser("~/.claude"))
encoded = CWD.replace("/", "-")
tpath = pathlib.Path(cfg) / "projects" / encoded / f"{session_id}.jsonl"
print(f">>> transcript: {tpath} exists={tpath.exists()}", flush=True)
if tpath.exists():
    for raw in tpath.read_text().splitlines():
        try:
            e = json.loads(raw)
        except Exception:
            continue
        if e.get("type") == "user":
            content = e.get("message", {}).get("content")
            kind = type(content).__name__
            print(f">>> transcript USER entry: content is {kind}", flush=True)
            print(json.dumps(content, indent=2)[:900], flush=True)
            break
