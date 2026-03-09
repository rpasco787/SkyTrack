# SkyTrack Phase 1 Roadmap — Local Development ($0 Cost)

**Scope:** Weeks 1–4 of the implementation plan — everything runs locally against LocalStack and recorded data.

**API Strategy:** BTS inference as primary schedule source + aviationstack free tier (100 req/month) as Tier 0, with AeroAPI stubbed out for future drop-in upgrade.

**End State:** `docker compose up` → hit `localhost:8080/airports/ORD/status` → get a meaningful response built from recorded data flowing through the full pipeline.

---

## Chunk 0: Prerequisites (~3–4 hours)

These are blockers for everything else. Do them first.

- [X] **0.1** Register for an OpenSky Network account at opensky-network.org. Free tier gives 400 API calls/day. You only need ~240 calls for one recording session.
- [X] **0.2** Download the BTS On-Time Performance CSV from transtats.bts.gov. Grab the most recent month. Select fields: `FL_DATE`, `OP_CARRIER`, `OP_CARRIER_FL_NUM`, `ORIGIN`, `DEST`, `CRS_DEP_TIME`, `CRS_ARR_TIME`, `DEP_TIME`, `ARR_TIME`, `ARR_DELAY`, `DISTANCE`, `DAY_OF_WEEK`. File is ~500 MB. Place in `data/bts/`.
- [X] **0.3** Register for aviationstack at aviationstack.com. Free tier, instant approval, 100 requests/month. Save your API key securely.
- [X] **0.4** Install Docker + Docker Compose. Verify: `docker compose version`.
- [X] **0.5** Pull LocalStack: `docker pull localstack/localstack`. Verify: `docker run --rm localstack/localstack`.
- [X] **0.6** Install AWS CLI v2. Configure for local dev: `aws --endpoint-url=http://localhost:4566 sqs list-queues`.
- [X] **0.7** Initialize GitHub repo with project board. Create 4 milestones (one per week).
- [X] **0.8** Download the OurAirports dataset from ourairports.com/data. Place in `data/airports/`.

---

## Chunk 1: Data Recording & OpenSky Clients (~5–6 hours)

Foundation layer. Everything downstream consumes `FlightPosition` objects produced here.

**Depends on:** Chunk 0

- [ ] **1.1 — Define core data model.** Create a `FlightPosition` record: `icao24`, `callsign` (trimmed), `latitude`, `longitude`, `baroAltitude`, `velocity`, `heading`, `onGround`, `lastContact`, `timePosition`, `parsedAt`. Use Jackson annotations for deserialization. This record flows through your entire pipeline.

- [ ] **1.2 — Define the `FlightDataSource` interface.** Single method returning a collection of `FlightPosition` objects. Both the real client and replay client implement this. The rest of the pipeline only touches this interface.

- [ ] **1.3 — Build the `LiveOpenSkyClient`.** Implements `FlightDataSource`. Makes HTTP calls to `/states/all`, deserializes JSON state vectors into `FlightPosition` records, filters to US-relevant flights. Handle rate limiting (5-second interval for authenticated users).

- [ ] **1.4 — Run the recording session.** Pick a weekday afternoon, 2–6 PM Eastern (heavy US domestic traffic). Poll `/states/all` every 30 seconds for 2 hours. Write each raw JSON response to `data/recorded-opensky/<timestamp>.json`. Target: ~240 files, 200–400 MB total. **This is the only time you hit the real OpenSky API during Phase 1.**

- [ ] **1.5 — Build the `ReplayOpenSkyClient`.** Implements `FlightDataSource`. Reads from recorded JSON files in timestamp order. Supports configurable replay speed (1x real-time, 10x fast-forward for quick iteration).

- [ ] **1.6 — Wire up Spring profiles for data source switching.**
  - `application-local.yml`: `opensky.mode: replay`, `opensky.replay-dir: ./data/recorded-opensky/`
  - `application-prod.yml`: `opensky.mode: live`, `opensky.api-url: https://opensky-network.org/api`
  - Spring injects the correct `FlightDataSource` based on active profile.

