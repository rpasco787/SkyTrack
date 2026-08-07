#!/usr/bin/env bash
#
# soak.sh — long-run health harness for the local pipeline.
#
# Answers one question: can SkyTrack run unattended for hours without the queue
# growing without bound, the heap creeping, or the pipeline silently stalling?
#
# Usage:  scripts/soak.sh [HOURS] [POLL_RATE_MS] [SAMPLE_SECONDS]
# Default: scripts/soak.sh 4 6500 60
#
# Why 6500ms: measured 2026-08-07. The producer and consumer contend for a single
# LocalStack container, so the local producer rate has to be tuned to consumer
# capacity or the queue grows unboundedly regardless of correctness.
#   poll-rate 2000 -> produced ~3,643/sec, queue +2,390/sec  (unsustainable)
#   poll-rate 5000 -> produced ~1,556/sec, queue +209/sec    (still growing)
#   poll-rate 6500 -> produced ~1,175/sec, queue flat 2k-7k  (steady state)
# This is a LocalStack artifact. Real production cadence is 30s (~248/sec), which
# is ~5x below even this throttled rate. See docs/throughput-measurements-2026-08-03.md.
#
# Writes a CSV of samples plus a summary verdict. Everything lands in OUT_DIR.

set -uo pipefail

HOURS=${1:-4}
POLL_RATE_MS=${2:-6500}
INTERVAL=${3:-60}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${SOAK_OUT_DIR:-$REPO_ROOT/soak-results}"
STAMP="$(date +%Y%m%dT%H%M%S)"
CSV="$OUT_DIR/soak-$STAMP.csv"
APP_LOG="$OUT_DIR/soak-$STAMP-app.log"
SUMMARY="$OUT_DIR/soak-$STAMP-summary.md"

ENDPOINT=http://localhost:4566
QUEUE=http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/skytrack-positions.fifo
DLQ=http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/skytrack-dlq.fifo

export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1

mkdir -p "$OUT_DIR"

log() { echo "[soak $(date +%H:%M:%S)] $*"; }

cleanup() {
  log "stopping app (pid ${APP_PID:-none})"
  [[ -n "${APP_PID:-}" ]] && kill "$APP_PID" 2>/dev/null
  pkill -f "spring-boot:run" 2>/dev/null
  pkill -f "skytrack.demo.MyApplication" 2>/dev/null
  sleep 2
}
trap cleanup EXIT INT TERM

qattr() {  # $1 = queue url, $2 = attribute
  aws --endpoint-url="$ENDPOINT" sqs get-queue-attributes --queue-url "$1" \
      --attribute-names "$2" --query "Attributes.$2" --output text 2>/dev/null || echo "ERR"
}

# ---------------------------------------------------------------- preflight
# Battery: a multi-hour soak on battery dies partway through and the partial result looks like a
# completed run. Learned the hard way — a 4h run was started at 18% charge.
if pmset -g batt 2>/dev/null | grep -q "discharging"; then
  REMAINING=$(pmset -g batt 2>/dev/null | grep -oE "[0-9]+:[0-9]+ remaining" | head -1)
  if [[ "${SOAK_ALLOW_BATTERY:-0}" != "1" ]]; then
    echo "FATAL: on battery power (${REMAINING:-unknown remaining}); a ${HOURS}h soak needs AC." >&2
    echo "       Plug in, or re-run with SOAK_ALLOW_BATTERY=1 to override." >&2
    exit 1
  fi
  log "WARNING: running on battery (${REMAINING:-unknown remaining}) — override in effect"
fi

# Keep the machine awake for the duration. System sleep does not merely pause a soak, it
# invalidates it: the JVM and LocalStack freeze while elapsed time is still measured from the wall
# clock, so throughput and growth-per-second are silently deflated toward zero and the run looks
# healthier than it was. -w ties the assertion to this script, so it releases on exit.
# NOTE: this does not survive a laptop lid close. Leave the lid open.
if command -v caffeinate >/dev/null 2>&1; then
  caffeinate -dimsu -w $$ &
  log "caffeinate armed (pid $!) — machine held awake until this script exits"
fi

