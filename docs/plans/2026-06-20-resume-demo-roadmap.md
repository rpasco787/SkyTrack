# Resume-Worthy Demo — Sequenced Roadmap

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement each phase task-by-task. This is a **master roadmap**: Phases 1 and 3 are detailed enough to execute directly; Phase 2 (dashboard) requires its own `superpowers:brainstorming` pass before a detailed plan; Phase 3 **amends** the existing [chunk 8 plan](2026-06-01-chunk8-production-profile-docker-cloudformation-deploy.md) rather than replacing it.

**Goal:** Turn a technically-complete-but-invisible backend (chunks 1–7, proven end-to-end) into a resume-worthy demo: legible on GitHub, visually impressive, and genuinely deployed to AWS at ~$0 — using free synthetic schedule data, never a paid flight API.

**Architecture:** Four phases in dependency order. (1) Make the existing system *legible* (README + diagram + ADRs). (2) Make it *visual* (a dashboard served from Spring's `static/`, so it ships inside the same jar). (3) Make it *deployed* (CloudFormation → EC2 running the app **in replay+synthetic mode against real AWS SQS/DynamoDB/S3**, yielding a live URL that shows rich disruption data). (4) Make the data *honest* (load free BTS on-time data, then optionally redeploy in live mode).

**Tech Stack:** Existing — Spring Boot 4.0.2, Java 25, AWS SDK v2, LocalStack, WireMock, Docker Compose. New — vanilla JS + Leaflet dashboard (served as Spring static resources), Dockerfile (multi-stage, JRE 25 alpine), CloudFormation, ECR, EC2.

---

## Why this order

| Phase | Resume signal | Effort | Blocks |
|---|---|---|---|
| 1. Docs | "This is a real, understandable project" | Low | Nothing — do first |
| 2. Dashboard | "It looks impressive" (the screenshot) | Med | Needs brainstorm; feeds README screenshots |
| 3. AWS deploy | "I can *operate* cloud infra" + live URL | Med | Needs 2 blocker fixes (below) |
| 4. BTS data | "The numbers are honest" | Med-High | Upgrade, not a blocker |

Phase 1 is first because it's cheap and everything else (screenshots, live URL) gets folded back into the README. Phase 2 before Phase 3 so the live deployment already serves the dashboard. Phase 4 last because synthetic data already demonstrates the full pipeline; BTS is an honesty upgrade.

## Cost guardrail (applies to Phase 3)

The expensive dependency — a paid flight schedule API (AeroAPI ~$30–50/mo) — is **never enabled**. Synthetic schedules come from free WireMock stubs you already generate. AWS infra for this workload is Free-Tier/credit-covered; even off Free Tier, deploy → capture URL+screenshot → `teardown.sh` costs **under $1** (EC2 billed per-second). Teardown is what guarantees the bill returns to $0.

## Two blockers that must be fixed before Phase 3 deploys (carried from the smoke test)

1. **`airports.csv` is loaded from the filesystem, not the classpath.** [`AirportLookupService.java:35`](../../skytrack/src/main/java/skytrack/demo/service/AirportLookupService.java#L35) calls `Files.newBufferedReader("data/airports/airports.csv")`. The chunk 8 `.dockerignore` excludes `data/`, so the container would crash on boot with `NoSuchFileException`. **Fixed in Phase 3, Task 3.1.**
2. **Prod ships no schedule data → empty live demo.** Chunk 8's prod profile disables AeroAPI with no BTS, reproducing the smoke test's all-`UNKNOWN` result. **Resolved in Phase 3 by deploying in replay+synthetic mode (Tasks 3.3–3.4): the EC2 host runs WireMock with the generated stubs, the app replays a curated subset of recorded data, and AWS clients point at real SQS/DynamoDB/S3.**

---

# PHASE 1 — Legibility (README + Architecture + ADRs)

**Goal:** Anyone opening the repo understands what it is, how it works, and how to run it within 60 seconds.

**Dependencies:** None.

**Done when:** `README.md` renders with an architecture diagram and a "run it locally" section that works; 5 ADRs exist.

### Task 1.1: Architecture diagram

**Files:**
- Create: `docs/architecture.md` (Mermaid source)
- The same Mermaid block is embedded in the README so it renders on GitHub.

**Step 1:** Write a Mermaid `flowchart LR` capturing the proven pipeline:
`ReplayOpenSkyClient → SqsPositionProducer → SQS positions.fifo → SqsConsumerService → AircraftStateMachine → (DynamoDB state) → LandingEvent → ScheduleResolver (WireMock AeroAPI / BTS) → DelayComputer → DelayEventProcessor → {DisruptionScoreService, CascadeDetector, HistoricalDelayWriter → S3 Parquet} → REST controllers → Dashboard`. Overlay `WeatherCache` enriching `DelayEvent`.

**Step 2:** Verify it renders (paste into the GitHub Mermaid live editor or VS Code Mermaid preview).

**Step 3:** Commit: `docs: architecture diagram (mermaid)`

### Task 1.2: README

**Files:**
- Create: `README.md` (repo root)

**Step 1:** Write these sections:
- **One-liner + 2-sentence pitch** (real-time flight delay & airport-disruption tracker).
- **Screenshot placeholder** (filled in Phase 2) and **live URL placeholder** (filled in Phase 3).
- **Architecture** (embed the Mermaid from 1.1) + the 5-layer description (ingestion / schedule resolution / processing / storage / serving).
- **Tech stack** + **what's real vs. synthetic** (be explicit: positions/weather/landing-detection/scoring are real; schedule ground-truth is synthetic — link [the synthetic demo results](../docs/2026-06-16-synthetic-schedule-demo-results.md)).
- **Run it locally** — the *exact* reproduce block from the smoke test, including the two gotchas: `-Dspring.devtools.restart.enabled=false` and the `data/` symlink (note Phase 3 Task 3.1 removes the symlink need).
- **API reference** — the 6 endpoints (`/airports/{iata}/status`, `/airports/disruptions`, `/flights/{callsign}`, `/cascades/{iata}`, `/analytics/delays`, `/schedule/coverage`) with one sample response each (copy from the synthetic demo doc).
- **Results** — the headline table (ORD 81.7, 54.3% resolved, cascades firing, 233 tests).

**Step 2:** Verify the "run it locally" steps actually work end-to-end on a clean checkout (follow your own instructions; fix any drift).

**Step 3:** Commit: `docs: project README with architecture, API reference, run instructions`

### Task 1.3: ADRs

**Files:**
- Create: `docs/adr/0001-sqs-fifo-over-kinesis.md`, `0002-single-table-dynamodb.md`, `0003-synthetic-and-bts-over-paid-api.md`, `0004-in-memory-sliding-windows.md`, `0005-local-first-localstack-wiremock.md`

**Step 1:** Each ADR uses the standard format (Context / Decision / Consequences). Source the rationale from [the implementation plan](../SkyTrack_Implementation_Plan_v4_API_Only.md) "Key Design Decisions" and the cost trade-offs. ADR 0003 is the honest one: *why we chose synthetic + (eventually) BTS over a paid real-time API — cost vs. accuracy.*

**Step 2:** Link the ADR index from the README.

**Step 3:** Commit: `docs: 5 architecture decision records`

---

# PHASE 2 — Dashboard (the visual "wow")

**Goal:** A single-page dashboard, served from Spring's `static/` (so it ships in the jar and the one deployed URL serves both UI and API), showing a US map with airports colored/sized by disruption score, a top-10 leaderboard, and a flight/airport detail panel.

**Dependencies:** Phase 1 (so screenshots land in the README). The 6 REST endpoints already exist and return everything the UI needs.

> **REQUIRED FIRST:** Use `superpowers:brainstorming` to settle the dashboard design (layout, interactions, vanilla-JS vs. a framework, color scale, refresh strategy) **before** writing a detailed task plan. The notes below are the starting constraints, not the final design.

**Starting constraints:**
- **Serve from `skytrack/src/main/resources/static/`** — `index.html` + `app.js` + `style.css`. No build step, no Node, no separate host. Confirmed empty and Spring-served today.
- **Map:** Leaflet via CDN. Plot the disruption airports as circle markers; radius ∝ active delays, color ∝ score (green→amber→red). Data from `GET /airports/disruptions?limit=50`.
- **Detail panel:** on marker click, call `GET /airports/{iata}/status` → show score, weather (METAR), and cascade alerts.
- **Leaderboard:** top-10 list, auto-refresh every ~15s, trend arrows from `trendDirection`.
- **Flight search:** input → `GET /flights/{callsign}`.
- **CORS:** same-origin (served by Spring), so no CORS config needed.

**Done when:** running locally with the synthetic-stub pipeline (the ORD=81.7 dataset), the map shows colored airports, clicking ORD shows score 81.7 + cascades + weather, and the leaderboard matches `/airports/disruptions`. Capture the screenshot → drop into the README (Task 1.2 placeholder).

**Deliverables (to be expanded into bite-sized tasks after brainstorming):**
1. `index.html` skeleton + Leaflet CDN + base map.
2. `app.js` — fetch `/airports/disruptions`, render markers with score→color scale.
3. Click handler → `/airports/{iata}/status` detail panel.
4. Leaderboard component + auto-refresh.
5. Flight search box → `/flights/{callsign}`.
6. `style.css` polish (dark theme reads well for an "ops console" vibe).
7. Capture screenshot; update README; commit.

> Frontend quality matters for the "wow" — consider the `frontend-design` skill during implementation.

---

# PHASE 3 — AWS Deploy (replay + synthetic, real AWS) → live URL

**Goal:** A public URL on a t3.micro that serves the dashboard + API, backed by **real** SQS/DynamoDB/S3, replaying a curated subset of recorded data through WireMock synthetic stubs — so the live demo shows the same rich disruption data as local. Then teardown to guarantee $0.

**Dependencies:** Phases 1–2 (the jar should already contain the dashboard). Builds on and **amends** the [chunk 8 plan](2026-06-01-chunk8-production-profile-docker-cloudformation-deploy.md).

**How this differs from the chunk 8 plan (which assumed live OpenSky + no schedule data):**

| Chunk 8 plan | This plan (Option A) |
|---|---|
| `opensky.mode=live`, no schedule data → empty demo | `opensky.mode=replay` + WireMock stubs → rich demo |
| Single app container | App container **+ WireMock container** on the EC2 host (docker compose) |
| `.dockerignore` excludes `data/`; `airports.csv` not in image (bug) | `airports.csv` moved to **classpath** (Task 3.1); curated recorded-data subset shipped to the host |
| `aeroapi.enabled=false` | `aeroapi.enabled=true` → `base-url` points at the WireMock sidecar |

Everything else from chunk 8 (CloudFormation skeleton, IAM instance role, ECR, `deploy.sh`/`teardown.sh`, the `ProdProfileBindingTest`) is reused with edits.

### Task 3.1: Fix `airports.csv` loading (classpath) — BLOCKER (TDD)

**Files:**
- Create: `skytrack/src/main/resources/data/airports/airports.csv` (copy of `data/airports/airports.csv`)
- Modify: [`AirportLookupService.java`](../../skytrack/src/main/java/skytrack/demo/service/AirportLookupService.java)
- Test: `skytrack/src/test/java/skytrack/demo/service/AirportLookupServiceClasspathTest.java`

**Step 1 — failing test:** assert the service loads airports when the path is a **classpath** resource (e.g. default `classpath:data/airports/airports.csv`) and resolves a known ICAO (e.g. `KORD`) to ORD — with no working-directory dependency.

**Step 2:** Run it; expect FAIL (current code uses `Files.newBufferedReader` on a filesystem path).

**Step 3 — implement:** load via Spring `ResourceLoader`/`ClassPathResource` so the property accepts `classpath:` (default) **and** a filesystem path (back-compat for the existing repo-root data). Default property → `classpath:data/airports/airports.csv`. Keep the `@Value` override working.

**Step 4:** Run the new test + the full existing suite (`mvn test`); expect PASS, no regressions.

**Step 5:** Commit: `fix: load airports.csv from classpath so it works in a container`

> This also lets you **drop the `data/airports` symlink** from the local run instructions — update README Task 1.2.

### Task 3.2: Rewrite `application-prod.yml` (replay + synthetic + real AWS) + binding test

**Files:**
- Modify: `skytrack/src/main/resources/application-prod.yml`
- Test: `skytrack/src/test/java/skytrack/demo/config/ProdProfileBindingTest.java` (from chunk 8 Task A2, adjusted)

The prod profile: **blank** AWS `endpoint`s (→ real AWS via EC2 IAM role), `opensky.mode=replay`, `weather.mode=replay`, `aeroapi.enabled=true` with `base-url=${AEROAPI_BASE_URL:http://wiremock:8080/aeroapi}` (the sidecar), queue names inherited from base.

**Step 1:** Write the binding test asserting: AWS endpoints blank; `aeroapi.enabled=true`; `aeroapi.base-url` resolves to the WireMock sidecar; `opensky.mode=replay`; queue names inherited. (Adjust chunk 8's assertions, which expected `enabled=false`/`live`.)

**Step 2:** Run → FAIL (current prod yml points at live FlightAware).

**Step 3:** Rewrite `application-prod.yml` accordingly.

**Step 4:** `mvn test -Dtest=ProdProfileBindingTest` → PASS.

**Step 5:** Commit: `config: prod profile = replay + WireMock synthetic + real AWS`

### Task 3.3: Curate a recorded-data subset for the demo

**Files:**
- Create: `data/demo-recorded-opensky/` (≈40–60 files copied from `data/recorded-opensky/`, enough for the rich window the demos already hit)
- Deliver to the instance via S3 (uploaded in `deploy.sh`, downloaded in user-data) — **not** baked into the image (keeps the image small).

**Steps:** select the subset; document why ~50 files suffices (the synthetic demo reached rich data by file ~42); commit the manifest/list (not necessarily the 500MB+ of data — decide in execution whether to commit the subset or generate it).

### Task 3.4: Dockerfile + docker-compose for EC2 (app + WireMock sidecar)

**Files:**
- Create: `Dockerfile` (multi-stage, per chunk 8 Task B2 — JRE 25 alpine; the jar now includes the dashboard static files)
- Create: `.dockerignore` (per chunk 8 Task B1, but **do not** exclude `src/main/resources/data/` — airports.csv must reach the image; the bulky `data/recorded-opensky/` is still excluded)
- Create: `infra/docker-compose.prod.yml` — `app` (from ECR, profile `prod`, replay-dir pointed at the host-mounted demo subset) + `wiremock` (stubs mounted from host)

**Steps:** build image (`docker build`), run the chunk 8 container smoke test (health UP offline), verify the dashboard loads at `/`. Commit.

### Task 3.5: CloudFormation (amend chunk 8 template)

**Files:**
- Create/modify: `infra/skytrack.cfn.yml`

Take chunk 8's template (SQS×2, DynamoDB, S3, ECR, IAM role, SG, EC2) and adjust user-data to: install docker + compose plugin, `aws s3 cp` the demo recorded-data subset + WireMock stubs to the host, then `docker compose -f docker-compose.prod.yml up -d`. Open port 80. Validate: `aws cloudformation validate-template`. Commit.

### Task 3.6: `deploy.sh` + `teardown.sh`

**Files:**
- Create: `infra/deploy.sh` (chunk 8 D1 + upload demo data/stubs to S3 before stack), `infra/teardown.sh` (chunk 8 D2, unchanged — empties S3/ECR then deletes stack).

Commit each.

### Task 3.7: One-time live deploy → capture URL + screenshot → teardown

**Steps (runbook, no commit):** `./infra/deploy.sh`; wait for `/actuator/health` UP; hit `/airports/ORD/status` and the dashboard at `/`; **screenshot the live dashboard**; record the `AppUrl`. Drop both into the README (Tasks 1.2). Then `./infra/teardown.sh` and confirm $0.

### Task 3.8: Deployment runbook

**Files:**
- Create: `docs/DEPLOYMENT.md` (prereqs, deploy/teardown usage, env vars, cost model, the captured URL).
- Update roadmap checkboxes in `docs/SkyTrack_Phase1_Roadmapv1.md` (6.6–6.8).

Commit.

**Phase 3 done when:** a public URL serves the dashboard with rich disruption data on real AWS; teardown returns the account to $0; README shows the URL + screenshot.

---

# PHASE 4 — Honest data (BTS integration)

**Goal:** Replace synthetic schedules with real BTS On-Time Performance data so delay numbers are historically grounded; then optionally redeploy in true live mode (live OpenSky + BTS, single container, still $0 API).

**Dependencies:** None on the others, but sequenced last because synthetic already demonstrates the pipeline.

> **REQUIRED FIRST:** `superpowers:brainstorming` then `superpowers:writing-plans` for a dedicated BTS plan — this is substantial (~the original roadmap's chunk 4: parse ~500MB CSV → in-memory `(carrier, flightNum, dow, origin, dest)` index → wire as a `ScheduleResolver` tier). Outline only here.

**Outline:**
1. Download BTS On-Time CSV → `data/bts/T_ONTIME_REPORTING.csv` (free; see roadmap Chunk 0.2 field list).
2. `BtsScheduleIndex` — parse + group + compute median scheduled arrival / stddev / sample count per key. Load at startup.
3. Add a **BTS tier** to `ScheduleResolver` (HIGH/MEDIUM/LOW confidence per the original roadmap 4.7) behind the existing AeroAPI/WireMock path.
4. Verify `/schedule/coverage` and `/analytics/delays` now show BTS-sourced, honest classifications (TDD against known callsigns from the recorded data).
5. **Optional redeploy:** flip prod to `opensky.mode=live` + BTS, drop the WireMock sidecar → single-container live deployment with real aircraft and honest delays.

**Done when:** delay classifications reflect real BTS statistics (not the sampled distribution), and the [synthetic demo doc's "Next step"](../docs/2026-06-16-synthetic-schedule-demo-results.md) is satisfied.

---

## Suggested execution cadence

- **Phase 1** — one session (docs only, low risk).
- **Phase 2** — brainstorm → plan → execute (1–2 sessions).
- **Phase 3** — execute Tasks 3.1–3.6 locally (one session), then the live deploy runbook 3.7–3.8 (one short session at the machine with AWS creds).
- **Phase 4** — separate brainstorm + plan + execute.

Each phase ends green (`cd skytrack && mvn clean test`) and committed before the next begins.