- [ ] **1.7 — Add scheduled polling.** Use `@Scheduled` to call `FlightDataSource` every 30 seconds. Log aircraft count, sample positions, and parsing errors. Verify: run the app locally and see recorded data being polled and logged.

---

## Chunk 2: LocalStack + SQS Pipeline (~4–5 hours)

Give the data somewhere to flow.

**Depends on:** Chunk 1

- [ ] **2.1 — Create `docker-compose.yml`.** LocalStack container with SQS and DynamoDB services, port 4566. Add init script that creates both SQS FIFO queues on startup: `skytrack-positions.fifo` and `skytrack-airport-events.fifo`. Spring Boot app container depends on LocalStack, starts with `SPRING_PROFILES_ACTIVE=local`.

- [ ] **2.2 — Add AWS SDK v2 dependencies for SQS.** Configure SDK to use LocalStack endpoint (`http://localhost:4566`) in the local profile.

- [ ] **2.3 — Build the SQS producer.** Serialize `FlightPosition` records as JSON, publish to `skytrack-positions.fifo`. Set `MessageGroupId = icao24` (per-aircraft ordering). Set `MessageDeduplicationId = icao24 + timestamp`. Use `SendMessageBatch` (up to 10 per call).

- [ ] **2.4 — Build the SQS consumer.** Poll `skytrack-positions.fifo` with long polling (20 seconds). Deserialize `FlightPosition` events, log them, delete messages after successful processing.

- [ ] **2.5 — Test the round trip.** Run `docker compose up`. Verify: recorded data → scheduled poll → SQS producer → SQS consumer logging positions. Everything runs on your laptop.

### ✅ Checkpoint 1
Recorded OpenSky data flows through Spring Boot → SQS FIFO (LocalStack) → consumer logging positions. BTS CSV downloaded and readable.

---

## Chunk 3: DynamoDB & Aircraft State Machine (~8–10 hours)

The stateful core of the system. Take your time here.

**Depends on:** Chunk 2, OurAirports data (Chunk 0)

- [ ] **3.1 — Design single-table DynamoDB schema.** One table (`skytrack`) with generic string PK and SK. Key patterns:
  - `AIRCRAFT#<icao24>` / `CURRENT`
  - `AIRCRAFT#<icao24>` / `POSITION#<ts>`
  - `AIRPORT#<code>` / `DELAY#<ts>`
  - `AIRPORT#<code>` / `SCORE`
  - `SCHEDULE#<carrier>#<flightNum>#<dow>`
  - `SCHEDULE_API#<callsign>#<date>`
  - `WEATHER#<airport>`
  - Create GSI (GSI1) for airport-score queries. Set TTL on position records (24 hours). Add table creation to docker-compose init script. Use on-demand capacity mode.

- [ ] **3.2 — Build the `DynamoDbRepository` layer.** Methods: `putAircraftState()`, `getAircraftState()`, `putAirportScore()`, `getAirportScore()`, `queryAircraftRoute()`, `putScheduleEntry()`, `getScheduleEntry()`. Use AWS SDK v2 `DynamoDbEnhancedClient`. Test all methods against LocalStack.

- [ ] **3.3 — Define aircraft state enum and `AircraftState` model.** States: `UNKNOWN → EN_ROUTE → APPROACHING → ON_GROUND → DEPARTED`. `AircraftState` holds: current position, phase, estimated origin/destination, route history (legs flown today), last update timestamp, callsign at each transition.

- [ ] **3.4 — Build the in-memory state store.** `ConcurrentHashMap<String, AircraftState>` keyed by icao24. Live state for every tracked aircraft.

- [ ] **3.5 — Implement state machine transitions.** Transitions based on altitude changes, ground speed, and `onGround` flag. On transition to `ON_GROUND`: detect arrival airport (nearest airport by Haversine distance using OurAirports data), record a completed leg, emit a `LandingEvent` (icao24, callsign, arrivalAirport, actualArrivalTime).

- [ ] **3.6 — Add stale state eviction.** No position report in 5 minutes → mark `STALE`. After 15 minutes → evict from memory, write final state to DynamoDB.

