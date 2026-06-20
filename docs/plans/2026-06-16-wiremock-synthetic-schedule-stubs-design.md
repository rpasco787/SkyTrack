# Synthetic Schedule Ground-Truth via Anchored WireMock Stubs — Design

**Date:** 2026-06-16
**Status:** Validated, ready for implementation plan
**Scope:** Local/demo only. **No `src/main` changes** — production stays AeroAPI-free and untouched.

## Problem

The pipeline runs end-to-end (proven in the [2026-06-06 smoke test](../2026-06-06-chunk7-pipeline-smoke-test-results.md)), but every delay resolves to `classification=UNKNOWN` because there is **no schedule ground-truth**: [`ScheduleResolver`](../../skytrack/src/main/java/skytrack/demo/service/ScheduleResolver.java) tries AeroAPI (WireMock) then a route-average fallback that is itself empty until AeroAPI populates it. The WireMock AeroAPI only stubs a handful of callsigns (`AAL100`, `DAL567`, `UAL1234`), none of which appear in the recorded OpenSky replay. Result: disruption scores are 0 and `/airports/disruptions` is empty.

This design supplies **synthetic but plausible** schedule ground-truth so the demo shows real-looking disruptions, delays, and cascades — without paid APIs and without touching production code.

## Key insight: deterministic replay enables exact delay control

The state machine keys arrival off `position.lastContact()` (the original ~2026-03-09 capture timestamp), so **replay is deterministic**: a given flight lands at the same arrival time `T` on every run. Therefore, if we capture `T` once and set `scheduled_in = T − D`, the demo run computes `delay = T − (T − D) = D` exactly. We get precise control over the delay distribution while anchoring to real arrival events.

## Resolution constraints (from code)

- **Request:** `GET /aeroapi/flights/{callsign}?start={date}&end={date}`; WireMock matches on **path only** (date param irrelevant to matching). [`AeroApiClient`](../../skytrack/src/main/java/skytrack/demo/client/AeroApiClient.java)
- **Delay field:** only `scheduled_in` matters — it maps to `FlightSchedule.scheduledArrival`, and `delay = observedArrival − scheduledArrival`.
- **Parseable callsigns only:** a callsign resolves only if it matches `^[A-Z]{3}\d+$` **and** the 3-letter ICAO prefix is one of 16 known carriers ([`CallsignParser`](../../skytrack/src/main/java/skytrack/demo/service/CallsignParser.java)): UAL, AAL, DAL, SWA, JBU, ASA, NKS, FFT, SKW, RPA, ENY, HAL, ACA, WJA, FDX, UPS. This bounds stub volume.
- **Catch-all:** `unknown-missing.json` (priority 10, low precedence) returns `{"flights":[]}` for anything unstubbed; per-callsign stubs (default priority 5) override it.

## Architecture

Two dev-tooling components (both under `src/test`) plus generated stub files. No `src/main` changes.

### Component 1 — `LandingSeedExtractor`
Reuses the **real** `AircraftStateMachine`, `AirportLookupService`, `CallsignParser`, and `ReplayOpenSkyClient` in-memory (no SQS/LocalStack). Maintains a `Map<icao24, AircraftTrack>`, loops `fetchPositions()` over all 370 replay files, and runs each position through `stateMachine.process(...)` — the exact runtime detection path. For every `LandingEvent` whose callsign parses, records `{callsign, icao24, arrivalEpoch, airportIcao, airportIata}`, **deduped to the first landing per callsign**. Writes `wiremock/seed/landing-seed.jsonl`. Runs in seconds (pure in-memory), and landings match runtime exactly because it reuses production components.

**Path handling:** runs with working directory = repo root (where `data/airports/airports.csv` lives) and an explicit `replayDir = ./skytrack/data/recorded-opensky/`. Avoids the data-root split noted in the smoke-test doc.

### Component 2 — `WireMockStubGenerator`
Reads the seed, samples a delay per flight (below), computes `scheduled_in = arrivalEpoch − delaySeconds`, and emits one mapping + one `__files` response per callsign into `wiremock/generated/`. Each response includes `ident`, `ident_iata`, `operator`, `destination.code_iata` (= arrival airport, so `RouteAverageEstimator` also benefits) and the critical `scheduled_in`. **Skips** callsigns that already have hand-written stubs so integration-test fixtures stay authoritative.

### Data flow at demo time (unchanged pipeline)
```
replay → SQS → state machine → LandingEvent
   → ScheduleResolver → GET /flights/{callsign} → WireMock anchored stub
   → delay = exactly the sampled D → classification populated
   → DisruptionScoreService (scores rise) + cascades + S3 Parquet
   → /airports/disruptions, /airports/{iata}/status, /analytics/delays light up
```

## Delay model (deterministic)

Fixed RNG seed ⇒ reproducible stub set. Per flight, draw a band then a uniform delay within it:

| Band | Share | Delay |
|---|---|---|
| On-time | 55% | −5…+14 min |
| Minor | 25% | 15…44 min |
| Major | 12% | 45…119 min |
| Severe | 8% | 120…240 min |

`scheduled_in = arrivalEpoch − delay` (negative delay ⇒ early, `scheduled_in` slightly after arrival — acceptable).

**Hotspot:** for arrivals at **ORD** and **JFK**, skew heavier (~50% in Major/Severe) so `/airports/disruptions` shows a clear worst-airport ranking and enough ≥30-min delays fire cascade alerts. JFK already shows IFR weather in replay → weather-plus-delay story at one airport.

Relevant thresholds: disruption counts delays ≥15 min; cascades trigger ≥30 min.

## Scope, packaging, reversibility

- **Scope:** all parseable first-landings across the full replay (16 carriers only) — bounded to roughly hundreds–low-thousands of stubs.
- **Commit:** the harness, the generator, and the small `landing-seed.jsonl`.
- **Gitignore:** the bulk `wiremock/generated/` stubs (derived; regenerable via one command).
- **docker-compose:** add `wiremock/generated/` to the WireMock mount.
- **Untouched:** everything in `src/main`; prod profile stays AeroAPI-free.

## Testing

- **Generator (TDD, pure logic):** given a seed row + fixed seed, assert band boundaries, the `scheduled_in = arrival − delay` math, hotspot skew, and that the emitted JSON round-trips through `AeroApiClient.parseFlightFromJson` into a `FlightSchedule` with the expected `scheduledArrival`.
- **Extractor:** feed a small fixture of synthetic position snapshots (airborne → on-ground near a known airport) and assert the expected `LandingEvent`s and seed rows; assert dedup-to-first and the parseable-callsign filter.
- **End-to-end (manual):** regenerate stubs, run the local stack, confirm `/airports/disruptions` is non-empty with ORD/JFK ranked high, `/analytics/delays` shows non-UNKNOWN classifications, and cascade alerts appear.

## Out of scope

- Real BTS integration (Option B) — the honest-ground-truth path, deferred.
- Finishing `DailySchedulePrefetchService` (still discards results).
- Any production schedule source.
```
