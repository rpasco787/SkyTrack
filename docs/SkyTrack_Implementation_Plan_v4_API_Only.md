# SKYTRACK

**Real-Time US Flight Delay & Disruption Tracker**

---

**Implementation Plan · 6-Week Build Schedule**
Java · Spring Boot · AWS (SQS, DynamoDB, S3, Lambda, EC2)
FlightAware AeroAPI for real-time schedule data

Ryan · March 2026 · v4.0

---

## Executive Summary

SkyTrack is a real-time flight delay and disruption tracking system that ingests live position data for all US commercial flights, retrieves scheduled arrival times from a real-time flight schedule API (FlightAware AeroAPI), computes delay propagation across airports, and predicts cascading disruptions. The system processes approximately 2 million ADS-B position updates per day through a Java-based streaming pipeline on AWS.

This v4.0 plan makes two key changes over v3.1: (1) the BTS schedule inference engine has been removed entirely in favor of relying exclusively on a real-time flight schedule API for schedule data, and (2) all schedule resolution flows through the API cache, eliminating the multi-tier confidence system in favor of a simpler VERIFIED / ESTIMATED model. The cost-optimized AWS stack and local-first development strategy from v3.1 are preserved.

### Total Estimated Effort

**Timeline:** 6 weeks · ~75 hours total · 12–15 hrs/week
**Stack:** Java 17 + Spring Boot · AWS SQS FIFO, DynamoDB, Lambda, EC2 t3.micro, S3, API Gateway
**Local Dev:** LocalStack + Docker Compose + recorded OpenSky data + WireMock (mocked API responses)

### Data Sources

- **OpenSky Network (ADS-B)** — live aircraft positions (recorded for dev, live for prod). Free.
- **FlightAware AeroAPI** — real-time flight schedules, gates, taxi times. $0.005/call, ~$30–50/month.
- **Aviation Weather (aviationweather.gov)** — METAR/TAF observations. Free, no key required.

### Monthly Cost

**~$0 during dev (Weeks 1–4) · ~$33–55 during AWS validation (Weeks 5–6)**

Breakdown: ~$3–5 AWS infrastructure + ~$30–50 AeroAPI. During local development, all API responses are mocked via WireMock, so no API costs are incurred until live validation begins.

---

## Development Strategy: Local-First, Deploy-Late

This plan follows a local-first development philosophy. For the first four weeks, all development and testing happens on your laptop against LocalStack and recorded/mocked data. You don't touch real AWS or make live API calls until Week 5, when you deploy a proven, tested system.

### Phase 1: Pure Local Development (Weeks 1–4, $0)

#### Recorded OpenSky Data

On Day 1, you run a one-time recording session: a simple script polls the OpenSky `/states/all` endpoint every 30 seconds for 2 hours and writes each raw JSON response to a timestamped file. This produces ~240 snapshots of real flight data, covering ~5,000–10,000 aircraft per snapshot. You store these files in your repository (total size: ~200–400 MB).

You then build a `ReplayOpenSkyClient` that implements the same interface as your real `OpenSkyClient` but reads from these saved files, advancing through them on each poll cycle. Your entire ingestion pipeline doesn't know the difference. This is controlled by a Spring profile:

```yaml
# application-local.yml (used during development)
opensky.mode: replay
opensky.replay-dir: ./data/recorded-opensky/

# application-prod.yml (used on AWS)
opensky.mode: live
opensky.api-url: https://opensky-network.org/api
```

#### WireMock for Schedule API

During local development, the FlightAware AeroAPI is mocked using WireMock. You create a set of realistic mock responses based on real flight data captured during your OpenSky recording session. This means you can develop and test the full schedule resolution pipeline without making any live API calls or incurring costs.

WireMock runs as a Docker container alongside LocalStack. Your Spring local profile points the AeroAPI client at the WireMock endpoint:

```yaml
# application-local.yml
schedule.api.endpoint: http://wiremock:8080/aeroapi

# application-prod.yml
schedule.api.endpoint: https://aeroapi.flightaware.com/aeroapi
```

#### Synthetic Flight Generator

Beyond replaying recorded data, you build a `SyntheticFlightGenerator` that creates fake but realistic flight data for testing edge cases that are hard to capture in a 2-hour recording:

- A flight that circles in a holding pattern before landing (tests state machine edge cases).
- An aircraft that flies 3–4 consecutive legs with increasing delays (tests cascade detection end-to-end).
- A sudden burst of 20 delayed arrivals at one airport within 30 minutes (tests disruption scoring at the extremes).
- Flights with no API schedule match (tests the fallback route-average estimation logic).
- Out-of-order position updates with duplicate timestamps (tests idempotency).

The generator is parameterized: `generateCascadeScenario(legs=4, initialDelayMinutes=45, decayFactor=0.85)` returns a time-ordered stream of FlightPosition updates. Pair each synthetic flight with a corresponding WireMock stub so the API returns the correct schedule when queried.

#### LocalStack Replaces AWS

LocalStack emulates SQS, DynamoDB, S3, and Lambda on your laptop in a single Docker container. Your `docker-compose.yml` spins up everything:

