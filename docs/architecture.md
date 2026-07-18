# SkyTrack Architecture

Real-time flight delay detection and airport-disruption scoring. Aircraft positions flow
through an SQS-decoupled pipeline into a per-aircraft state machine that detects landings,
resolves each landing against a flight schedule, computes the delay, and fans the resulting
`DelayEvent` out to disruption scoring, cascade prediction, and historical (S3 Parquet)
storage. Six REST endpoints serve the results; a dashboard consumes them.

The same Mermaid source below is embedded in the [README](../README.md) so it renders on
GitHub.

## Pipeline

```mermaid
flowchart LR
    subgraph Ingestion
        OS[ReplayOpenSkyClient<br/>recorded OpenSky snapshots]
        PROD[SqsPositionProducer]
        Q[(SQS positions.fifo<br/>MessageGroupId = icao24)]
        OS --> PROD --> Q
    end

    subgraph Processing
        CONS[SqsConsumerService]
        SM[AircraftStateMachine]
        DB[(DynamoDB<br/>aircraft state)]
        LE[LandingEvent]
        Q --> CONS --> SM
        SM <--> DB
        SM --> LE
    end

    subgraph "Schedule resolution"
        SR[ScheduleResolver]
        AERO[WireMock AeroAPI<br/>synthetic stubs / BTS]
        DC[DelayComputer]
        LE --> SR
        SR <--> AERO
        SR --> DC
    end

    DEP[DelayEventProcessor]
    DC --> DEP

    subgraph "Fan-out"
        DS[DisruptionScoreService]
        CCD[CascadeChainDetector<br/>multi-hop tail-rotation walk]
        RCS[(RecentCascadeStore<br/>in-memory per-airport)]
        HW[HistoricalDelayWriter]
        S3[(S3 Parquet<br/>year/month/day/hour)]
        DEP --> DS
        DEP --> CCD --> RCS
        DEP --> HW --> S3
    end

    subgraph Serving
        API[REST controllers<br/>airports / flights / cascades<br/>cascades/accuracy / schedule]
        UI[Dashboard]
        DS --> API
        RCS --> API
        DB --> API
        S3 --> API
        API --> UI
    end

    WX[WeatherCache<br/>METAR enrichment]
    WX -. enriches .-> DEP
```

## The five layers

1. **Ingestion** — `ReplayOpenSkyClient` replays recorded OpenSky ADS-B snapshots (or
   `LiveOpenSkyClient` polls the real API). `SqsPositionProducer` publishes one message per
   aircraft position to `skytrack-positions.fifo` with `MessageGroupId = icao24`, giving
   per-aircraft ordering (SQS FIFO chosen over Kinesis for per-key ordering at $0 cost).

2. **Processing** — `SqsConsumerService` drains the queue into `AircraftStateMachine`, which
   tracks each aircraft through flight phases
   (`UNKNOWN → EN_ROUTE → APPROACHING → ON_GROUND → DEPARTED`), persisting state to DynamoDB
   (single-table design) and emitting a `LandingEvent`
   on touchdown.

3. **Schedule resolution** — `ScheduleResolver` looks up each landing's scheduled arrival via
   `AeroApiClient` (pointed at WireMock-served synthetic stubs locally; a future BTS tier or
   the real AeroAPI in production). `DelayComputer` then computes
   `delay = actualArrival − scheduledArrival`. Synthetic stubs (anchored to real arrivals) and
   a future BTS tier are used in place of a paid real-time flight API.

4. **Processing fan-out** — `DelayEventProcessor` enriches each `DelayEvent` with weather
   (`WeatherCache`, METAR) and dispatches it to three independent consumers:
   `DisruptionScoreService` (in-memory sliding-window airport scores),
   `CascadeChainDetector` (multi-hop tail-rotation chain: walks BTS schedule via
   `findNextDeparture`, propagates delay in delay-space using slack absorption and
   en-route recovery, stores results in `RecentCascadeStore`; config keys
   `skytrack.disruption.en-route-recovery-factor` and `cascade-max-hops`),
   and `HistoricalDelayWriter` (buffers and flushes Parquet to S3,
   partitioned `year/month/day/hour`).

   **Cascade chain recurrence:**
   `depDelay[n] = max(0, carriedArrivalDelay − scheduledSlack[n])`;
   `arrivalDelay[n] = depDelay[n] × (1 − enRouteRecovery)`.
   Chain terminates when a hop's predicted delay falls below the 15-minute threshold,
   the tail rotation ends, a leg is cancelled, or `cascadeMaxHops` (default 8) is reached.
   Two REST endpoints serve results: `GET /cascades/{iata}` (recent chains) and
   `GET /cascades/{iata}/accuracy` (hop-level MAE + backtestable-hop counts against BTS actuals).

5. **Serving** — six Spring REST controllers expose the live and historical results, consumed
   by the dashboard.

## Local-first development

Every external dependency is swapped by Spring profile: LocalStack stands in for
SQS/DynamoDB/S3, WireMock for the schedule API, and recorded fixtures for OpenSky and weather.
Switching `local → prod` is a profile swap, not a code change — no application code is aware
of which backing services are real.
