#!/usr/bin/env python3
# PURPOSE: Probes two CLI capabilities the library cannot currently express: sending a user
# PURPOSE: message while a turn is in flight, and interrupting a running turn via control_request.
#
# MODE=midturn   send a long task, then send a 2nd user message while it runs.
#                Q: does the CLI reject it, queue it, or interleave? Do results arrive FIFO?
# MODE=interrupt send a long task, then send a control_request/interrupt.
#                Q: is it accepted? does the turn end? does the SESSION survive afterwards?

import json, subprocess, sys, threading, time, os

MODE = os.environ.get("MODE", "midturn")

ARGS = [
    "claude", "--verbose", "--print",
    "--input-format", "stream-json",
    "--output-format", "stream-json",
    "--permission-mode", "bypassPermissions",
]

T0 = time.time()
seen = []


def stamp():
    return f"{time.time() - T0:6.1f}s"


proc = subprocess.Popen(
    ARGS, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    text=True, bufsize=1,
)


def read_stdout():
    for line in proc.stdout:
        line = line.strip()
        if not line:
            continue
        try:
            o = json.loads(line)
        except Exception:
            print(f"[{stamp()}] NON-JSON: {line[:160]}", flush=True)
            continue
        seen.append((time.time() - T0, o))
        t = o.get("type")
        if t == "result":
            print(f"[{stamp()}] *** RESULT subtype={o.get('subtype')} is_error={o.get('is_error')} "
                  f"num_turns={o.get('num_turns')} origin={o.get('origin')!r}", flush=True)
            print(f"[{stamp()}]     text={str(o.get('result'))[:160]!r}", flush=True)
        elif t == "control_response":
            print(f"[{stamp()}] *** CONTROL_RESPONSE {json.dumps(o)[:300]}", flush=True)
        elif t == "assistant":
            for b in o.get("message", {}).get("content", []):
                if b.get("type") == "text" and b.get("text", "").strip():
                    print(f"[{stamp()}] assistant: {b['text'][:110]!r}", flush=True)
                elif b.get("type") == "tool_use":
                    print(f"[{stamp()}] tool_use: {b.get('name')}", flush=True)
        elif t == "system":
            print(f"[{stamp()}] system/{o.get('subtype')}", flush=True)


def read_stderr():
    for line in proc.stderr:
        if line.strip():
            print(f"[{stamp()}] STDERR: {line.strip()[:200]}", flush=True)


threading.Thread(target=read_stdout, daemon=True).start()
threading.Thread(target=read_stderr, daemon=True).start()

sid = None
for _ in range(300):
    for _, o in list(seen):
        if o.get("type") == "system" and o.get("session_id"):
            sid = o["session_id"]
            break
    if sid:
        break
    time.sleep(0.1)
print(f"\n>>> session_id={sid}  MODE={MODE}\n", flush=True)


def send_user(text):
    msg = {"type": "user",
           "message": {"role": "user", "content": [{"type": "text", "text": text}]},
           "parent_tool_use_id": None, "session_id": sid or "pending"}
    proc.stdin.write(json.dumps(msg) + "\n")
    proc.stdin.flush()
    print(f"[{stamp()}] >>> SENT user: {text[:70]!r}", flush=True)


LONG = ("Use the Bash tool to run exactly this and report the output: "
        "for i in $(seq 1 25); do echo tick-$i; sleep 1; done")

send_user(LONG)
time.sleep(4)

if MODE == "midturn":
    send_user("When you have finished that, additionally reply with the single word BANANA.")
    print(f"[{stamp()}] >>> 2nd user message sent WHILE turn 1 in flight\n", flush=True)
else:
    req = {"type": "control_request", "request_id": "req_probe_1",
           "request": {"subtype": "interrupt"}}
    proc.stdin.write(json.dumps(req) + "\n")
    proc.stdin.flush()
    print(f"[{stamp()}] >>> SENT control_request/interrupt\n", flush=True)

deadline = time.time() + 75
while time.time() < deadline:
    if proc.poll() is not None:
        print(f"[{stamp()}] !!! process EXITED rc={proc.returncode}", flush=True)
        break
    time.sleep(0.3)

# For interrupt mode: did the SESSION survive? Send a third message and see.
if MODE == "interrupt" and proc.poll() is None:
    print(f"\n[{stamp()}] >>> session-survival check", flush=True)
    send_user("Reply with exactly: ALIVE")
    t_end = time.time() + 45
    n_before = sum(1 for _, o in seen if o.get("type") == "result")
    while time.time() < t_end:
        if sum(1 for _, o in seen if o.get("type") == "result") > n_before:
            break
        time.sleep(0.3)

results = [(t, o) for t, o in seen if o.get("type") == "result"]
print(f"\n>>> VERDICT ({MODE}): {len(results)} result(s)", flush=True)
for t, o in results:
    print(f"    @{t:6.1f}s subtype={o.get('subtype')!r} num_turns={o.get('num_turns')} "
          f"origin={o.get('origin')!r} text={str(o.get('result'))[:90]!r}", flush=True)
print(f">>> control_responses: {[json.dumps(o) for _, o in seen if o.get('type') == 'control_response']}",
      flush=True)

try:
    proc.stdin.close()
except Exception:
    pass
proc.terminate()