```yaml
services:
  localstack:
    image: localstack/localstack
    ports: ["4566:4566"]
    environment:
      - SERVICES=sqs,dynamodb,s3
  wiremock:
    image: wiremock/wiremock:latest
    ports: ["8080:8080"]
    volumes:
      - ./wiremock:/home/wiremock
  skytrack:
    build: .
    depends_on: [localstack, wiremock]
    environment:
      - SPRING_PROFILES_ACTIVE=local
      - AWS_ENDPOINT=http://localstack:4566
```

### Phase 2: AWS Validation (Weeks 5–6, ~$33–55/month)

Only once your entire pipeline works locally do you deploy to real AWS and enable live AeroAPI calls. At that point you're deploying a proven system, not debugging on a running meter. The deploy step is a profile swap: change `SPRING_PROFILES_ACTIVE` from `local` to `prod`, point at real AWS endpoints and the real AeroAPI, and the same code runs against real services.

---

## Cost-Optimized AWS Architecture

| Component | Service | Free Tier? | Est. Monthly Cost |
|---|---|---|---|
| Streaming/Messaging | SQS FIFO (2 queues) | 1M requests/mo free | $0 |
| Compute (processing) | EC2 t3.micro | 750 hrs/mo free (12 mo) | $0 |
| State store | DynamoDB on-demand | 25 GB + 25 WCU/RCU free | ~$1–2 |
| Ingestion poller | Lambda + EventBridge | 1M invocations free | $0 |
| Historical storage | S3 (Parquet files) | 5 GB free | <$1 |
| API serving | API Gateway + Lambda | 1M API calls free | $0 |
| Schedule data | FlightAware AeroAPI | $0.005/call | ~$30–50 |
| Weather data | aviationweather.gov | Free, no key | $0 |
| Monitoring | CloudWatch | Basic metrics free | $0 |
| | | **Total** | **~$33–55/month** |

### SQS FIFO Instead of Kinesis

SQS FIFO queues support `MessageGroupId`, which provides per-key ordering — the same guarantee that Kinesis shard partitioning provides. Set `MessageGroupId` to `icao24` for the positions queue and to airport code for the airport-events queue. This is a deliberate, defensible trade-off: SQS FIFO gives you per-key ordering at zero cost. In production, you'd migrate to Kinesis for replay capability and fan-out.

### EC2 t3.micro Instead of ECS Fargate

EC2 t3.micro is free for 12 months (750 hours/month, covering 24/7 operation). You still deploy your Spring Boot app in Docker on the EC2 instance. The interview narrative: "I deployed to a containerized EC2 instance, and I'd migrate to ECS Fargate for production to get auto-scaling and zero-downtime deployments."

### FlightAware AeroAPI as Sole Schedule Source

Rather than building a statistical inference engine from BTS data, this plan relies entirely on real-time schedule data from the FlightAware AeroAPI. This is a cleaner, more reliable approach that provides higher accuracy and richer data (gate assignments, actual taxi times, aircraft type).

#### How It Works

- **Daily prefetch:** A Lambda function triggered by EventBridge once daily at 00:00 UTC calls the AeroAPI to retrieve all scheduled US domestic flights for the current date. Writes each schedule record to DynamoDB under `PK=SCHEDULE#<callsign>#<date>`, `SK=<arrivalAirport>`. Set TTL to 24 hours.
- **On-demand lookup:** When the state machine detects a landing and the prefetch cache has no entry, make a live API call. Cache the result in DynamoDB for future lookups.
- **Rate-limit guard:** Track API calls in an `AtomicInteger`. When approaching the monthly budget, log a warning and fall through to route-average estimation.

#### Schedule Resolution Tiers

The `ScheduleResolver` uses a simplified two-tier approach:

- **VERIFIED:** AeroAPI returned a schedule for this callsign + date + airport. This is the primary path for the vast majority of flights.
- **ESTIMATED:** No API data available (API miss, rate limit hit, or non-covered flight). Fall back to route-average block time estimation using historical averages from previously cached API data. Delay events with ESTIMATED confidence are flagged but still included in disruption scoring with reduced weight.

#### AeroAPI Details

- **Endpoint:** `GET https://aeroapi.flightaware.com/aeroapi/flights/{ident}`
- **Returns:** `scheduled_out`, `scheduled_in`, `actual_out`, `actual_in`, `gate_origin`, `gate_destination`, `route`, `aircraft_type`.
- **Pricing:** $0.005 per API call. Daily prefetch of ~200 bulk calls = ~$30/month. On-demand lookups for cache misses add ~$10–20/month.
- **Setup:** Register at flightaware.com/aeroapi. Requires a credit card. Approval is instant.
- **Bonus data:** Gate assignments and actual taxi times enable richer delay breakdowns (gate delay vs taxi delay vs airborne delay).

#### Alternative: aviationstack

If AeroAPI is not preferred, aviationstack is a viable alternative. Free tier provides 100 requests/month (HTTP only). Basic plan at $49.99/month provides 10,000 requests with HTTPS. The `FlightScheduleApiClient` interface abstracts the provider, so switching is a configuration change.