log "checking LocalStack"
if ! aws --endpoint-url="$ENDPOINT" sqs get-queue-attributes --queue-url "$QUEUE" \
        --attribute-names QueueArn >/dev/null 2>&1; then
  echo "FATAL: positions queue unreachable at $ENDPOINT. Run: docker compose up -d" >&2
  exit 1
fi

log "purging positions queue for a clean t=0"
aws --endpoint-url="$ENDPOINT" sqs purge-queue --queue-url "$QUEUE" >/dev/null 2>&1
sleep 15

# ---------------------------------------------------------------- start app
log "starting app: poll-rate-ms=$POLL_RATE_MS, duration=${HOURS}h, sample=${INTERVAL}s"
cd "$REPO_ROOT/skytrack"
./mvnw spring-boot:run -Dspring-boot.run.profiles=local \
    -Dspring-boot.run.arguments="--opensky.poll-rate-ms=$POLL_RATE_MS" \
    > "$APP_LOG" 2>&1 &
APP_PID=$!

for _ in $(seq 1 60); do
  grep -q "Started MyApplication" "$APP_LOG" 2>/dev/null && break
  sleep 5
done
if ! grep -q "Started MyApplication" "$APP_LOG" 2>/dev/null; then
  echo "FATAL: app did not start within 300s — see $APP_LOG" >&2
  exit 1
fi

JVM_PID="$(jps -l 2>/dev/null | grep -i 'skytrack\|MyApplication' | head -1 | awk '{print $1}')"
log "app up (maven pid $APP_PID, jvm pid ${JVM_PID:-unknown})"

# ---------------------------------------------------------------- sampling
echo "elapsed_s,queue_depth,inflight,dlq_depth,produced_cum,files_replayed,wraps,landings,rss_kb,heap_old_pct,full_gcs,errors,warns" > "$CSV"

START=$(date +%s)
END=$(( START + HOURS * 3600 ))
FIRST_DEPTH=""; LAST_DEPTH=""; FIRST_RSS=""; LAST_RSS=""; PEAK_DEPTH=0

while [[ $(date +%s) -lt $END ]]; do
  sleep "$INTERVAL"
  NOW=$(date +%s); ELAPSED=$(( NOW - START ))

  DEPTH=$(qattr "$QUEUE" ApproximateNumberOfMessages)
  INFLIGHT=$(qattr "$QUEUE" ApproximateNumberOfMessagesNotVisible)
  DLQ_DEPTH=$(qattr "$DLQ" ApproximateNumberOfMessages)

  PRODUCED=$(grep -oE "Published [0-9]+ positions to SQS" "$APP_LOG" | grep -oE "[0-9]+" | awk '{s+=$1} END {print s+0}')
  FILES=$(grep -cE "Replayed file [0-9]+/" "$APP_LOG")
  WRAPS=$(grep -c "Replay wrapped to the start" "$APP_LOG")
  LANDINGS=$(grep -oE "([0-9]+) landings detected" "$APP_LOG" | grep -oE "^[0-9]+" | awk '{s+=$1} END {print s+0}')
  ERRORS=$(grep -c " ERROR " "$APP_LOG")
  WARNS=$(grep -c " WARN " "$APP_LOG")

  RSS=""; OLD_PCT=""; FGC=""
  if [[ -n "${JVM_PID:-}" ]] && kill -0 "$JVM_PID" 2>/dev/null; then
    RSS=$(ps -o rss= -p "$JVM_PID" 2>/dev/null | tr -d ' ')
    # jstat -gcutil columns: S0 S1 E O M CCS YGC YGCT FGC FGCT GCT
    #                        $1 $2 $3 $4 $5 $6  $7  $8   $9  $10  $11
    # $8 is YGCT (young-GC seconds), NOT the full-GC count — an earlier version read $8 here and
    # logged fractional "full GCs". Old-gen % ($4) and FGC ($9) are the leak signals.
    GCUTIL=$(jstat -gcutil "$JVM_PID" 2>/dev/null | tail -1)
    OLD_PCT=$(echo "$GCUTIL" | awk '{print $4}')
    FGC=$(echo "$GCUTIL" | awk '{print $9}')
  fi

  # Detect a clock jump (system sleep, suspend, VM pause). Rates computed across such a gap are
  # meaningless, so record it rather than quietly averaging it in.
  if [[ -n "${PREV_NOW:-}" ]]; then
    GAP=$(( NOW - PREV_NOW ))
    if (( GAP > INTERVAL * 3 )); then
      JUMPS=$(( ${JUMPS:-0} + 1 ))
      log "WARNING: ${GAP}s gap between samples (expected ~${INTERVAL}s) — probable system sleep; rate math for this interval is unreliable"
    fi
  fi
  PREV_NOW=$NOW

  echo "$ELAPSED,$DEPTH,$INFLIGHT,$DLQ_DEPTH,$PRODUCED,$FILES,$WRAPS,$LANDINGS,$RSS,$OLD_PCT,$FGC,$ERRORS,$WARNS" >> "$CSV"
  log "t=${ELAPSED}s depth=$DEPTH dlq=$DLQ_DEPTH produced=$PRODUCED wraps=$WRAPS rss=${RSS}KB old=${OLD_PCT}% fgc=$FGC err=$ERRORS"

  [[ -z "$FIRST_DEPTH" ]] && FIRST_DEPTH=$DEPTH && FIRST_RSS=$RSS
  LAST_DEPTH=$DEPTH; LAST_RSS=$RSS
  [[ "$DEPTH" =~ ^[0-9]+$ ]] && (( DEPTH > PEAK_DEPTH )) && PEAK_DEPTH=$DEPTH

  # Abort early on a hard failure rather than burning hours.
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    log "FATAL: app process died at t=${ELAPSED}s"
    break
  fi
