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