```yaml
# application-prod.yml
schedule.api.enabled: true
schedule.api.provider: aeroapi  # or aviationstack
schedule.api.key: ${SCHEDULE_API_KEY}
schedule.api.daily-prefetch: true
schedule.api.max-monthly-calls: 10000
```

---

## Architecture Overview

The system follows a layered streaming architecture with five core components: ingestion, schedule resolution, processing, storage, and serving.

### Data Flow

**Ingestion Layer:** A Lambda function triggered by EventBridge every 30 seconds polls the OpenSky Network API, normalizes raw ADS-B state vectors into canonical FlightPosition events, and publishes them to an SQS FIFO queue (`skytrack-positions.fifo`) with `MessageGroupId = icao24`.

**Schedule Resolution Layer:** A daily prefetch Lambda populates a DynamoDB cache with the day's scheduled flights from AeroAPI. When a landing is detected, the ScheduleResolver queries DynamoDB for the cached schedule. If found, confidence = VERIFIED. If not found, an on-demand API call is attempted. If that also fails (rate limit, API error), fall back to route-average block time estimation with confidence = ESTIMATED.

**Processing Layer:** A Java Spring Boot application running on EC2 t3.micro polls the SQS positions queue, maintaining in-memory state per aircraft (`ConcurrentHashMap`). The state machine tracks each aircraft through flight phases (UNKNOWN → EN_ROUTE → APPROACHING → ON_GROUND → DEPARTED), detects landings, resolves schedules, computes delays, and emits airport-level events to a second SQS FIFO queue (`skytrack-airport-events.fifo`) with `MessageGroupId = airport code`.

**Storage Layer:** DynamoDB (on-demand) holds live state: current aircraft positions, active delays with confidence tags, airport disruption scores, and the schedule API cache. S3 stores historical data in Parquet format, partitioned by date and airport.

**Serving Layer:** API Gateway + Lambda exposes a REST API for querying live state, historical trends, and schedule coverage statistics. WebSocket API pushes real-time disruption updates to connected clients.

### Key Design Decisions

**SQS FIFO MessageGroupId as partition key:** Two queues with different grouping strategies. The positions queue groups by `icao24` (aircraft-level ordering). The airport-events queue groups by airport code (airport-level ordering). Same per-key ordering guarantee as Kinesis shard partitioning at zero cost.

**DynamoDB single-table design:** Aircraft state, airport metrics, delay events, and schedule cache share one table with composite keys. `SCHEDULE#` keys hold the API-cached schedule data. TTL on position and delay records for automatic cleanup.

**In-memory sliding windows:** Airport disruption scores use time-bucketed counters (`ConcurrentHashMap` with minute-granularity buckets), flushed to DynamoDB periodically. Avoids per-event DynamoDB writes.

**Schedule confidence tracking:** Every delay computation carries a confidence tag (VERIFIED or ESTIMATED). VERIFIED comes from the AeroAPI cache. ESTIMATED comes from route-average block time fallback. This flows through the entire system to the API, so consumers know how much to trust a reported delay.

**Spring profiles for environment switching:** All external dependencies (AWS endpoints, API endpoints, data source mode) are controlled by Spring profiles. Switching between local (LocalStack + WireMock + replayed data) and prod (real AWS + live AeroAPI + live OpenSky) requires no code changes — only a profile swap.

---

## Prerequisites & Setup (Before Week 1)

Complete these items before starting the build. Estimated time: 3–4 hours over a weekend.

### Accounts & Data Downloads

- **OpenSky Network:** Register at opensky-network.org. Free tier allows 400 API calls/day. You only need this for the one-time recording session on Day 1 (~240 calls).
- **FlightAware AeroAPI (REQUIRED):** Register at flightaware.com/aeroapi. Requires a credit card even for the personal tier. Approval is instant. This is your sole source of schedule data.
- **AWS Account:** Create or use existing. Set billing alerts at $5 and $50. You won't need this until Week 5.
- **GitHub Repo:** Initialize with Java .gitignore, README, Apache 2.0 license. Create project board with 6 milestones.

### Local Development Environment

- **Java 17 (LTS):** Install via SDKMAN or Adoptium. Verify: `java --version`.
- **Maven:** Install and verify: `mvn --version`.
- **Docker + Docker Compose:** Required for LocalStack, WireMock, and eventual EC2 deployment.
- **LocalStack:** Pull the image: `docker pull localstack/localstack`.
- **WireMock:** Pull the image: `docker pull wiremock/wiremock`. This mocks AeroAPI responses during local development.
- **AWS CLI v2:** Install and configure. For local dev, point at LocalStack.
- **IDE:** IntelliJ IDEA Community Edition. Install Lombok plugin.

### WireMock Setup

Before starting Week 1, prepare a set of WireMock stubs for the AeroAPI. After your OpenSky recording session (Day 3), extract the callsigns from your recorded data and create realistic schedule responses for them. This gives you deterministic, repeatable test data without hitting the real API.

Store stub mappings in `wiremock/mappings/` and response bodies in `wiremock/__files/`. WireMock will automatically load them on startup.

---

