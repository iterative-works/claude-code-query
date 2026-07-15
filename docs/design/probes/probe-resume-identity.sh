#!/usr/bin/env bash
# PURPOSE: Probes whether `claude --resume <id>` forks the session id and transcript file
# PURPOSE: Run against a real CLI; needs a logged-in credentials file to copy into the isolated config
set -euo pipefail

D=$(mktemp -d)
mkdir -p "$D/config" "$D/cwd"
# The probe isolates CLAUDE_CONFIG_DIR, so login must be copied in explicitly.
cp "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/.credentials.json" "$D/config/"

run() (cd "$D/cwd" && CLAUDE_CONFIG_DIR="$D/config" claude -p "$1" --output-format json "${@:2}")

sid() { python3 -c "import json,sys; print(json.loads(sys.stdin.read())['session_id'])"; }

r1=$(run "Remember the word 'pineapple'. Reply OK.")
sid1=$(echo "$r1" | sid)
r2=$(run "What word did I ask you to remember? Reply with just the word." --resume "$sid1")
sid2=$(echo "$r2" | sid)
r3=$(run "Reply OK." --resume "$sid1")
sid3=$(echo "$r3" | sid)
r4=$(run "Reply OK." --resume "$sid1" --fork-session)
sid4=$(echo "$r4" | sid)

echo "sid1=$sid1"
echo "sid2=$sid2 (resume #1 — expect same as sid1)"
echo "sid3=$sid3 (resume #2 — expect same as sid1)"
echo "sid4=$sid4 (fork — expect NEW id)"
echo "--- transcript files ---"
find "$D/config/projects" -name '*.jsonl' -printf '%f %s bytes\n' | sort
echo "--- user entries per file (fork should carry the FULL prior conversation) ---"
python3 - "$D/config/projects" <<'EOF'
import json, glob, os, sys, collections
for f in sorted(glob.glob(sys.argv[1] + '/*/*.jsonl')):
    lines = [json.loads(l) for l in open(f) if l.strip()]
    counts = collections.Counter(e.get('type') for e in lines)
    print(os.path.basename(f)[:8], dict(counts))
EOF
echo "probe dir: $D"