- [ ] **3.7 — Add periodic flush.** Every 60 seconds, batch-write all active aircraft state to DynamoDB (`BatchWriteItem`, up to 25 per call).

- [ ] **3.8 — Build the `CallsignParser`.** Extract airline ICAO code and flight number from OpenSky callsigns (e.g., `UAL1234` → carrier=UA, flight=1234). Maintain ICAO→IATA mapping table (UAL=UA, DAL=DL, AAL=AA, etc.). Mark cargo (FDX, UPS), charter, and military callsigns as non-commercial — skip schedule resolution for these.

- [ ] **3.9 — Load airport reference data.** Parse OurAirports CSV into `HashMap<String, Airport>` at startup for nearest-airport lookups during landing detection.

---

## Chunk 4: Schedule Inference Engine (~6–8 hours)

The most intellectually interesting piece. Delay detection depends entirely on this.

**Depends on:** Chunk 3, BTS CSV (Chunk 0), aviationstack registration (Chunk 0)

- [ ] **4.1 — Parse the BTS CSV.** Read the On-Time Performance CSV into memory. Fields needed: `OP_CARRIER`, `OP_CARRIER_FL_NUM`, `FL_DATE`, `ORIGIN`, `DEST`, `CRS_DEP_TIME`, `CRS_ARR_TIME`, `DAY_OF_WEEK`.

- [ ] **4.2 — Build the BTS schedule index.** Group records by `(carrier, flightNumber, dayOfWeek, origin, destination)`. For each group compute: median scheduled departure, median scheduled arrival, standard deviation of arrival times, sample count, average block time. Store as `HashMap<ScheduleKey, ScheduleEntry>`. Typical size: ~100K–200K entries, fits in memory. Load at startup.

- [ ] **4.3 — Build the `FlightScheduleApiClient` interface.** Single method: `fetchSchedule(callsign, date) → Optional<ApiScheduleRecord>`. Both aviationstack and AeroAPI implement this.

- [ ] **4.4 — Implement `AviationstackClient`.** Calls `GET http://api.aviationstack.com/v1/flights?access_key=KEY&flight_date=...`. Parses response into `ApiScheduleRecord`. Include rate-limit guard — `AtomicInteger` tracking calls, warn when approaching 100/month limit, fall through to BTS tiers when exhausted.

- [ ] **4.5 — Stub out `AeroApiClient`.** Implements `FlightScheduleApiClient`, returns `Optional.empty()` or throws unimplemented. This is your future drop-in point — fill in the implementation and change a profile flag when ready.

- [ ] **4.6 — Build the daily prefetch job.** Scheduled task (simulating the Lambda at 00:00 UTC). Calls the API to fetch scheduled US domestic flights for the current date. Writes each record to DynamoDB: `PK=SCHEDULE_API#<callsign>#<date>`, `SK=<arrivalAirport>`. TTL = 24 hours. For aviationstack free tier, this is one bulk call per day.

- [ ] **4.7 — Build the `ScheduleResolver`.** Interface: `resolve(callsign, dayOfWeek, arrivalAirport) → ScheduleResolution`. Tiers:
  - **Tier 0 — VERIFIED:** Query DynamoDB for `SCHEDULE_API#<callsign>#<date>`. Only if `schedule.api.enabled=true`.
  - **Tier 1 — HIGH:** BTS index, 4+ samples, stdDev < 15 minutes.
  - **Tier 2 — MEDIUM:** BTS index, 1–3 samples or stdDev ≥ 15 minutes.
  - **Tier 3 — LOW:** No flight match. Estimate using average block time for origin-destination pair.
  - **UNMATCHED:** No data at any level. Log for investigation, don't emit a delay event.

- [ ] **4.8 — Wire up Spring profile config for API tier:**
  ```yaml
  # application-prod.yml (BTS-only, default)
  schedule.api.enabled: false

  # application-prod-with-api.yml
  schedule.api.enabled: true
  schedule.api.provider: aviationstack  # or aeroapi
  schedule.api.key: ${SCHEDULE_API_KEY}
  schedule.api.daily-prefetch: true
  schedule.api.max-monthly-calls: 95
  ```