## WEEK 1 · Days 1–7

**Java Foundation, Data Recording & Local Pipeline**

> **Goal:** Learn Java/Spring Boot essentials, record real OpenSky data, set up LocalStack + WireMock, and build a working ingestion pipeline — all running locally at zero cost.

### Days 1–2: Java & Spring Boot Crash Course

- **Set up a Spring Boot project:** Use start.spring.io with dependencies: Spring Web, Lombok, Jackson, Spring Scheduling. Create a basic REST endpoint that returns hardcoded JSON.
- **Java essentials to focus on:** Generics, `Optional<T>`, records (Java 16+), streams and lambdas, try-with-resources, `CompletableFuture` basics, `ConcurrentHashMap`.
- **Build a small exercise:** Create a service class that fetches JSON from a public API, deserializes with Jackson into a Java record, and returns it via a REST endpoint.

### Day 3: Record Real OpenSky Data

This is the only day you hit the real OpenSky API. After this, all development uses recorded data.

- **Study the OpenSky API:** Read docs at openskynetwork.github.io/opensky-api. The `/states/all` endpoint returns all currently tracked aircraft.
- **Build the recording script:** A simple Java class that polls `/states/all` every 30 seconds for 2 hours, writing each JSON response to `data/recorded-opensky/<timestamp>.json`. Produces ~240 files.
- **Run the recording session:** Pick an afternoon when US domestic traffic is heavy (weekday, 2–6 PM ET). Verify: ~240 JSON files totaling 200–400 MB.
- **Build the ReplayOpenSkyClient:** Implements the same `OpenSkyClient` interface but reads from recorded files. Supports configurable replay speed (1x or 10x fast-forward).
- **Create WireMock stubs:** Extract callsigns from your recorded data. For each, create a realistic AeroAPI response stub in `wiremock/mappings/`. This ensures your schedule resolution works with your recorded flights.

### Day 4: OpenSky Integration + Data Model

- **Build the real OpenSkyClient:** For production use. Makes HTTP calls, deserializes into `FlightState` records, filters to US-relevant flights.
- **Data model:** Define `FlightPosition` record: `icao24`, `callsign` (trimmed), `latitude`, `longitude`, `baroAltitude`, `velocity`, `heading`, `onGround`, `lastContact`, `timePosition`, `parsedAt`.
- **Client interface:** Both `ReplayOpenSkyClient` and `LiveOpenSkyClient` implement `FlightDataSource`. Spring injects the correct one based on the active profile.
- **Scheduled polling:** Use Spring `@Scheduled` to call the `FlightDataSource` every 30 seconds.

### Days 5–6: LocalStack + WireMock Setup & SQS Pipeline

Set up `docker-compose.yml` with LocalStack (SQS + DynamoDB), WireMock (AeroAPI mock), and your Spring Boot app. Add init script that creates the two SQS FIFO queues on startup: `skytrack-positions.fifo` (`MessageGroupId = icao24`) and `skytrack-airport-events.fifo` (`MessageGroupId = airport code`).

Build the SQS producer (serialize FlightPosition records, publish to `skytrack-positions.fifo` with `MessageGroupId = icao24`, batch with `SendMessageBatch`). Build the SQS consumer (poll with 20-second long poll, deserialize FlightPosition events, delete after processing).

### Day 7: AeroAPI Client & Synthetic Generator

Build the `FlightScheduleApiClient` interface and the `AeroApiClient` implementation. For local development, this client hits WireMock instead of the real API. Implement the daily prefetch job logic and on-demand lookup with rate-limit guard.

Build the `SyntheticFlightGenerator` for testing edge cases. For each synthetic scenario, create matching WireMock stubs so the schedule resolver returns correct data.

Write integration tests: test serialization/deserialization, replay client, SQS round-trip against LocalStack, and AeroAPI client against WireMock.

### Week 1 Deliverables

| Deliverable | Est. Hours | Priority |
|---|---|---|
| Spring Boot project with dual OpenSky clients | 4–5 | Critical |
| Recorded OpenSky data (~240 snapshots) | 1–2 | Critical |
| ReplayOpenSkyClient with configurable speed | 2–3 | Critical |
| docker-compose.yml with LocalStack + WireMock | 2–3 | Critical |
| SQS FIFO producer + consumer (via LocalStack) | 3–4 | Critical |
| FlightScheduleApiClient + AeroApiClient | 2–3 | Critical |
| WireMock stubs for recorded callsigns | 1–2 | High |
| SyntheticFlightGenerator (basic scenarios) | 1–2 | High |
| Integration tests | 2–3 | High |

> ✅ **Checkpoint:** Run `docker compose up` and see: recorded OpenSky data flowing through your Spring Boot app → SQS FIFO queue (LocalStack) → consumer logging positions. AeroAPI client successfully resolves schedules against WireMock. AWS cost: $0.

---

## WEEK 2 · Days 8–14

**DynamoDB, Aircraft State Machine & Schedule Resolution**

> **Goal:** Build the stateful processing engine and integrate the schedule API cache. By the end of this week, your system tracks aircraft, detects landings, and resolves schedules from the API cache — all running locally.

