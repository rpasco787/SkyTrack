#!/usr/bin/env bash
# Stop the port-forwarders started by kind-up.sh and delete the kind cluster.
set -euo pipefail
CLUSTER=skytrack
STATE_DIR="${TMPDIR:-/tmp}/skytrack-kind"

for pidfile in "$STATE_DIR"/pf-*.pid; do
  [[ -f "$pidfile" ]] || continue
  pid=$(cat "$pidfile")
  # Kill the reconnect loop *and* the kubectl child it is currently running.
  pkill -TERM -P "$pid" 2>/dev/null || true
  kill -TERM "$pid" 2>/dev/null || true
  rm -f "$pidfile"
done

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  kind delete cluster --name "$CLUSTER"
else
  echo "kind-down: no cluster named '$CLUSTER'"
fi
