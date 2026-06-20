# Synthetic Schedule Ground-Truth via Anchored WireMock Stubs — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Generate per-callsign WireMock AeroAPI stubs whose `scheduled_in` is anchored to each flight's *real* replay arrival time minus a sampled delay, so the local demo shows plausible, tunable delays, populated disruption scores, an ORD/JFK hotspot, and cascade alerts — with **zero `src/main` changes**.

**Architecture:** Two dev-tooling components under `src/test`. ① `LandingSeedExtractor` reuses the real `AircraftStateMachine` + `AirportLookupService` + `ReplayOpenSkyClient` in-memory to detect every landing across the 370-file replay, writing a small `landing-seed.jsonl`. ② `WireMockStubGenerator` reads that seed, samples a deterministic delay per flight, and writes one WireMock mapping per callsign (inline `jsonBody`) into `wiremock/generated/`. At demo time the unchanged pipeline resolves each callsign against its anchored stub, so `delay = sampled value` exactly (replay is deterministic).

**Tech Stack:** Java 25, JUnit 5 + AssertJ, Jackson 3 (`tools.jackson.databind`), WireMock (file-based stubs), the project's existing replay/state-machine classes. No production code, no pom changes.

**Design doc:** [docs/plans/2026-06-16-wiremock-synthetic-schedule-stubs-design.md](2026-06-16-wiremock-synthetic-schedule-stubs-design.md)

**Required sub-skill while implementing each task:** @superpowers:test-driven-development

---

## Key facts the implementer must know

