# SkyTrack Pipeline — Live End-to-End Smoke Test Results

**Date:** 2026-06-06
**Build:** chunks 1–7 complete, `main` branch
**Profile:** `local` (replay OpenSky + WireMock AeroAPI + LocalStack SQS/DynamoDB/S3)
**Goal:** Prove the whole pipeline runs end-to-end on recorded data before starting chunk 8 (deployment). Requested at "15× speed."

---

> **Update 2026-06-20:** The UNKNOWN-delay gap identified here has been resolved via anchored WireMock schedule stubs. See [2026-06-16-synthetic-schedule-demo-results.md](2026-06-16-synthetic-schedule-demo-results.md) for verified endpoint output: 208/383 flights resolved via AEROAPI, ORD disruption score 81.7, cascades firing across multiple airports.

## Verdict

**The pipeline works end-to-end.** Recorded OpenSky data flowed through every stage — replay → SQS → state machine → DynamoDB → schedule resolution → delay processing → disruption scoring → S3 Parquet → REST/analytics read-back — with **zero runtime errors** after two environment fixes. The write→S3→Parquet→read round-trip closes (the `/analytics/delays` endpoint returned a record decoded from a Parquet object), and live weather correlation works.

**One semantic gap (data, not code):** delay events are classified `UNKNOWN` with `null` delay because there is **no schedule ground-truth** in this environment — so disruption scores are 0 and `/airports/disruptions` is empty. This is expected given the inputs and is the main thing to fix before a deploy is "interesting." See [Findings](#findings).

---

## What "15× speed" actually meant here

The requested "15× replay speed" turned out to be a non-lever:

1. **`opensky.replay-speed-multiplier` is dead config** — [`ReplayOpenSkyClient`](../skytrack/src/main/java/skytrack/demo/client/ReplayOpenSkyClient.java) never reads it; it advances exactly one file per poll.
2. **The real bottleneck is the consumer, not replay.** Each recorded file holds **~7,434 flight positions** (after US-origin filtering), and the producer emits **one SQS message per position**. The stock consumer ([`SqsConsumerService`](../skytrack/src/main/java/skytrack/demo/service/SqsConsumerService.java)) drains ≤10 messages/sec — so producing *faster* would only flood the queue, not move data through faster.

So "15×" was reinterpreted as **"maximize end-to-end throughput"**, which meant boosting the consumer. The measured drain rate after the boost was **~290 positions/sec** against LocalStack (13 files ≈ 96k positions processed in ~5.5 min). True 15× end-to-end is not achievable with a single-threaded consumer against LocalStack; that's a genuine architectural finding for scale-out later.

---

## Instrumentation applied (and reverted)

Two **temporary, reverted** changes let the pipeline keep up with replay so landings would appear in minutes instead of hours:

| File | Change | Why |
|---|---|---|
| [`SqsConsumerService.java`](../skytrack/src/main/java/skytrack/demo/service/SqsConsumerService.java) | `@Scheduled(fixedDelay=1000)` + single `poll()` → `fixedDelay=100` + 40 polls/tick | Raise drain rate from ~10/s to ~290/s |
| [`SqsPositionConsumer.java`](../skytrack/src/main/java/skytrack/demo/sqs/SqsPositionConsumer.java) | `waitTimeSeconds(20)` → `waitTimeSeconds(1)` | Avoid long-poll stalls when caught up |

Both were reverted after the run (`git checkout`), so the working tree is unchanged. They are **not** a recommendation by themselves — see [Recommendations](#recommendations-before-chunk-8).

Also created two **reversible symlinks** (removed after) — see Finding 1.

---

## Two blockers found and fixed mid-run

### Finding 1 — Replay and reference data live in different roots
The app boots from `skytrack/` (Maven `spring-boot:run` working dir), where it finds `skytrack/data/recorded-opensky/` (525 MB, 370 files). But [`AirportLookupService`](../skytrack/src/main/java/skytrack/demo/service/AirportLookupService.java#L35) loads `data/airports/airports.csv`, which lives at the **repo root** (`data/airports/`), not under `skytrack/`. First boot crashed with `NoSuchFileException: data/airports/airports.csv`. (Tests don't hit this because surefire sets `workingDirectory=${project.basedir}/..` = repo root — but the repo-root `data/recorded-opensky/` is empty, so tests and the live app disagree on where data is.)

**Fix used:** reversible symlinks so `skytrack/data/` exposes all three datasets:
```
skytrack/data/airports         -> ../../data/airports
skytrack/data/recorded-weather -> ../../data/recorded-weather
```
**For chunk 8:** the Dockerfile/`prod` setup must put `airports.csv` (and any reference data) on a path the running app actually resolves, or load it from the classpath/S3. This split will bite a containerized deploy.

### Finding 2 — Spring DevTools restart classloader breaks the DynamoDB mapper
With DevTools active, every position failed with:
```
ClassCastException: AircraftTrack cannot be cast to AircraftTrack
(one in RestartClassLoader, one in 'app' loader)
```
The DynamoDB Enhanced `TableSchema.fromBean(AircraftTrack.class)` is built under one classloader while runtime instances come from another → 0 landings, 0 delay events. Setting the **system property** `-Dspring.devtools.restart.enabled=false` (the env var `SPRING_DEVTOOLS_RESTART_ENABLED` does **not** work — DevTools reads this before environment processing) fixed it immediately: thread `restartedMain` → `main`, cast errors → 0.

**For chunk 8:** the production jar excludes DevTools, so prod is unaffected — but local dev runs should disable the restart loader, or DynamoDB persistence silently fails. Worth a note in the README / run instructions.

---

## End-to-end results

Run window ≈ 5.5 min after the fixes. Replay reached **file 13/370**.

| Stage | Component | Evidence | Status |
|---|---|---|---|
| Replay ingest | `ReplayOpenSkyClient` | "Replayed file 13/370 … 7386 positions" | ✅ |
| Publish | `SqsPositionProducer` → SQS | "Published 7434 positions to SQS" | ✅ |
| Consume | `SqsConsumerService` → LocalStack SQS | drained ~290 pos/sec | ✅ |
| State machine | `AircraftStateMachine` | **72 landings detected** | ✅ |
| State persistence | DynamoDB `skytrack-aircraft` | **7,682 items** scanned | ✅ |
| Schedule resolution | `ScheduleResolver` (+WireMock AeroAPI) | 64 arrivals resolved (all `UNRESOLVED`) | ⚠️ data |
| Delay processing | `DelayEventProcessor` | **72 delay events** processed | ✅ |
| Weather correlation | `WeatherCache` | JFK=IFR, ORD=VFR attached | ✅ |
| Historical write | `HistoricalDelayWriter` → S3 | **5 Parquet flushes** | ✅ |
| Analytics read-back | `AnalyticsService` reads S3 Parquet | endpoint returned a decoded row | ✅ |
| Disruption scoring | `DisruptionScoreService` | scores computed (all 0 — see below) | ⚠️ data |
| Cascade detection | `CascadeDetector` | 0 alerts (no qualifying delays) | ⚠️ data |

### Endpoint responses (live)

```
GET /actuator/health
{"groups":["liveness","readiness"],"status":"UP"}

GET /schedule/coverage
{"total":64,"verified":0,"estimated":0,"unresolved":64,"verifiedRate":0.0}

GET /airports/disruptions?limit=10
[]

GET /airports/JFK/status
{"score":{"airportIata":"JFK","score":0.0,"activeDelayCount":0,"totalFlightsInWindow":2,
  "averageDelayMinutes":0.0,...},
 "weather":{"airportIcao":"KJFK","flightCategory":"IFR","visibilityStatuteMiles":2.0,
  "ceilingFeet":800,"rawMetar":"METAR KJFK 051430Z 18012G18KT 2SM RA OVC008"},
 "cascades":[]}

GET /analytics/delays?airport=JFK&date=2026-06-07
[{"icao24":"a9b62c","callsign":"AAL103","arrivalAirportIcao":"KJFK","arrivalAirportIata":"JFK",
  "actualArrivalTime":1773078820,"scheduledArrivalTime":null,"delaySeconds":null,
  "classification":"UNKNOWN","resolutionMethod":"UNRESOLVED","flightCategory":"IFR",
  "visibilityStatuteMiles":2.0,"ceilingFeet":800,"windSpeedKnots":12}]
```

### S3 Parquet objects
```
delays/year=2026/month=06/day=07/hour=02/delays-1780798980319.parquet   3678 B
delays/year=2026/month=06/day=07/hour=02/delays-1780799039407.parquet   3842 B
delays/year=2026/month=06/day=07/hour=02/delays-1780799099721.parquet   3972 B
delays/year=2026/month=06/day=07/hour=02/delays-1780799160310.parquet   4277 B
delays/year=2026/month=06/day=07/hour=02/delays-1780799218928.parquet   4126 B
```
Partitioning (`year/month/day/hour`) works; objects grow as the buffer flushes every ~60s.

---

## Findings

**The plumbing is proven; the *semantics* are starved of reference data.** Every stage executes and data physically lands in S3 and reads back. But because there is **no schedule ground-truth**:

- `data/bts/` (BTS On-Time Performance) is **absent** in this environment, so the primary schedule source is empty.
- WireMock AeroAPI only stubs a handful of specific callsigns (`DAL567`, `UAL1234`, `AAL100`, …); the real replay aircraft don't match.

…so `ScheduleResolver` returns `UNRESOLVED` for all 64 arrivals → `DelayComputer` can't compute a delay → `classification=UNKNOWN`, `delaySeconds=null` → `DisruptionScoreService` records nothing scoreable → `/airports/disruptions` is `[]` and all scores are 0. **Weather is the one enrichment that works**, because it doesn't depend on schedules.

This is exactly the kind of thing the smoke test exists to surface: the system is wired correctly, but a live deploy would show empty disruption data until schedule ground-truth is supplied.

---

## Recommendations before chunk 8

1. **Supply schedule ground-truth.** Add `data/bts/T_ONTIME_REPORTING.csv` (the roadmap's BTS source) and/or broaden the AeroAPI stubs so real callsigns resolve. Without this, a deployed demo's headline feature (disruption scoring) shows nothing. **Highest priority.**
2. **Fix the data-path split (Finding 1)** in the container image — bake `airports.csv` onto the path the app resolves, or load reference data from the classpath/S3.
3. **Document the DevTools gotcha (Finding 2)** for local runs (`-Dspring.devtools.restart.enabled=false`), or exclude DevTools from the dev run.
4. **Note the consumer throughput ceiling** (~290 pos/sec here). Fine for a single-host demo; if real-time fidelity matters, scale consumers or batch positions per message. Not a blocker for chunk 8.
5. The instrumentation tweaks in this run were reverted — if you want a faster *local* dev loop, consider making the consumer drain-rate configurable rather than hard-coded.

---

## Reproduce

```bash
# 1. Stack
docker compose up -d                      # wiremock + localstack (auto-creates queues/table/bucket)

# 2. Bridge the data roots (until Finding 1 is fixed properly)
ln -sfn ../../data/airports         skytrack/data/airports
ln -sfn ../../data/recorded-weather skytrack/data/recorded-weather

# 3. Run (devtools restart MUST be off, or DynamoDB mapping throws ClassCastException)
cd skytrack && SPRING_PROFILES_ACTIVE=local \
  ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.devtools.restart.enabled=false"

# 4. After a few minutes, query (UTC date for the partition!)
curl -s localhost:8080/schedule/coverage
curl -s localhost:8080/airports/JFK/status
curl -s "localhost:8080/analytics/delays?airport=JFK&date=$(date -u +%Y-%m-%d)"
aws --endpoint-url=http://localhost:4566 s3 ls s3://skytrack-history/delays/ --recursive
```

*Note: to see non-`UNKNOWN` delays, the consumer drain-rate tweak (reverted) or a long run is needed, plus schedule ground-truth (Recommendation 1).*