### Days 8–9: DynamoDB Design & Setup (on LocalStack)

Single-table design with composite keys. PK and SK are generic strings encoding entity type and ID. Access patterns: `AIRCRAFT#<icao24>/CURRENT`, `AIRCRAFT#<icao24>/POSITION#<ts>`, `AIRPORT#<code>/DELAY#<ts>`, `AIRPORT#<code>/SCORE`, `SCHEDULE#<callsign>#<date>`.

Create the table on LocalStack with on-demand capacity mode. Create a GSI (GSI1) for airport-score queries. Set TTL on position records (24 hours). Build the `DynamoDbRepository` class with methods for all entity types including schedule cache operations.

### Days 10–12: Aircraft State Machine

- **Aircraft states:** UNKNOWN → EN_ROUTE → APPROACHING → ON_GROUND → DEPARTED. Transitions determined by altitude changes, ground speed, and `onGround` flag.
- **In-memory state store:** `ConcurrentHashMap<String, AircraftState>` keyed by `icao24`. Holds current position, phase, estimated origin/destination, route history, last update timestamp, callsign at each transition.
- **Route reconstruction:** When an aircraft transitions to ON_GROUND, record a completed leg with the detected arrival airport (nearest airport by Haversine distance) and actual arrival time. Emit a `LandingEvent`.
- **Stale state eviction:** If no position report in 5 minutes → STALE. After 15 minutes → evict from memory, write final state to DynamoDB.
- **Flush to DynamoDB:** Every 60 seconds, batch-write current state for all active aircraft.

### Day 13: Schedule Resolution Pipeline

This is where the API-only approach pays off with a cleaner, simpler architecture.

**Build the ScheduleResolver:** Interface: `resolve(callsign, date, arrivalAirport)` → `ScheduleResolution(scheduledArrival, confidence, source)`. Resolution flow:

- **Step 1 — Cache lookup:** Query DynamoDB for `SCHEDULE#<callsign>#<date>`. If found, return with `confidence=VERIFIED`.
- **Step 2 — On-demand API call:** If cache miss, call AeroAPI live (if within rate-limit budget). Cache the result. Return with `confidence=VERIFIED`.
- **Step 3 — Route-average estimation:** If API call fails or rate limit exceeded, estimate using average block time for this origin–destination pair computed from previously cached API data. Return with `confidence=ESTIMATED`.

Build the route-average estimator: maintain a running average of scheduled block times per (origin, destination) pair from all cached API responses. This provides a reasonable fallback without needing any external data.

### Day 14: Callsign Mapping, Airport Data & Testing

Build `CallsignParser`: extract airline ICAO code and flight number from OpenSky callsigns (e.g., UAL1234 → carrier=UA, flight=1234). Maintain ICAO→IATA mapping. Mark cargo/charter/military callsigns as non-commercial and skip schedule resolution.

Download OurAirports dataset for nearest-airport lookups. Run comprehensive tests: state transitions with synthetic flights, schedule resolution against WireMock, callsign parsing edge cases, DynamoDB operations against LocalStack.

### Week 2 Deliverables

| Deliverable | Est. Hours | Priority |
|---|---|---|
| DynamoDB table + GSI on LocalStack | 2–3 | Critical |
| DynamoDB repository layer with batch writes | 2–3 | Critical |
| AircraftState machine with 5 phases | 4–5 | Critical |
| In-memory state store with eviction | 2–3 | Critical |
| ScheduleResolver with API cache + fallback | 3–4 | Critical |
| Daily prefetch job (local, against WireMock) | 1–2 | Critical |
| Route-average estimator from cached data | 1–2 | High |
| CallsignParser + airport reference data | 1–2 | High |
| Unit and integration tests | 3–4 | High |

> ✅ **Checkpoint:** Run the full local pipeline with recorded data. Your system should: (1) track aircraft and detect landings, (2) resolve schedules from the WireMock-backed API cache. Pick a real callsign from your recorded data, trace it through the state machine until it lands, and verify the ScheduleResolver returns the correct scheduled arrival time from the mock API. AWS cost: still $0.

---

## WEEK 3 · Days 15–21

**Delay Detection & Airport Disruption Scoring**

> **Goal:** Add the intelligence layer. Detect delays by comparing actual arrivals against API-provided schedules, compute airport-level disruption scores, and begin tracking cascade patterns. Still running entirely locally.

### Days 15–16: Delay Computation Engine

When the state machine detects ON_GROUND at a recognized airport, emit a `LandingEvent`. `DelayComputer` receives LandingEvents, calls `ScheduleResolver.resolve(callsign, date, arrivalAirport)`. If schedule found: delay = actualArrival − scheduledArrival. Delayed if > 15 minutes (FAA standard).

**DelayEvent model:** `flightId`, `airline`, `origin`, `destination`, `scheduledArrival`, `actualArrival`, `delayMinutes`, `confidence` (VERIFIED/ESTIMATED), `source` (API_CACHE/API_LIVE/ROUTE_AVG), `weatherCategory` (added Week 4). Write to DynamoDB and publish to `skytrack-airport-events.fifo`.

