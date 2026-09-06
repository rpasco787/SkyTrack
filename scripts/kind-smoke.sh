#!/usr/bin/env bash
# Acceptance check for the kind deployment (run after scripts/kind-up.sh):
#   1. the API answers on localhost:8080
#   2. `kubectl delete pod -l app=skytrack` -> the Deployment creates a replacement
#   3. the Service has NO ready endpoint while the replacement is starting (readiness gates traffic)
#   4. the replacement becomes Ready and the API answers again
set -euo pipefail
CTX=kind-skytrack; NS=skytrack
K=(kubectl --context "$CTX" -n "$NS")

ready_endpoints() {
  "${K[@]}" get endpointslices -l kubernetes.io/service-name=skytrack \
    -o jsonpath='{range .items[*].endpoints[?(@.conditions.ready==true)]}{.addresses[0]} {end}'
}
api_status() { curl -s -o /dev/null -w '%{http_code}' localhost:8080/airports/disruptions || true; }

echo "1. API answers through the port-forward"
[[ "$(api_status)" == 200 ]] || { echo "FAIL: expected 200, got $(api_status)"; exit 1; }

old=$("${K[@]}" get pod -l app=skytrack -o jsonpath='{.items[0].metadata.name}')
echo "2. deleting pod $old"
"${K[@]}" delete pod -l app=skytrack --wait=false >/dev/null

echo "3. readiness gates traffic while the replacement starts"
saw_unready=false
for _ in $(seq 1 60); do
  new=$("${K[@]}" get pod -l app=skytrack -o jsonpath='{.items[*].metadata.name}' | tr ' ' '\n' | grep -v "^$old\$" | head -1 || true)
  eps=$(ready_endpoints)
  if [[ -n "$new" && "$new" != "$old" && -z "${eps// /}" ]]; then saw_unready=true; break; fi
  sleep 1
done
$saw_unready || { echo "FAIL: never observed a replacement pod with zero ready endpoints"; exit 1; }
echo "   replacement pod: $new, ready endpoints: (none)"

echo "4. replacement becomes Ready and the API answers again"
"${K[@]}" rollout status deploy/skytrack --timeout=300s >/dev/null
eps=$(ready_endpoints)
[[ -n "${eps// /}" ]] || { echo "FAIL: rollout done but no ready endpoint"; exit 1; }
for _ in $(seq 1 30); do   # the port-forward loop needs a second or two to reattach
  [[ "$(api_status)" == 200 ]] && break
  sleep 1
done
[[ "$(api_status)" == 200 ]] || { echo "FAIL: API not back after pod replacement"; exit 1; }
echo "   ready endpoint: $eps"
echo "PASS: pod $old was replaced by $new and readiness gated the Service"