- **Only some callsigns resolve.** [`CallsignParser`](../../skytrack/src/main/java/skytrack/demo/service/CallsignParser.java) accepts a callsign only if it matches `^[A-Z]{3}\d+$` **and** the 3-letter prefix is one of 16 carriers (UAL, AAL, DAL, SWA, JBU, ASA, NKS, FFT, SKW, RPA, ENY, HAL, ACA, WJA, FDX, UPS). Stubs are only useful for these.
- **Only `scheduled_in` matters.** [`AeroApiClient.mapFlightNode`](../../skytrack/src/main/java/skytrack/demo/client/AeroApiClient.java#L120) maps `scheduled_in` → `FlightSchedule.scheduledArrival`; [`ScheduleResolver`](../../skytrack/src/main/java/skytrack/demo/service/ScheduleResolver.java#L47) computes `delay = arrivalTime − scheduledArrival`.
- **WireMock matches on path only.** Per-callsign mappings (default priority 5) override the `unknown-missing` catch-all (priority 10).
- **Working directory for tests is the repo root.** Surefire sets `workingDirectory=${project.basedir}/..` ([pom.xml:147](../../skytrack/pom.xml#L147)). So tests resolve `data/airports/airports.csv`, `./skytrack/data/recorded-opensky/`, and `wiremock/...` relative to the repo root. The replay data lives under `skytrack/data/recorded-opensky/` (525 MB, 370 files), so the extractor must point `replayDir` at `./skytrack/data/recorded-opensky/`.
- **Heavy runs are gated.** The full-replay extraction and the bulk generation are `@Test` methods guarded by `assumeTrue(Boolean.getBoolean("skytrack.tooling"))`, so they are **skipped** in normal `mvn test` and run on demand with `-Dskytrack.tooling=true`. Their pure logic is covered by ungated unit tests.
- **Determinism:** the generator seeds one RNG with a fixed value and processes seed rows in a stable (callsign-sorted) order, so the stub set is fully reproducible.

---

# PART A — Landing seed extraction

## Task 1: `SeedRow` model + pure seed-building logic

Build the testable core first: turn raw `LandingEvent`s into deduped, parseable-filtered seed rows.

**Files:**
- Create: `skytrack/src/test/java/skytrack/demo/tooling/SeedRow.java`
- Create: `skytrack/src/test/java/skytrack/demo/tooling/SeedBuilder.java`
- Test: `skytrack/src/test/java/skytrack/demo/tooling/SeedBuilderTest.java`

### Step 1: Write the failing test

**`SeedBuilderTest.java`:**

```java
package skytrack.demo.tooling;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.service.CallsignParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeedBuilderTest {

    private final SeedBuilder builder = new SeedBuilder(new CallsignParser());

    private static LandingEvent landing(String callsign, long arrivalTime, String iata) {
        return new LandingEvent("icao-" + callsign, callsign, "K" + iata, iata,
                arrivalTime, 41.9, -87.9);
    }

    @Test
    void keepsOnlyParseableCarrierCallsigns() {
        List<SeedRow> rows = builder.build(List.of(
                landing("AAL103", 1000, "JFK"),   // parseable carrier
                landing("N12345", 1000, "JFK"),   // not LLLNNN
                landing("XYZ999", 1000, "JFK")));  // LLLNNN but unknown carrier
        assertThat(rows).extracting(SeedRow::callsign).containsExactly("AAL103");
    }

    @Test
    void dedupsToFirstLandingPerCallsign() {
        List<SeedRow> rows = builder.build(List.of(
                landing("UAL200", 5000, "ORD"),
                landing("UAL200", 9000, "LAX")));   // later landing ignored
        assertThat(rows).singleElement()
                .satisfies(r -> {
                    assertThat(r.arrivalEpoch()).isEqualTo(5000);
                    assertThat(r.airportIata()).isEqualTo("ORD");
                });
    }

    @Test
    void capturesCarrierAndIataFlightNumber() {
        SeedRow r = builder.build(List.of(landing("DAL567", 1234, "JFK"))).get(0);
        assertThat(r.icaoCarrier()).isEqualTo("DAL");
        assertThat(r.identIata()).isEqualTo("DL567");
        assertThat(r.airportIcao()).isEqualTo("KJFK");
    }
}
```

### Step 2: Run it to verify it fails

Run: `cd skytrack && ./mvnw test -Dtest=SeedBuilderTest -q`
Expected: FAIL — `SeedRow`/`SeedBuilder` don't exist (compilation error).

### Step 3: Write the minimal implementation

**`SeedRow.java`:**

```java
package skytrack.demo.tooling;

/** One anchored flight: the real replay arrival used to back a synthetic schedule. */
public record SeedRow(
        String callsign,      // e.g. "AAL103" (AeroAPI ident)
        String identIata,     // e.g. "AA103"
        String icaoCarrier,   // e.g. "AAL" (operator)
        long arrivalEpoch,    // real replay arrival time (epoch seconds)
        String airportIcao,   // e.g. "KJFK"
        String airportIata) { // e.g. "JFK" (may be empty)
}
```

**`SeedBuilder.java`:**

```java
package skytrack.demo.tooling;

import skytrack.demo.model.LandingEvent;
import skytrack.demo.service.CallsignParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns raw landings into deduped, parseable-only seed rows (first landing per callsign). */
public class SeedBuilder {

    private final CallsignParser parser;

    public SeedBuilder(CallsignParser parser) {
        this.parser = parser;
    }

    public List<SeedRow> build(List<LandingEvent> landings) {
        Map<String, SeedRow> firstByCallsign = new LinkedHashMap<>();
        for (LandingEvent e : landings) {
            var parsed = parser.parse(e.callsign());
            if (parsed.isEmpty()) continue;                      // skip non-carrier / malformed
            if (firstByCallsign.containsKey(e.callsign())) continue; // dedup to first landing
            var p = parsed.get();
            firstByCallsign.put(e.callsign(), new SeedRow(
                    e.callsign(),
                    p.iataCarrierCode() + p.flightNumber(),
                    p.icaoCarrierCode(),
                    e.arrivalTime(),
                    e.arrivalAirportIcao(),
                    e.arrivalAirportIata() == null ? "" : e.arrivalAirportIata()));
        }
        return new ArrayList<>(firstByCallsign.values());
    }
}
```

### Step 4: Run the test to verify it passes

Run: `cd skytrack && ./mvnw test -Dtest=SeedBuilderTest -q`
Expected: PASS (3 tests).

### Step 5: Commit

```bash
git add skytrack/src/test/java/skytrack/demo/tooling/SeedRow.java \
        skytrack/src/test/java/skytrack/demo/tooling/SeedBuilder.java \
        skytrack/src/test/java/skytrack/demo/tooling/SeedBuilderTest.java
git commit -m "test(tooling): seed model + deduped parseable seed builder"
```

---

## Task 2: Full-replay extraction runner (gated)

Wire the real components together to produce the authoritative seed from all 370 files. This is a gated tooling test — it runs only with `-Dskytrack.tooling=true`.

**Files:**
- Create: `skytrack/src/test/java/skytrack/demo/service/LandingSeedExtractorIT.java`

> **Package note:** this class is in `skytrack.demo.service` (not `tooling`) so it can call the package-private `AirportLookupService.loadAirports()` without reflection.

### Step 1: Write the runner

**`LandingSeedExtractorIT.java`:**

```java
package skytrack.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper; // Jackson 2 for simple JSONL writing
import org.junit.jupiter.api.Test;
import skytrack.demo.client.ReplayOpenSkyClient;
import skytrack.demo.config.OpenSkyProperties;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.AircraftTrack;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.tooling.SeedBuilder;
import skytrack.demo.tooling.SeedRow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tooling: replays all recorded OpenSky files through the REAL state machine in-memory and
 * writes wiremock/seed/landing-seed.jsonl. Gated — run with:
 *   cd skytrack && ./mvnw test -Dtest=LandingSeedExtractorIT -Dskytrack.tooling=true
 */
class LandingSeedExtractorIT {

    @Test
    void extractLandingSeed() throws Exception {
        assumeTrue(Boolean.getBoolean("skytrack.tooling"),
                "Tooling test — run with -Dskytrack.tooling=true");

        // Real components, no Spring. Paths resolve from the repo root (surefire workingDirectory=..).
        var airportLookup = new AirportLookupService("data/airports/airports.csv");
        airportLookup.loadAirports();
        var stateMachine = new AircraftStateMachine(
                airportLookup, new StateMachineProperties(150, 50, 5, 300));
        var replay = new ReplayOpenSkyClient(
                new OpenSkyProperties("replay", null, null, null,
                        "./skytrack/data/recorded-opensky/", 1),
                new tools.jackson.databind.ObjectMapper());

        Map<String, AircraftTrack> tracks = new HashMap<>();
        List<LandingEvent> landings = new ArrayList<>();

        int files = 0;
        for (List<FlightPosition> batch = replay.fetchPositions();
             !batch.isEmpty();
             batch = replay.fetchPositions()) {
            files++;
            for (FlightPosition pos : batch) {
                AircraftTrack track = tracks.computeIfAbsent(
                        pos.icao24(), AircraftTrack::initial);
                var result = stateMachine.process(track, pos);
                tracks.put(pos.icao24(), result.updatedTrack());
                result.landingEvent().ifPresent(landings::add);
            }
        }

        List<SeedRow> rows = new SeedBuilder(new CallsignParser()).build(landings);

        Path out = Path.of("wiremock/seed/landing-seed.jsonl");
        Files.createDirectories(out.getParent());
        ObjectMapper json = new ObjectMapper();
        try (var w = Files.newBufferedWriter(out)) {
            for (SeedRow r : rows) {
                w.write(json.writeValueAsString(r));
                w.newLine();
            }
        }
        System.out.printf("Extracted %d landings -> %d seed rows from %d files -> %s%n",
                landings.size(), rows.size(), files, out.toAbsolutePath());
    }
}
```

### Step 2: Verify it's skipped by default

Run: `cd skytrack && ./mvnw test -Dtest=LandingSeedExtractorIT -q`
Expected: BUILD SUCCESS with the test **skipped** (assumption failed). Confirms it won't slow normal runs.

### Step 3: Run the extraction for real

Run: `cd skytrack && ./mvnw test -Dtest=LandingSeedExtractorIT -Dskytrack.tooling=true -q`
Expected: PASS; console prints e.g. `Extracted NNNN landings -> NNN seed rows from 370 files`. File `wiremock/seed/landing-seed.jsonl` now exists at the repo root.

### Step 4: Sanity-check the seed

Run:
```bash
wc -l wiremock/seed/landing-seed.jsonl
head -3 wiremock/seed/landing-seed.jsonl
# Confirm an ORD/JFK arrival exists for the hotspot to bite:
grep -E '"airportIata":"(ORD|JFK)"' wiremock/seed/landing-seed.jsonl | head -3
```
Expected: non-zero line count; each line a JSON object with `callsign`, `arrivalEpoch`, `airportIata`; at least some ORD/JFK rows.

> **If zero ORD/JFK rows:** the replay window may not include those arrivals; pick two hotspot airports that *do* appear (use `grep -oE '"airportIata":"[A-Z]{3}"' … | sort | uniq -c | sort -rn | head` to see the busiest) and use them in Task 3.

### Step 5: Commit the seed (small, reusable)

```bash
git add skytrack/src/test/java/skytrack/demo/service/LandingSeedExtractorIT.java \
        wiremock/seed/landing-seed.jsonl
git commit -m "tooling: extract landing seed from replay via real state machine"
```

---

# PART B — Stub generation

## Task 3: Deterministic delay model

**Files:**
- Create: `skytrack/src/test/java/skytrack/demo/tooling/DelayModel.java`
- Test: `skytrack/src/test/java/skytrack/demo/tooling/DelayModelTest.java`

### Step 1: Write the failing test

**`DelayModelTest.java`:**

```java
package skytrack.demo.tooling;

import org.junit.jupiter.api.Test;
import skytrack.demo.tooling.DelayModel.Band;

import static org.assertj.core.api.Assertions.assertThat;

class DelayModelTest {

    @Test
    void normalBandBoundaries() {
        assertThat(DelayModel.chooseBand(0.00, false)).isEqualTo(Band.ON_TIME);
        assertThat(DelayModel.chooseBand(0.54, false)).isEqualTo(Band.ON_TIME);
        assertThat(DelayModel.chooseBand(0.55, false)).isEqualTo(Band.MINOR);
        assertThat(DelayModel.chooseBand(0.79, false)).isEqualTo(Band.MINOR);
        assertThat(DelayModel.chooseBand(0.80, false)).isEqualTo(Band.MAJOR);
        assertThat(DelayModel.chooseBand(0.91, false)).isEqualTo(Band.MAJOR);
        assertThat(DelayModel.chooseBand(0.92, false)).isEqualTo(Band.SEVERE);
    }

    @Test
    void hotspotSkewsHeavier() {
        // At r=0.60 a normal airport is MINOR, a hotspot is MAJOR.
        assertThat(DelayModel.chooseBand(0.60, false)).isEqualTo(Band.MINOR);
        assertThat(DelayModel.chooseBand(0.60, true)).isEqualTo(Band.MAJOR);
    }

    @Test
    void delayStaysWithinBandRange() {
        var rng = new java.util.SplittableRandom(42);
        for (int i = 0; i < 1000; i++) {
            long s = DelayModel.sampleDelaySeconds(Band.MAJOR, rng);
            assertThat(s).isBetween(45 * 60L, 119 * 60L);
        }
    }

    @Test
    void onTimeCanBeSlightlyNegative() {
        var rng = new java.util.SplittableRandom(1);
        boolean sawNegative = false;
        for (int i = 0; i < 200; i++) {
            if (DelayModel.sampleDelaySeconds(Band.ON_TIME, rng) < 0) sawNegative = true;
        }
        assertThat(sawNegative).isTrue();
    }
}
```

### Step 2: Run it to verify it fails

Run: `cd skytrack && ./mvnw test -Dtest=DelayModelTest -q`
Expected: FAIL — `DelayModel` doesn't exist.

### Step 3: Write the implementation

**`DelayModel.java`:**

```java
package skytrack.demo.tooling;

import java.util.random.RandomGenerator;

/** Deterministic synthetic delay sampling. Bands are cumulative over a uniform r in [0,1). */
public final class DelayModel {

    public enum Band { ON_TIME, MINOR, MAJOR, SEVERE }

    private DelayModel() {}

    /** Normal: 55/25/12/8. Hotspot: 25/25/30/20 (skewed toward Major/Severe). */
    public static Band chooseBand(double r, boolean hotspot) {
        if (hotspot) {
            if (r < 0.25) return Band.ON_TIME;
            if (r < 0.50) return Band.MINOR;
            if (r < 0.80) return Band.MAJOR;
            return Band.SEVERE;
        }
        if (r < 0.55) return Band.ON_TIME;
        if (r < 0.80) return Band.MINOR;
        if (r < 0.92) return Band.MAJOR;
        return Band.SEVERE;
    }

    /** Uniform delay (seconds) within the band. ON_TIME spans -5..+14 min. */
    public static long sampleDelaySeconds(Band band, RandomGenerator rng) {
        int lo, hi; // minutes, inclusive
        switch (band) {
            case ON_TIME -> { lo = -5;  hi = 14;  }
            case MINOR   -> { lo = 15;  hi = 44;  }
            case MAJOR   -> { lo = 45;  hi = 119; }
            case SEVERE  -> { lo = 120; hi = 240; }
            default -> throw new IllegalStateException();
        }
        int minutes = lo + rng.nextInt(hi - lo + 1);
        return minutes * 60L;
    }
}
```

### Step 4: Run the test to verify it passes

Run: `cd skytrack && ./mvnw test -Dtest=DelayModelTest -q`
Expected: PASS (4 tests).

### Step 5: Commit

```bash
git add skytrack/src/test/java/skytrack/demo/tooling/DelayModel.java \
        skytrack/src/test/java/skytrack/demo/tooling/DelayModelTest.java
git commit -m "test(tooling): deterministic delay band model with hotspot skew"
```

---

## Task 4: Flights-body JSON builder + round-trip through the real parser

The generated JSON must parse cleanly through the production `AeroApiClient`. We prove it by feeding our body into `AeroApiClient.parseFlightFromJson` and asserting the resulting `scheduledArrival`.

**Files:**
- Create: `skytrack/src/test/java/skytrack/demo/tooling/StubJson.java`
- Test: `skytrack/src/test/java/skytrack/demo/client/GeneratedStubParsesTest.java`

> **Package note:** the round-trip test is in `skytrack.demo.client` so it can call the package-private static `AeroApiClient.parseFlightFromJson`.

### Step 1: Write the failing test

**`GeneratedStubParsesTest.java`:**

```java
package skytrack.demo.client;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.FlightSchedule;
import skytrack.demo.tooling.SeedRow;
import skytrack.demo.tooling.StubJson;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedStubParsesTest {

    @Test
    void generatedBodyRoundTripsThroughAeroApiParser() throws Exception {
        // arrival at epoch 1_773_078_820, 30-minute delay -> scheduled_in 1800s earlier
        SeedRow row = new SeedRow("AAL103", "AA103", "AAL",
                1_773_078_820L, "KJFK", "JFK");
        long scheduledInEpoch = row.arrivalEpoch() - 1800;

        String body = StubJson.flightsBody(new ObjectMapper(), row, scheduledInEpoch);
        FlightSchedule fs = AeroApiClient.parseFlightFromJson(new ObjectMapper(), body);

        assertThat(fs.callsign()).isEqualTo("AAL103");
        assertThat(fs.destination()).isEqualTo("JFK");
        assertThat(fs.scheduledArrival()).isEqualTo(Instant.ofEpochSecond(scheduledInEpoch));
    }
}
```

### Step 2: Run it to verify it fails

Run: `cd skytrack && ./mvnw test -Dtest=GeneratedStubParsesTest -q`
Expected: FAIL — `StubJson` doesn't exist.

### Step 3: Write the implementation

**`StubJson.java`:**

```java
package skytrack.demo.tooling;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/** Builds the AeroAPI response body ({"flights":[{...}]}) and the full WireMock mapping. */
public final class StubJson {

    private StubJson() {}

    /** The response body the AeroAPI client parses. Only scheduled_in is functionally required. */
    public static String flightsBody(ObjectMapper mapper, SeedRow row, long scheduledInEpoch) {
        ObjectNode flight = mapper.createObjectNode();
        flight.put("ident", row.callsign());
        flight.put("ident_iata", row.identIata());
        flight.put("operator", row.icaoCarrier());

        ObjectNode origin = flight.putObject("origin");
        origin.putNull("code");
        origin.putNull("code_iata");

        ObjectNode dest = flight.putObject("destination");
        dest.put("code", row.airportIcao());
        dest.put("code_iata", row.airportIata().isEmpty() ? null : row.airportIata());

        flight.putNull("scheduled_out");
        flight.put("scheduled_in", Instant.ofEpochSecond(scheduledInEpoch).toString());
        flight.putNull("actual_out");
        flight.putNull("actual_in");
        flight.putNull("gate_origin");
        flight.putNull("gate_destination");
        flight.putNull("aircraft_type");

        ObjectNode root = mapper.createObjectNode();
        root.putArray("flights").add(flight);
        return mapper.writeValueAsString(root);
    }

    /** Full WireMock mapping with the body inlined as jsonBody (one file per callsign). */
    public static String mapping(ObjectMapper mapper, SeedRow row, long scheduledInEpoch) {
        ObjectNode root = mapper.createObjectNode();
        root.put("priority", 5);

        ObjectNode request = root.putObject("request");
        request.put("method", "GET");
        request.put("urlPathPattern", "/aeroapi/flights/" + row.callsign() + ".*");

        ObjectNode response = root.putObject("response");
        response.put("status", 200);
        response.putObject("headers").put("Content-Type", "application/json");
        // Re-parse the body string into a node so it embeds as a JSON object, not a string.
        response.set("jsonBody", mapper.readTree(flightsBody(mapper, row, scheduledInEpoch)));

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }
}
```

### Step 4: Run the test to verify it passes

Run: `cd skytrack && ./mvnw test -Dtest=GeneratedStubParsesTest -q`
Expected: PASS.

### Step 5: Commit

```bash
git add skytrack/src/test/java/skytrack/demo/tooling/StubJson.java \
        skytrack/src/test/java/skytrack/demo/client/GeneratedStubParsesTest.java
git commit -m "test(tooling): AeroAPI stub body builder verified against real parser"
```

---

## Task 5: Bulk stub generator (gated) over the seed

Tie it together: read the seed, assign deterministic delays (hotspot-aware), and write the mappings.

**Files:**
- Create: `skytrack/src/test/java/skytrack/demo/tooling/WireMockStubGeneratorIT.java`

### Step 1: Write the generator runner

**`WireMockStubGeneratorIT.java`:**

```java
package skytrack.demo.tooling;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tooling: reads wiremock/seed/landing-seed.jsonl and writes wiremock/generated/gen-<CALLSIGN>.json.
 * Gated — run with:
 *   cd skytrack && ./mvnw test -Dtest=WireMockStubGeneratorIT -Dskytrack.tooling=true
 */
class WireMockStubGeneratorIT {

    private static final long RNG_SEED = 20260616L;
    private static final Set<String> HOTSPOTS = Set.of("ORD", "JFK");
    // Callsigns with hand-written stubs / fixtures — do not overwrite.
    private static final Set<String> RESERVED = Set.of("AAL100", "DAL567", "UAL1234");

    @Test
    void generateStubs() throws Exception {
        assumeTrue(Boolean.getBoolean("skytrack.tooling"),
                "Tooling test — run with -Dskytrack.tooling=true");

        ObjectMapper mapper = new ObjectMapper();
        List<SeedRow> rows = readSeed(mapper, Path.of("wiremock/seed/landing-seed.jsonl"));
        // Stable order -> reproducible RNG assignment.
        rows.sort((a, b) -> a.callsign().compareTo(b.callsign()));

        Path outDir = Path.of("wiremock/generated");
        if (Files.isDirectory(outDir)) {                 // clean prior run
            try (Stream<Path> old = Files.list(outDir)) {
                old.filter(p -> p.getFileName().toString().startsWith("gen-"))
                   .forEach(WireMockStubGeneratorIT::deleteQuietly);
            }
        }
        Files.createDirectories(outDir);

        var rng = new SplittableRandom(RNG_SEED);
        int written = 0, skipped = 0;
        for (SeedRow row : rows) {
            if (RESERVED.contains(row.callsign())) { skipped++; continue; }
            boolean hotspot = HOTSPOTS.contains(row.airportIata());
            var band = DelayModel.chooseBand(rng.nextDouble(), hotspot);
            long delay = DelayModel.sampleDelaySeconds(band, rng);
            long scheduledIn = row.arrivalEpoch() - delay;

            Files.writeString(outDir.resolve("gen-" + row.callsign() + ".json"),
                    StubJson.mapping(mapper, row, scheduledIn));
            written++;
        }
        System.out.printf("Wrote %d stubs (skipped %d reserved) to %s%n",
                written, skipped, outDir.toAbsolutePath());
    }

    private static List<SeedRow> readSeed(ObjectMapper mapper, Path seed) throws IOException {
        try (Stream<String> lines = Files.lines(seed)) {
            return lines.filter(l -> !l.isBlank())
                    .map(l -> readRow(mapper, l))
                    .toList();
        }
    }

    private static SeedRow readRow(ObjectMapper mapper, String line) {
        try { return mapper.readValue(line, SeedRow.class); }
        catch (Exception e) { throw new RuntimeException("Bad seed line: " + line, e); }
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }
}
```

### Step 2: Generate the stubs

Run: `cd skytrack && ./mvnw test -Dtest=WireMockStubGeneratorIT -Dskytrack.tooling=true -q`
Expected: PASS; prints `Wrote N stubs (skipped M reserved) ...`.

### Step 3: Verify the output

Run:
```bash
ls wiremock/generated/ | head
ls wiremock/generated/ | wc -l
# spot-check a hotspot flight has a meaningful scheduled_in:
cat wiremock/generated/gen-$(grep -m1 -oE 'gen-[A-Z]{3}[0-9]+' <(ls wiremock/generated) | sed 's/gen-//').json 2>/dev/null | head -40
```
Expected: many `gen-*.json` files; each is a valid WireMock mapping with an inline `jsonBody.flights[0].scheduled_in`.

### Step 4: Commit the generator (not the bulk output)

```bash
git add skytrack/src/test/java/skytrack/demo/tooling/WireMockStubGeneratorIT.java
git commit -m "tooling: generate anchored WireMock schedule stubs from seed"
```

---

# PART C — Wire-up & demo verification

## Task 6: Mount the generated stubs and ignore the bulk output

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.gitignore`

### Step 1: Add the generated dir to the WireMock mount

In `docker-compose.yml`, under the `wiremock` service `volumes:`, add the generated mappings mount so WireMock serves them alongside the hand-written ones:

```yaml
  wiremock:
    image: wiremock/wiremock:latest
    ports:
      - "9090:8080"
    volumes:
      - "./wiremock/mappings:/home/wiremock/mappings"
      - "./wiremock/__files:/home/wiremock/__files"
      - "./wiremock/generated:/home/wiremock/mappings/generated"
    command: ["--verbose"]
```

> WireMock loads mappings recursively, so `mappings/generated/*.json` are picked up. Inline `jsonBody` means no `__files` entries are needed.

### Step 2: Ignore the bulk generated stubs (keep the seed tracked)

Append to the repo-root `.gitignore`:

```gitignore
# Generated WireMock schedule stubs (regenerate via WireMockStubGeneratorIT)
wiremock/generated/
```

### Step 3: Commit

```bash
git add docker-compose.yml .gitignore
git commit -m "build: mount generated WireMock stubs; ignore the bulk output"
```

---

## Task 7: End-to-end demo verification

Confirm the synthetic ground-truth produces non-`UNKNOWN` delays, populated disruptions with an ORD/JFK hotspot, and cascades. This mirrors the [2026-06-06 smoke test](../2026-06-06-chunk7-pipeline-smoke-test-results.md) procedure.

**Files:** none (manual verification).

### Step 1: Bring up the stack and run the app

```bash
docker compose up -d
# bridge data roots (Finding 1 from the smoke-test doc), then run with devtools restart off (Finding 2):
ln -sfn ../../data/airports         skytrack/data/airports
ln -sfn ../../data/recorded-weather skytrack/data/recorded-weather
cd skytrack && SPRING_PROFILES_ACTIVE=local \
  ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.devtools.restart.enabled=false"
```

### Step 2: After a few minutes, query the endpoints

```bash
B=http://localhost:8080
curl -s "$B/schedule/coverage"                                   # verified > 0 now
curl -s "$B/airports/disruptions?limit=10"                       # non-empty, ORD/JFK high
curl -s "$B/airports/JFK/status"                                 # score > 0 + IFR weather
curl -s "$B/analytics/delays?airport=JFK&date=2026-03-09"        # classification != UNKNOWN
```

Expected:
- `/schedule/coverage` shows non-zero `verified` (resolution method `AEROAPI`).
- `/airports/disruptions` is **non-empty**, with ORD and/or JFK ranked near the top.
- `/analytics/delays` rows show `classification` of `ON_TIME`/`MINOR`/`MAJOR`/`SEVERE` (not `UNKNOWN`) and non-null `delaySeconds`.
- App logs show `Cascade risk:` lines (delays ≥30 min at the hotspot).

> **Date note:** the replay arrivals are ~2026-03-09, so query `/analytics/delays` with `date=2026-03-09` (the partition is derived from the event time, not today).

> **If disruptions are still empty:** confirm WireMock served a generated stub — `curl -s "http://localhost:9090/aeroapi/flights/<aCallsignFromSeed>?start=2026-03-09&end=2026-03-09"` should return your synthetic flight, not `{"flights":[]}`. If it returns empty, re-check the Task 6 mount path.

### Step 3: Tear down

```bash
# in the app shell: Ctrl-C
rm -f skytrack/data/airports skytrack/data/recorded-weather
cd /Users/ryanpascual/SkyTrack && docker compose down
```

---

## Task 8: Document the result and full regression

**Files:**
- Create: `docs/2026-06-16-synthetic-schedule-demo-results.md`
- Modify: `docs/2026-06-06-chunk7-pipeline-smoke-test-results.md` (add a short "Resolved via synthetic stubs" note linking the new doc)

### Step 1: Capture the before/after

Write a short results doc: the endpoint outputs now vs. the all-`UNKNOWN` baseline, the observed band distribution, the hotspot ranking, and how to regenerate (`-Dskytrack.tooling=true`). Note it's synthetic ground-truth and that real BTS (Option B) remains the honest follow-up.

### Step 2: Full regression (ungated tests only)

Run: `cd skytrack && ./mvnw clean test`
Expected: BUILD SUCCESS. The gated tooling tests (`*IT` with `assumeTrue`) are **skipped**; the unit tests (`SeedBuilderTest`, `DelayModelTest`, `GeneratedStubParsesTest`) pass.

### Step 3: Commit

```bash
git add docs/2026-06-16-synthetic-schedule-demo-results.md \
        docs/2026-06-06-chunk7-pipeline-smoke-test-results.md
git commit -m "docs: synthetic schedule demo results"
```

---

## Deliverables

| Deliverable | Tasks | Priority |
|---|---|---|
| Seed model + deduped/parseable seed builder (TDD) | 1 | Critical |
| Full-replay extraction runner (gated, reuses real state machine) | 2 | Critical |
| Deterministic delay band model + hotspot (TDD) | 3 | Critical |
| Stub body/mapping builder verified against real parser (TDD) | 4 | Critical |
| Bulk stub generator over the seed (gated) | 5 | Critical |
| docker-compose mount + gitignore | 6 | Critical |
| End-to-end demo verification | 7 | High |
| Results doc + regression | 8 | Medium |

> ✅ **Checkpoint:** `./mvnw clean test` is green (gated tooling skipped). After running the two gated tools and `docker compose up`, the pipeline resolves real replay callsigns against anchored stubs → `/airports/disruptions` is non-empty with ORD/JFK ranked high, `/analytics/delays` shows real classifications, and cascade alerts fire — all synthetic, local-only, with `src/main` untouched.

## Out of scope

- Real BTS integration (Option B) — the honest ground-truth path, deferred.
- Finishing `DailySchedulePrefetchService` (still discards results).
- Any production schedule source / prod-profile change.
- Per-flight realism beyond the sampled distribution (e.g. modeling actual carrier on-time rates).
```