With AeroAPI data, you can also compute richer delay breakdowns: gate delay (`actual_out − scheduled_out`), taxi-out delay, airborne delay, and taxi-in delay. This is data that a BTS inference engine could never provide.

### Days 17–19: Airport Disruption Score

Sliding window implementation: `ConcurrentHashMap<String, TreeMap<Long, BucketMetrics>>` keyed by airport code. Each bucket = 1 minute. Keep 60 buckets (1-hour window). Evict expired buckets every minute.

Score computation: weighted sum of (1) delayed flight count (0.3), (2) average delay severity (0.3), (3) trend direction (0.2), (4) percentage of flights delayed (0.2). Normalize to 0–100. Weight VERIFIED delays more heavily than ESTIMATED. Maintain a sorted set of airports by score for "top 10 most disrupted" queries.

### Days 20–21: Cascade Detection

When an aircraft lands late, query the AeroAPI cache for the same aircraft's next scheduled departure from that airport. AeroAPI provides tail-number-level schedule data, which is more accurate for cascade prediction than flight-number-based approaches.

Propagation model: expected downstream delay = current delay × 0.85 per leg (airlines build in buffer). If predicted delay > 15 minutes, emit a `CascadeAlert`. Test with the SyntheticFlightGenerator's 4-leg cascade scenario.

### Week 3 Deliverables

| Deliverable | Est. Hours | Priority |
|---|---|---|
| DelayComputer with ScheduleResolver integration | 3–4 | Critical |
| DelayEvent model with confidence tagging | 1–2 | Critical |
| Rich delay breakdown (gate/taxi/airborne) | 2–3 | High |
| Airport disruption score with sliding windows | 4–5 | Critical |
| Top-N disrupted airports query | 1–2 | High |
| Cascade detection with API route data | 3–4 | High |
| SyntheticFlightGenerator expanded scenarios | 1–2 | High |
| Tests for delay computation and scoring | 2–3 | High |

> ✅ **Checkpoint:** Run the full pipeline locally with recorded data. You should see delay events with confidence tags, disruption scores for airports, and cascade alerts. Use 10x replay speed to simulate several hours. AWS cost: still $0.

---

## WEEK 4 · Days 22–28

**Weather Correlation, Historical Storage & AWS Prep**

> **Goal:** Add weather correlation, set up S3-based historical storage with Parquet, build the REST API, and prepare the production Spring profile for AWS deployment. Last week of pure local development.

### Days 22–23: Weather Data Integration

aviationweather.gov provides free METARs and TAFs for all US airports. No API key needed. Build a `WeatherObservation` model (`airport`, `timestamp`, `visibility`, `ceiling`, `windSpeed`, `windGust`, `precipitationType`, `flightCategory`). Scheduled job every 15 minutes. For local dev, record one set of real METAR data and replay it.

Enrich each `DelayEvent` with the concurrent weather observation. Tag with `flightCategory` (VFR/MVFR/IFR/LIFR). Over time this enables analysis like "when ORD is IFR, average delay increases by 35 minutes."

### Days 24–25: Historical Data Pipeline (S3 + Parquet)

Every 5 minutes, serialize position updates and delay events to Parquet using Apache Parquet Java libraries. Upload to S3 (LocalStack locally, real S3 in prod). Partition by date/hour. Schema includes: `flightId`, `origin`, `destination`, `scheduledArrival`, `actualArrival`, `delayMinutes`, `confidence`, `source`, `weatherCategory`, `disruptionScore`, `gateDelay`, `taxiDelay`, `airborneDelay`.

### Days 26–27: REST API Layer

- **GET /flights/{callsign}:** Current position, state, delay status, confidence, route history, gate information.
- **GET /airports/{code}/status:** Disruption score, active delays with confidence breakdown, weather, cascade risks.
- **GET /airports/disruptions:** Top-N most disrupted airports. Supports `limit`, `minScore`, `minConfidence` filters.
- **GET /cascades/{airport}:** Predicted cascade delays for inbound flights.
- **GET /analytics/delays:** Historical query (Athena in prod, mock locally).
- **GET /schedule/coverage:** Schedule resolution stats: total landings, API cache hits, API live lookups, route-average fallbacks, unmatched. Useful for monitoring API cache efficiency.

### Day 28: Production Profile & AWS Prep

Prepare the prod Spring profile: real AWS endpoints, real AeroAPI endpoint with live key, real OpenSky API URL, real SQS queue ARNs, real DynamoDB table name, real S3 bucket name.

Dockerfile: multi-stage build (Maven build stage → Eclipse Temurin JRE 17 slim runtime). Keep image under 200 MB. Expose `/actuator/health`.

AWS resource list and `setup.sh` script: 2 SQS FIFO queues, 1 DynamoDB table with GSI, 1 S3 bucket, 1 EC2 t3.micro instance, 1 Lambda (OpenSky poller), 1 Lambda (schedule API daily prefetch), 1 API Gateway REST API.

### Week 4 Deliverables

