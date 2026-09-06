#!/usr/bin/env bash
# Bring the SkyTrack stack up on a local kind cluster.
#
#   ./scripts/kind-up.sh                                # build image, create cluster, deploy, port-forward
#   SKYTRACK_IMAGE_SOURCE=pull  ./scripts/kind-up.sh    # pull ghcr.io/rpasco787/skytrack:latest instead
#   SKYTRACK_IMAGE_SOURCE=local ./scripts/kind-up.sh    # tag already in the local Docker daemon
#
# Idempotent: re-running reuses the cluster, reloads the image, re-applies the manifests
# and restarts the Deployments so ConfigMap / image changes take effect.
# Tear down with scripts/kind-down.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER=skytrack
CTX="kind-${CLUSTER}"
NS=skytrack
IMAGE=ghcr.io/rpasco787/skytrack:latest
IMAGE_SOURCE="${SKYTRACK_IMAGE_SOURCE:-build}"
STATE_DIR="${TMPDIR:-/tmp}/skytrack-kind"
K=(kubectl --context "$CTX" -n "$NS")

for tool in kind kubectl docker; do
  command -v "$tool" >/dev/null || { echo "kind-up: '$tool' is not installed" >&2; exit 1; }
done
mkdir -p "$STATE_DIR"

# --- 1. cluster -----------------------------------------------------------------------
# data/recorded-weather is whitelisted in .gitignore but git cannot track an empty dir,
# so a clean checkout lacks it and kind would refuse to mount a missing hostPath.
mkdir -p "$REPO_ROOT/data/recorded-weather"

cluster_existed=true
if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  cluster_existed=false
  sed "s#\${REPO_ROOT}#${REPO_ROOT}#g" "$REPO_ROOT/deploy/kind/cluster.yaml" > "$STATE_DIR/cluster.yaml"
  kind create cluster --config "$STATE_DIR/cluster.yaml"
fi
kubectl --context "$CTX" cluster-info >/dev/null
echo "kind-up: cluster '$CLUSTER' ready (context $CTX)"

# --- 2. image -------------------------------------------------------------------------
# The GHCR package is private until A3 Task 9, and the Deployment uses IfNotPresent, so the
# image is always side-loaded into the node rather than pulled by the kubelet.
case "$IMAGE_SOURCE" in
  build) docker build -t "$IMAGE" "$REPO_ROOT/skytrack" ;;
  pull)  docker pull "$IMAGE" ;;
  local) docker image inspect "$IMAGE" >/dev/null || { echo "kind-up: $IMAGE not in local daemon" >&2; exit 1; } ;;
  *)     echo "kind-up: SKYTRACK_IMAGE_SOURCE must be build|pull|local" >&2; exit 1 ;;
esac
kind load docker-image "$IMAGE" --name "$CLUSTER"

# --- 3. manifests ---------------------------------------------------------------------
"$REPO_ROOT/scripts/k8s-gen-configmaps.sh" >/dev/null
kubectl --context "$CTX" apply -k "$REPO_ROOT/deploy/k8s"

# A re-run may have changed the image (same tag) or a ConfigMap (same name): neither triggers
# a rollout by itself, so bounce everything. Fresh cluster: skip, the first rollout is underway.
if $cluster_existed; then
  for d in localstack wiremock prometheus grafana skytrack; do
    "${K[@]}" rollout restart "deploy/$d"
  done
fi

# --- 4. wait --------------------------------------------------------------------------
for d in localstack wiremock prometheus grafana skytrack; do
  "${K[@]}" rollout status "deploy/$d" --timeout=300s
done

# --- 5. port-forward ------------------------------------------------------------------
for spec in "skytrack 8080:8080" "grafana 3000:3000"; do
  set -- $spec
  pidfile="$STATE_DIR/pf-$1.pid"
  if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    continue   # forwarder from a previous run is still alive; it reconnects on its own
  fi
  nohup "$REPO_ROOT/scripts/kind-port-forward.sh" "$1" "$2" >"$STATE_DIR/pf-$1.log" 2>&1 &
  echo $! > "$pidfile"
done

for _ in $(seq 1 30); do
  curl -sf localhost:8080/actuator/health/readiness >/dev/null && break
  sleep 1
done
curl -sf localhost:8080/actuator/health/readiness >/dev/null || { echo "kind-up: localhost:8080 never answered" >&2; exit 1; }

cat <<EOF2

SkyTrack is up on kind cluster '$CLUSTER' (kubectl context: $CTX, namespace: $NS)
  API      http://localhost:8080/airports/disruptions
  Health   http://localhost:8080/actuator/health
  Grafana  http://localhost:3000
  Pods     kubectl --context $CTX -n $NS get pods
  Logs     kubectl --context $CTX -n $NS logs -f deploy/skytrack
  Down     ./scripts/kind-down.sh
EOF2
