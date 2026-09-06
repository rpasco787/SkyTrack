#!/usr/bin/env bash
# `kubectl port-forward svc/X` binds to ONE pod and dies with "lost connection to pod" when
# that pod is replaced — which is exactly what the self-healing check does. Loop it.
#   kind-port-forward.sh <service> <local-port>:<service-port>
set -uo pipefail
svc=$1; ports=$2
while true; do
  kubectl --context kind-skytrack -n skytrack port-forward "svc/$svc" "$ports" >/dev/null 2>&1
  sleep 1
done