done

# ---------------------------------------------------------------- summary
ELAPSED=$(( $(date +%s) - START ))
DEPTH_DELTA=$(( ${LAST_DEPTH:-0} - ${FIRST_DEPTH:-0} ))
RSS_DELTA=$(( ${LAST_RSS:-0} - ${FIRST_RSS:-0} ))
GROWTH_PER_SEC=$(awk -v d="$DEPTH_DELTA" -v t="$ELAPSED" 'BEGIN{ if(t>0) printf "%.1f", d/t; else print "n/a" }')

{
  echo "# Soak result — $STAMP"
  echo
  echo "Ran ${ELAPSED}s (~$(( ELAPSED / 60 ))min) at \`poll-rate-ms=$POLL_RATE_MS\`, sampled every ${INTERVAL}s."
  echo
  echo "| metric | first | last | delta |"
  echo "|---|---|---|---|"
  echo "| queue depth | ${FIRST_DEPTH:-?} | ${LAST_DEPTH:-?} | $DEPTH_DELTA (${GROWTH_PER_SEC}/sec) |"
  echo "| RSS (KB) | ${FIRST_RSS:-?} | ${LAST_RSS:-?} | $RSS_DELTA |"
  echo
  echo "- peak queue depth: $PEAK_DEPTH"
  echo "- DLQ depth at end: $(qattr "$DLQ" ApproximateNumberOfMessages)"
  echo "- replay wraps: $(grep -c 'Replay wrapped to the start' "$APP_LOG")"
  echo "- ERROR lines: $(grep -c ' ERROR ' "$APP_LOG")"
  echo "- WARN lines: $(grep -c ' WARN ' "$APP_LOG")"
  echo "- ingest-buffer drops: $(grep -c 'Ingest buffer full' "$APP_LOG")"
  echo "- S3 flushes: $(grep -c 'Flushed .* to s3://' "$APP_LOG")"
  if (( ${JUMPS:-0} > 0 )); then
    echo
    echo "> **${JUMPS} clock jump(s) detected — treat every rate in this run as unreliable.** The"
    echo "> machine most likely slept. Elapsed time is wall-clock while the JVM and LocalStack were"
    echo "> frozen, so throughput and growth/sec are deflated. Re-run on AC with the lid open."
  fi
  echo
  echo "Samples: \`$(basename "$CSV")\`  ·  app log: \`$(basename "$APP_LOG")\`"
  echo
  echo "## Distinct ERRORs"
  echo '```'
  grep ' ERROR ' "$APP_LOG" | sed -E 's/^[0-9T:.+-]+ //' | cut -c1-140 | sort | uniq -c | sort -rn | head -15
  echo '```'
} > "$SUMMARY"

log "done — summary at $SUMMARY"
cat "$SUMMARY"