| Deliverable | Est. Hours | Priority |
|---|---|---|
| Weather poller and METAR parser | 2–3 | High |
| Weather-delay correlation tagging | 1–2 | High |
| S3 Parquet writer with partitioning | 2–3 | Critical |
| 6 REST API endpoints (local Spring Boot) | 4–5 | Critical |
| Production Spring profile (application-prod.yml) | 1–2 | Critical |
| Dockerfile + setup.sh for AWS resources | 2–3 | Critical |
| Error handling, validation, input checks | 1–2 | High |

> ✅ **Checkpoint:** Your entire system works end-to-end locally: recorded data → SQS → state machine → API-based schedule resolution → delay detection → disruption scoring → REST API. Hit `localhost:8080/airports/ORD/status` and get a meaningful response. Production profile is ready. AWS cost: still $0.

---

## WEEK 5 · Days 29–35

**AWS Deployment & Live Data Validation**

> **Goal:** Deploy your proven system to real AWS. Switch to live OpenSky data and live AeroAPI calls. Validate with real flight traffic. This is where costs begin (~$33–55/month).

### Days 29–30: AWS Resource Setup

Create all AWS resources: SQS FIFO queues (4-day retention, content-based deduplication), DynamoDB table (on-demand, GSI1, TTL enabled), S3 bucket (Intelligent-Tiering), EC2 t3.micro (Amazon Linux 2023, Docker installed, security group for ports 8080 and 22, IAM role for SQS/DynamoDB/S3).

Create Lambda functions: (1) OpenSky ingestion poller triggered by EventBridge every 30 seconds, (2) AeroAPI daily prefetch triggered by EventBridge at 00:00 UTC. Set `SCHEDULE_API_KEY` as an encrypted environment variable. Create API Gateway REST API.

### Days 31–32: Deploy & Validate

Deploy to EC2: `docker build`, push to ECR, `docker run` with `SPRING_PROFILES_ACTIVE=prod`. Activate the Lambda pollers. Let the system run for 4–6 hours with live data.

**Validation checklist:** Aircraft state tracking works with real traffic, AeroAPI prefetch populates the cache correctly, schedule resolution returns VERIFIED for the majority of flights, delay events have sensible values, disruption scores respond to actual airport conditions.

Compare to known delays: look up FlightAware or Google Flights for currently-delayed flights. Verify your system detected the same delays. Monitor the `/schedule/coverage` endpoint to track API cache hit rate. Target: >90% VERIFIED resolution rate.

### Days 33–34: API Quality & WebSocket

API Gateway configuration: usage plans, rate limiting (1000 requests/day for demo), API keys. Generate OpenAPI/Swagger spec. Build WebSocket API for real-time disruption updates (push when scores change by >5 points). Add DynamoDB-backed response caching (TTL 30 seconds).

### Day 35: Athena Setup

Create Glue database and table pointing to S3 Parquet data. Verify SQL queries work. Connect the `GET /analytics/delays` endpoint to Athena via the AWS SDK.

### Week 5 Deliverables

| Deliverable | Est. Hours | Priority |
|---|---|---|
| AWS resources created (all) | 3–4 | Critical |
| Application deployed to EC2 with prod profile | 2–3 | Critical |
| Lambda OpenSky poller + EventBridge trigger | 2–3 | Critical |
| Lambda AeroAPI prefetch + EventBridge trigger | 1–2 | Critical |
| API Gateway REST API | 2–3 | Critical |
| Live data validation (4–6 hours) | 2–3 | Critical |
| WebSocket API for real-time updates | 2–3 | High |
| Athena table + analytics endpoint | 1–2 | High |

> ✅ **Checkpoint:** Your system is running on real AWS with live flight data and live AeroAPI schedule resolution. Hit your API endpoint and get real-time delay information. Validate against FlightAware that detected delays are accurate. Schedule coverage should show >90% VERIFIED.

---

## WEEK 6 · Days 36–42

**Dashboard, Observability & Interview Prep**

> **Goal:** Build a lightweight dashboard, add monitoring, write documentation, and prepare interview narrative. This week turns a good project into a great one.

### Days 36–38: Lightweight Dashboard

- **Airport map view:** React + Leaflet. Plot US airports as circles sized/colored by disruption score. Click for detail panel: live delays with confidence tags, weather, cascade alerts, gate-level delay breakdown.
- **Flight tracker:** Search by callsign. Show position on map, route history, delay status, gate information from AeroAPI.
- **Live leaderboard:** Top 10 disrupted airports with auto-refresh and trend arrows.
- **Host on S3 + CloudFront:** Static site hosting, essentially free.

### Days 39–40: Observability & Hardening

CloudWatch dashboard with key metrics: SQS queue depth, DynamoDB consumed capacity, Lambda invocations/errors, EC2 CPU/memory, API cache hit rate, schedule resolution rate (VERIFIED vs ESTIMATED), delay events per minute, AeroAPI call count vs budget.

Structured JSON logging with correlation IDs. SQS dead letter queue for failed messages. Graceful shutdown: flush in-memory state to DynamoDB on SIGTERM.

### Days 41–42: Documentation & Interview Prep

**README:** Architecture diagram, setup instructions (local and AWS), API reference, AeroAPI integration details, dashboard screenshots.