- [ ] **4.9 — Test with WireMock.** Mock both aviationstack and AeroAPI responses locally. Verify full resolution chain: API hit → cache in DynamoDB → resolver finds at Tier 0. Disable API → verify fallthrough to BTS tiers. Test with a real callsign from recorded data to confirm BTS returns sensible schedule times.

### ✅ Checkpoint 2
Full local pipeline with recorded data: tracks aircraft, detects landings, resolves schedules via BTS inference (and optionally API cache). Pick a real callsign from recorded data, trace it through the state machine to landing, verify `ScheduleResolver` returns a sensible scheduled arrival with correct confidence.

---

## Chunk 5: Delay Detection & Disruption Scoring (~7–9 hours)

Intelligence layer on top of the state machine and schedule engine.

**Depends on:** Chunks 3 and 4

- [ ] **5.1 — Build the `DelayComputer`.** Receives `LandingEvent`s from the state machine. Calls `ScheduleResolver.resolve()`. If schedule found: `delay = actualArrival − scheduledArrival`. Delayed if > 15 minutes (FAA standard). If `UNMATCHED`, log but don't emit a `DelayEvent`. Track unmatched rate as a metric (if > 30%, callsign parser needs tuning).

- [ ] **5.2 — Define the `DelayEvent` model.** Fields: `flightId`, `airline`, `origin`, `destination`, `scheduledArrival`, `actualArrival`, `delayMinutes`, `scheduleConfidence` (VERIFIED/HIGH/MEDIUM/LOW), `matchType` (API/BTS_EXACT/BTS_ROUTE_AVG/ESTIMATED), `weatherCategory` (added in Chunk 6). Write to DynamoDB, publish to `skytrack-airport-events.fifo`.

- [ ] **5.3 — Build the airport disruption score.** Sliding window: `ConcurrentHashMap<String, TreeMap<Long, BucketMetrics>>` keyed by airport code. Each bucket = 1 minute, keep 60 buckets (1-hour window). Evict expired buckets every minute. Score formula:
  - Delayed flight count: weight 0.3
  - Average delay severity: weight 0.3
  - Trend direction: weight 0.2
  - Percentage of flights delayed: weight 0.2
  - Normalize to 0–100. Only include delays with confidence ≥ MEDIUM. Optionally weight VERIFIED higher than HIGH when API is enabled.

- [ ] **5.4 — Build airport ranking.** Sorted set of airports by score. Expose "top N most disrupted" query. Update every 30 seconds. Write to DynamoDB with GSI for efficient ranking queries.

- [ ] **5.5 — Build cascade detection.** When an aircraft lands late, query BTS index for same carrier's subsequent flights departing from that airport within 90 minutes. Predicted downstream delay = `currentDelay × 0.85` per leg. If predicted delay > 15 minutes, emit a `CascadeAlert`. Known limitation: BTS links by flight number, not tail number — flag as POSSIBLE cascade.

- [ ] **5.6 — Build the `SyntheticFlightGenerator`.** Parameterized generator for edge cases:
  - A flight circling in a holding pattern (state machine edge cases)
  - A 3–4 leg cascade with increasing delays (end-to-end cascade detection)
  - A burst of 20 delayed arrivals at one airport in 30 minutes (disruption scoring extremes)
  - Flights with no BTS match (fallback estimation logic)
  - Out-of-order position updates with duplicate timestamps (idempotency)
  - Example: `generateCascadeScenario(legs=4, initialDelayMinutes=45, decayFactor=0.85)`

### ✅ Checkpoint 3
Run full pipeline locally with recorded data. Delay events generated with confidence tags, disruption scores computed for airports, cascade alerts propagating. Use 10x replay speed to simulate several hours of traffic in minutes — verify scores rise and fall sensibly.

---

## Chunk 6: Weather, Historical Storage & REST API (~8–10 hours)

The final local-dev layer.

**Depends on:** Chunk 5

