# SkyTrack — Synthetic Schedule Ground-Truth Demo Results

**Date:** 2026-06-20
**Build:** chunks 1–7 + synthetic stubs, `main` branch
**Profile:** `local` (replay OpenSky + WireMock AeroAPI synthetic stubs + LocalStack SQS/DynamoDB/S3)
**Goal:** Verify that anchored WireMock schedule stubs close the UNKNOWN-delay gap identified in the [2026-06-06 smoke test](2026-06-06-chunk7-pipeline-smoke-test-results.md).

---

## Verdict

**The synthetic stubs resolve the UNKNOWN-delay gap.** Disruption scores are now non-zero, cascade alerts fire, and `/analytics/delays` returns real classifications. All four headline checks pass.

| Check | Before (smoke test) | After (this run) |
|---|---|---|
| `/schedule/coverage` verified | 0 (0%) | **208 / 383 (54.3%)** |
| `/airports/disruptions` | `[]` (empty) | **Non-empty, 10+ airports** |
| `/analytics/delays` classification | `UNKNOWN` across the board | **ON\_TIME / MINOR / MAJOR / SEVERE / MODERATE** |
| Cascade alerts in logs | 0 | **Active (ORD, SFO, BNA, PHX, …)** |

---

## How the stubs work

`WireMockStubGeneratorIT` (gated, `skytrack.tooling=true`) reads `wiremock/seed/landing-seed.jsonl` — 2172 rows extracted by `LandingSeedExtractorIT` from the full 370-file replay via the real `AircraftStateMachine`. For each row it:

1. Samples a delay band from `DelayModel.chooseBand(r, hotspot)` — ORD/JFK are flagged as hotspots (25% on-time vs. 55% for normal airports).
2. Samples `delaySeconds` uniformly within the band (ON\_TIME −5–+14 min, MINOR 15–44, MAJOR 45–119, SEVERE 120–240).
3. Sets `scheduled_in = arrivalEpoch − delaySeconds` and writes `wiremock/generated/gen-<CALLSIGN>.json`.

At demo time WireMock's recursive mapping loader picks up `mappings/generated/*.json`. `AeroApiClient` resolves each callsign, reads `scheduled_in`, and `ScheduleResolver` computes `delay = actualArrival − scheduledArrival`. The result is synthetic but **anchored**: every stub references the real replay arrival, so delay values are plausible durations, not fictional.

---

## Endpoint responses (live)

### `/schedule/coverage`
```json
{"total": 383, "verified": 208, "estimated": 0, "unresolved": 175, "verifiedRate": 0.5430809399477807}
```
54.3% resolved via `AEROAPI`. The 175 unresolved are flights whose callsigns didn't match the 16-carrier `CallsignParser` (general aviation, foreign operators).

### `/airports/disruptions?limit=10`
| Airport | Score | Active Delays | Avg Delay (min) | Trend |
|---|---|---|---|---|
| **ORD** | **81.7** | 10 | 50.0 | 0.67 |
| CAK | 73.0 | 1 | 194.0 | 1.0 |
| PDX | 68.5 | 3 | 39.0 | 1.0 |
| LGA | 62.3 | 7 | 42.6 | 0.5 |
| SFO | 62.1 | 4 | 54.4 | 0.57 |
| ANC | 58.5 | 2 | 51.7 | 0.67 |
| DFW | 57.7 | 9 | 23.4 | 0.47 |
| ATL | 56.6 | 6 | 47.2 | 0.38 |
| SLC | 55.0 | 5 | 46.7 | 0.42 |
| BNA | 49.25 | 4 | 34.5 | 0.5 |

ORD ranks #1 as expected for a hotspot airport. JFK (score 43.2, 3 active delays) falls just outside the top 10 — it had fewer flights in this replay window but still shows elevated delay rates vs. a normal airport.

### `/airports/ORD/status`
```json
{
  "score": {"airportIata": "ORD", "score": 81.67, "activeDelayCount": 10,
            "totalFlightsInWindow": 15, "averageDelayMinutes": 50.0, "trendDirection": 0.67},
  "cascades": [6 active cascade alerts]
}
```