**Architecture Decision Records (5 ADRs):**

1. Why SQS FIFO over Kinesis and the migration path.
2. Why single-table DynamoDB.
3. Why a real-time schedule API over BTS inference — trade-off of cost for accuracy, richer data, and simpler architecture.
4. Why in-memory sliding windows.
5. Why local-first development with LocalStack + WireMock.

### Interview Talking Points

**"Walk me through the architecture."** Cover all 5 layers with emphasis on the real-time API integration, the caching strategy, and cost trade-offs.

**"What was the hardest challenge?"** Designing a reliable schedule resolution pipeline that gracefully handles API failures and rate limits, building a caching strategy that maximizes API cache hits while minimizing cost, and architecting the system so it works identically on LocalStack/WireMock and real AWS/AeroAPI via Spring profiles.

**"How would you scale to 10x?"** Migrate SQS to Kinesis for replay and fan-out, move EC2 to ECS Fargate for auto-scaling, upgrade AeroAPI plan for higher call volume, add DynamoDB auto-scaling.

**"What would you do differently?"** Use Kafka for replay capability, add Flink for complex event processing, integrate FAA SWIM for tail-number tracking.

**Metrics to quote:** Events/second, p99 latency, API cache hit rate, schedule resolution rate, DynamoDB consumed capacity, API response times, delay detection accuracy vs FlightAware ground truth.

---

## Risk Mitigation

### Risk: AeroAPI Dependency & Cost

The system depends entirely on AeroAPI for schedule data. Mitigations: (1) The daily prefetch + DynamoDB cache means most lookups never hit the API. (2) Rate-limit guard prevents surprise quota exhaustion. (3) Route-average estimation provides a degraded but functional fallback. (4) The `FlightScheduleApiClient` interface abstracts the provider — switching to aviationstack or another provider is a configuration change. (5) Monitor API costs via the `/schedule/coverage` endpoint and CloudWatch. (6) If costs become a concern, aviationstack's free tier (100 req/month) can supplement for a subset of flights.

### Risk: API Downtime or Rate Limits

If AeroAPI is unavailable or rate-limited during the daily prefetch, the cache may be incomplete. Mitigations: (1) Retry logic with exponential backoff on the prefetch Lambda. (2) On-demand lookups fill cache gaps when individual flights land. (3) Route-average estimation covers any remaining gaps. (4) Cache TTL is 24 hours, so a brief outage during prefetch is recovered by on-demand lookups throughout the day.

### Risk: Low Schedule Match Rate

If <90% of detected landings resolve to VERIFIED, investigate: (1) Callsign parser edge cases (codeshares, charters, cargo, repositioning flights). (2) ICAO/IATA code normalization. (3) AeroAPI coverage gaps for certain carriers or routes. (4) International flights will not have API coverage — filter to domestic-only for scoring. (5) Widen matching window (±30 minutes).

### Risk: OpenSky API Rate Limits

Only needed for the Day 1 recording session and the production Lambda poller. The recording session uses ~240 calls (well within 400/day). Production polling at 30-second intervals = 2,880/day, well within limits. Fallback: ADS-B Exchange ($10/month).

### Risk: AWS Free Tier Expiration

EC2 t3.micro is free for 12 months only. After that, ~$8.50/month. Mitigations: stop the instance when not demoing, or migrate to a $5/month VPS.

### Risk: Java Learning Curve

Focus on patterns you need (Spring Boot, Jackson, AWS SDK, streams). Use Lombok and records. The local-first approach means fast iteration without cloud deploy waits. If Week 1 takes longer, compress Week 6 rather than cutting core features.

---

## Total Cost Summary

| Phase | Duration | Cost |
|---|---|---|
| Local development (Weeks 1–4) | 4 weeks | $0 |
| AWS validation (Weeks 5–6) | 2 weeks | ~$33–55 |
| Ongoing (demo-ready) | As needed | ~$33–55/month |

Note: The primary cost driver is the AeroAPI subscription at ~$30–50/month. AWS infrastructure costs remain at ~$3–5/month. When not actively running the system, stop the EC2 instance and disable the Lambda pollers to reduce costs to near zero (only DynamoDB storage persists).

---

**Start this weekend.**

Record your OpenSky data on Day 1. Register for AeroAPI. Build locally with WireMock. Deploy when it works.

*The best project is the one that's running, not the one that's planned.*

---

## Stretch Goals

- **ML delay prediction:** Train a gradient-boosted model on historical Parquet data to predict delay probability before departure. AeroAPI's richer data (gate times, taxi times) makes for better features than BTS ever could.
- **Kinesis migration:** Replace SQS FIFO with Kinesis Data Streams. Demonstrate replay capability and multi-consumer fan-out.
- **Infrastructure as Code:** Port all AWS resources to CDK (Java). Infra defined in the same language as the app is a strong signal.
- **Load testing:** Gatling or k6 simulating 1000 concurrent API users. Document throughput and latency.
- **Multi-provider schedule resolution:** Add aviationstack as a secondary provider. Compare accuracy and cost. The `FlightScheduleApiClient` interface already supports this.