- [ ] **6.1 — Build the weather poller.** Hit aviationweather.gov (free, no key) every 15 minutes for METAR data. Parse XML into `WeatherObservation`: `airport`, `timestamp`, `visibility`, `ceiling`, `windSpeed`, `windGust`, `windDirection`, `precipitationType`, `temperature`, `flightCategory` (VFR/MVFR/IFR/LIFR). Write to DynamoDB under `WEATHER#<airport>` keys. For local dev, record one set of real METAR data and replay it.

- [ ] **6.2 — Add weather-delay correlation tagging.** Enrich each `DelayEvent` with the concurrent weather observation. Tag with `flightCategory`.

- [ ] **6.3 — Build the S3 Parquet writer.** Every 5 minutes, serialize position updates and delay events to Parquet (Apache Parquet Java libraries). Upload to S3 (LocalStack locally). Partition: `s3://skytrack-data/delays/date=2026-03-15/hour=14/part-0001.parquet`. Verify you can read files back from LocalStack.

- [ ] **6.4 — Build REST API endpoints** (Spring Boot, serve locally):

  | Endpoint | Returns |
  |---|---|
  | `GET /flights/{callsign}` | Position, state, delay status, schedule confidence, route history |
  | `GET /airports/{code}/status` | Disruption score, active delays with confidence breakdown, weather, cascade risks |
  | `GET /airports/disruptions` | Top-N most disrupted. Supports `limit`, `minScore`, `minConfidence` filters |
  | `GET /cascades/{airport}` | Predicted cascade delays for inbound flights |
  | `GET /analytics/delays` | Historical query (mock response locally, Athena in prod) |
  | `GET /schedule/coverage` | Total landings, matched by tier (VERIFIED/HIGH/MEDIUM/LOW), unmatched rate, API vs BTS breakdown |

- [ ] **6.5 — Add error handling and input validation** across all endpoints.

- [ ] **6.6 — Prepare production Spring profile** (`application-prod.yml`). Real AWS endpoints, real OpenSky URL, real SQS ARNs, real DynamoDB table, real S3 bucket. Don't deploy — just have the config ready for Week 5.

- [ ] **6.7 — Build the Dockerfile.** Multi-stage: Maven build stage → Eclipse Temurin JRE 17 slim runtime. Keep under 200 MB. Expose `/actuator/health`.

- [ ] **6.8 — Write `setup.sh`** (or CloudFormation template) documenting every AWS resource needed: 2 SQS FIFO queues, 1 DynamoDB table with GSI, 1 S3 bucket, 1 EC2 t3.micro, Lambda functions (OpenSky poller + optional schedule prefetch), API Gateway.

### ✅ Final Phase 1 Checkpoint
Run `docker compose up` → hit `localhost:8080/airports/ORD/status` → get a meaningful response. Full pipeline working end-to-end: recorded data → SQS → state machine → schedule inference (BTS + aviationstack mock) → delay detection → disruption scoring → weather correlation → REST API. Production profile ready for Week 5 deployment. **AWS cost: $0.**

---

## Dependency Graph

```
Chunk 0 (Prerequisites)
├── Chunk 1 (OpenSky Clients + Recording)
│   └── Chunk 2 (LocalStack + SQS)
│       └── Chunk 3 (DynamoDB + State Machine)
│           ├── Chunk 4 (Schedule Inference)
│           │   └─┐
│           └─────┤
│                 └── Chunk 5 (Delay Detection + Scoring)
│                     └── Chunk 6 (Weather + Storage + API + Deploy Prep)
```

## Estimated Total Effort

| Chunk | Hours |
|---|---|
| 0 — Prerequisites | 3–4 |
| 1 — Data Recording & OpenSky Clients | 5–6 |
| 2 — LocalStack + SQS Pipeline | 4–5 |
| 3 — DynamoDB & State Machine | 8–10 |
| 4 — Schedule Inference Engine | 6–8 |
| 5 — Delay Detection & Scoring | 7–9 |
| 6 — Weather, Storage & REST API | 8–10 |
| **Total** | **~41–52 hours** |