### `/airports/JFK/status`
```json
{
  "score": {"airportIata": "JFK", "score": 43.19, "activeDelayCount": 3,
            "totalFlightsInWindow": 8, "averageDelayMinutes": 38.4, "trendDirection": 0.375},
  "weather": {"flightCategory": "IFR", "visibilityStatuteMiles": 2.0, "ceilingFeet": 800,
              "rawMetar": "METAR KJFK 051430Z 18012G18KT 2SM RA OVC008"},
  "cascades": [3 active cascade alerts]
}
```

### `/analytics/delays?airport=ORD&date=2026-06-20` (sample)
| Callsign | Classification | Delay | Resolution |
|---|---|---|---|
| SKW6007 | MODERATE | 1140 s (19 min) | AEROAPI |
| UAL973 | SEVERE | 11160 s (186 min) | AEROAPI |
| UAL1082 | MAJOR | 4620 s (77 min) | AEROAPI |
| UAL958 | MODERATE | 1200 s (20 min) | AEROAPI |
| ENY3428 | MODERATE | 2340 s (39 min) | AEROAPI |

### `/analytics/delays?airport=JFK&date=2026-06-20` (sample)
| Callsign | Classification | Delay | Resolution |
|---|---|---|---|
| AAL103 | MAJOR | 3480 s (58 min) | AEROAPI |
| DAL2320 | MINOR | 600 s (10 min) | AEROAPI |
| JBU817 | ON\_TIME | −180 s (−3 min) | AEROAPI |

> **Date note:** the S3 Parquet partition is keyed by flush time (today, `2026-06-20`), not the replay arrival epoch (`2026-03-09`). This is a known behavior of `HistoricalDelayWriter`.

### Cascade alerts (log excerpt)
```
CascadeDetector: Cascade alert: AAL1101 at ORD  delay=228min -> predicted downstream=193min
CascadeDetector: Cascade alert: UAL1634 at ORD  delay=104min -> predicted downstream=88min
CascadeDetector: Cascade alert: AAL867  at ORD  delay=35min  -> predicted downstream=29min
CascadeDetector: Cascade alert: UAL1053 at SFO  delay=95min  -> predicted downstream=80min
CascadeDetector: Cascade alert: SWA3293 at PHX  delay=184min -> predicted downstream=156min
CascadeDetector: Cascade alert: FFT1020 at PHX  delay=37min  -> predicted downstream=31min
CascadeDetector: Cascade alert: SWA1155 at BNA  delay=136min -> predicted downstream=115min
CascadeDetector: Cascade alert: SWA1190 at BNA  delay=74min  -> predicted downstream=62min
```

---

## Run conditions

- Replay files processed: ~42 / 370 (≈ 11%) in ~5 min
- Consumer: temporary speed boost applied (reverted, not committed) — same technique as the June 6 run
- `N65V` SQS error: empty-callsign flights rejected by SQS `MessageGroupId` validation (pre-existing, non-blocking)
- `wiremock/generated/` contained 2172 stubs, all loaded by WireMock at startup

---

## What is and isn't synthetic

**Synthetic (this plan):**
- `scheduled_in` times for each flight — sampled from a delay probability distribution anchored to the real arrival epoch.
- Delay magnitudes are plausible but not historically accurate.

**Real:**
- Aircraft positions (370 recorded OpenSky snapshots).
- Landing detection (real `AircraftStateMachine` logic).
- Schedule resolution path (real `AeroApiClient` → `ScheduleResolver` → `DelayComputer`).
- Weather data (recorded METAR fixtures via `ReplayAviationWeatherClient`).
- All downstream scoring and cascade logic.

The pipeline wiring is proven end-to-end. Delay *values* won't match real BTS statistics until Option B (real BTS integration) is implemented.

---

## Regression

`mvn clean test`: **233 tests, 0 failures, 1 skipped** (gated `LandingSeedExtractorIT`).

---

## How to regenerate

```bash
# 1. Extract the seed (one-time, ~2 min, reads all 370 files)
cd skytrack && ./mvnw test -Dtest=LandingSeedExtractorIT -Dskytrack.tooling=true

# 2. Generate stubs (seconds)
./mvnw test -Dtest=WireMockStubGeneratorIT -Dskytrack.tooling=true
```

Both are deterministic: same seed file + RNG seed `20260616` → identical stub set.

---

## Next step

Real BTS integration (Option B): replace the sampled delay distribution with actual on-time performance from `data/bts/T_ONTIME_REPORTING.csv`. That would make `/analytics/delays` classifications honest against historical data, not just structurally correct.
