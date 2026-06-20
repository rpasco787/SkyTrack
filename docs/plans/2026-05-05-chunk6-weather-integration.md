# Chunk 6: Weather Integration & Delay Enrichment

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Integrate aviationweather.gov METAR data into the pipeline and enrich every `DelayEvent` with the concurrent weather observation (visibility, ceiling, wind, flight category VFR/MVFR/IFR/LIFR) for the arrival airport.

**Architecture:** A new `WeatherSource` interface mirrors the `FlightDataSource` pattern with two implementations: `LiveAviationWeatherClient` (hits `aviationweather.gov/api/data/metar`) and `ReplayAviationWeatherClient` (reads recorded METARs from disk for local dev). A `WeatherPollingService` runs every 15 minutes and refreshes a `WeatherCache` (in-memory `ConcurrentHashMap<String, WeatherObservation>` keyed by airport ICAO with 30-minute TTL). `DelayEventProcessor` queries the cache before delegating to `DelayComputer`, passing an `Optional<WeatherObservation>` so the resulting `DelayEvent` carries `flightCategory`, `visibilityStatuteMiles`, `ceilingFeet`, and `windSpeedKnots` fields. A failed weather lookup is non-fatal — events are emitted with null weather fields and logged.

**Tech Stack:** Spring Boot 4.0.2, Java 25, `RestClient` for HTTP, `tools.jackson` (Spring Boot 4 relocation) for JSON, Spring `@Scheduled`, ConcurrentHashMap, Lombok, JUnit 5 + AssertJ + Mockito + WireMock

**Depends on:** Chunks 1–5 (OpenSky clients, SQS pipeline, AeroAPI + WireMock, DynamoDB + state machine, delay detection + disruption scoring)

---

## Task 1: FlightCategory Enum & WeatherObservation Model

Create the FAA flight-category enum and the canonical weather record that flows through the pipeline.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/model/FlightCategory.java`
- Create: `skytrack/src/main/java/skytrack/demo/model/WeatherObservation.java`
- Test: `skytrack/src/test/java/skytrack/demo/model/FlightCategoryTest.java`
- Test: `skytrack/src/test/java/skytrack/demo/model/WeatherObservationTest.java`

### Step 1: Write the failing tests

**`FlightCategoryTest.java`:**

```java
package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlightCategoryTest {

    @Test
    void shouldClassifyVfrWhenVisHighAndCeilingHigh() {
        assertThat(FlightCategory.from(10.0, 5000)).isEqualTo(FlightCategory.VFR);
        assertThat(FlightCategory.from(5.0, 3000)).isEqualTo(FlightCategory.VFR);
    }

    @Test
    void shouldClassifyMvfrWhenVisOrCeilingMarginal() {
        assertThat(FlightCategory.from(4.0, 5000)).isEqualTo(FlightCategory.MVFR);
        assertThat(FlightCategory.from(10.0, 2000)).isEqualTo(FlightCategory.MVFR);
        assertThat(FlightCategory.from(3.0, 1500)).isEqualTo(FlightCategory.MVFR);
    }

    @Test
    void shouldClassifyIfrWhenVisOrCeilingLow() {
        assertThat(FlightCategory.from(2.0, 5000)).isEqualTo(FlightCategory.IFR);
        assertThat(FlightCategory.from(10.0, 800)).isEqualTo(FlightCategory.IFR);
        assertThat(FlightCategory.from(1.0, 600)).isEqualTo(FlightCategory.IFR);
    }

    @Test
    void shouldClassifyLifrWhenVisOrCeilingVeryLow() {
        assertThat(FlightCategory.from(0.5, 5000)).isEqualTo(FlightCategory.LIFR);
        assertThat(FlightCategory.from(10.0, 300)).isEqualTo(FlightCategory.LIFR);
    }

    @Test
    void shouldReturnUnknownForNullInputs() {
        assertThat(FlightCategory.from(null, 5000)).isEqualTo(FlightCategory.UNKNOWN);
        assertThat(FlightCategory.from(5.0, null)).isEqualTo(FlightCategory.UNKNOWN);
        assertThat(FlightCategory.from(null, null)).isEqualTo(FlightCategory.UNKNOWN);
    }

    @Test
    void shouldUseWorstOfVisOrCeiling() {
        // Vis is VFR but ceiling is IFR -> downgrade to IFR
        assertThat(FlightCategory.from(10.0, 800)).isEqualTo(FlightCategory.IFR);
        // Vis is LIFR but ceiling is VFR -> downgrade to LIFR
        assertThat(FlightCategory.from(0.5, 5000)).isEqualTo(FlightCategory.LIFR);
    }
}
```

**`WeatherObservationTest.java`:**

```java
package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherObservationTest {

    @Test
    void shouldConstructWeatherObservation() {
        Instant observed = Instant.parse("2026-05-05T14:30:00Z");
        var obs = new WeatherObservation(
                "KORD", "ORD", observed,
                4.0, 1500, 18, 25, FlightCategory.MVFR,
                "METAR KORD 051430Z 27018G25KT 4SM BR OVC015");
        assertThat(obs.airportIcao()).isEqualTo("KORD");
        assertThat(obs.flightCategory()).isEqualTo(FlightCategory.MVFR);
        assertThat(obs.windGustKnots()).isEqualTo(25);
    }

    @Test
    void shouldAllowNullOptionalFields() {
        var obs = new WeatherObservation(
                "KORD", "ORD", Instant.now(),
                null, null, null, null, FlightCategory.UNKNOWN, null);
        assertThat(obs.visibilityStatuteMiles()).isNull();
        assertThat(obs.rawMetar()).isNull();
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd skytrack && mvn test -Dtest='FlightCategoryTest,WeatherObservationTest' -q`
Expected: FAIL with "cannot find symbol" or "FlightCategory does not exist".

**Step 3: Write the implementations**

**`FlightCategory.java`:**

```java
package skytrack.demo.model;

public enum FlightCategory {
    VFR,
    MVFR,
    IFR,
    LIFR,
    UNKNOWN;

    /**
     * FAA flight category from visibility (statute miles) and ceiling (feet AGL).
     * Returns the worse of the two thresholds.
     */
    public static FlightCategory from(Double visibilityStatuteMiles, Integer ceilingFeet) {
        if (visibilityStatuteMiles == null || ceilingFeet == null) {
            return UNKNOWN;
        }
        FlightCategory byVis = byVisibility(visibilityStatuteMiles);
        FlightCategory byCeiling = byCeiling(ceilingFeet);
        return byVis.ordinal() > byCeiling.ordinal() ? byVis : byCeiling;
    }

    private static FlightCategory byVisibility(double vis) {
        if (vis < 1.0) return LIFR;
        if (vis < 3.0) return IFR;
        if (vis < 5.0) return MVFR;
        return VFR;
    }

    private static FlightCategory byCeiling(int ceiling) {
        if (ceiling < 500) return LIFR;
        if (ceiling < 1000) return IFR;
        if (ceiling < 3000) return MVFR;
        return VFR;
    }
}
```

**`WeatherObservation.java`:**

```java
package skytrack.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WeatherObservation(
        String airportIcao,
        String airportIata,
        Instant observedAt,
        Double visibilityStatuteMiles,
        Integer ceilingFeet,
        Integer windSpeedKnots,
        Integer windGustKnots,
        FlightCategory flightCategory,
        String rawMetar
) {}
```

**Step 4: Run tests to verify they pass**

Run: `cd skytrack && mvn test -Dtest='FlightCategoryTest,WeatherObservationTest' -q`
Expected: PASS, both test classes green.

**Step 5: Commit**

```bash
git add skytrack/src/main/java/skytrack/demo/model/FlightCategory.java \
        skytrack/src/main/java/skytrack/demo/model/WeatherObservation.java \
        skytrack/src/test/java/skytrack/demo/model/FlightCategoryTest.java \
        skytrack/src/test/java/skytrack/demo/model/WeatherObservationTest.java
git commit -m "feat: add FlightCategory enum and WeatherObservation model"
```

---

## Task 2: WeatherProperties Configuration

Spring `@ConfigurationProperties` for the weather subsystem — mode (live/replay), API URL, replay directory, polling interval, cache TTL, target airports.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/config/WeatherProperties.java`
- Test: `skytrack/src/test/java/skytrack/demo/config/WeatherPropertiesTest.java`

### Step 1: Write the failing test

**`WeatherPropertiesTest.java`:**

```java
package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherPropertiesTest {

    @Test
    void shouldApplyDefaults() {
        var props = new WeatherProperties(null, null, null, null, 0, 0, null);
        assertThat(props.mode()).isEqualTo("replay");
        assertThat(props.apiUrl()).isEqualTo("https://aviationweather.gov/api/data/metar");
        assertThat(props.replayDir()).isEqualTo("./data/recorded-weather/");
        assertThat(props.requestTimeoutMs()).isEqualTo(5000);
        assertThat(props.pollIntervalMinutes()).isEqualTo(15);
        assertThat(props.cacheTtlMinutes()).isEqualTo(30);
        assertThat(props.targetAirports()).isEmpty();
    }

    @Test
    void shouldRetainProvidedValues() {
        var props = new WeatherProperties(
                "live",
                "https://example.com/metar",
                "./data/test-weather/",
                10000,
                5,
                60,
                List.of("KORD", "KATL"));
        assertThat(props.mode()).isEqualTo("live");
        assertThat(props.targetAirports()).containsExactly("KORD", "KATL");
        assertThat(props.pollIntervalMinutes()).isEqualTo(5);
    }
}
```

**Step 2: Verify failure**

Run: `cd skytrack && mvn test -Dtest=WeatherPropertiesTest -q`
Expected: FAIL — `WeatherProperties` does not exist.

**Step 3: Implement**

**`WeatherProperties.java`:**

```java
package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "weather")
public record WeatherProperties(
        String mode,
        String apiUrl,
        String replayDir,
        Integer requestTimeoutMs,
        int pollIntervalMinutes,
        int cacheTtlMinutes,
        List<String> targetAirports
) {
    public WeatherProperties {
        if (mode == null) mode = "replay";
        if (apiUrl == null) apiUrl = "https://aviationweather.gov/api/data/metar";
        if (replayDir == null) replayDir = "./data/recorded-weather/";
        if (requestTimeoutMs == null || requestTimeoutMs <= 0) requestTimeoutMs = 5000;
        if (pollIntervalMinutes <= 0) pollIntervalMinutes = 15;
        if (cacheTtlMinutes <= 0) cacheTtlMinutes = 30;
        if (targetAirports == null) targetAirports = List.of();
    }
}
```

**Register the properties.** Add `WeatherProperties.class` to the existing `@EnableConfigurationProperties` annotation. Find it first:

Run: `grep -r "EnableConfigurationProperties" skytrack/src/main/java/`

Expected: a single application config class (likely `MyApplication.java` or a `*Properties` registrar). Add `WeatherProperties.class` to that list. If no such annotation exists yet, add it to `MyApplication.java`:

```java
@EnableConfigurationProperties({
        OpenSkyProperties.class,
        SqsProperties.class,
        AeroApiProperties.class,
        DynamoDbProperties.class,
        StateMachineProperties.class,
        DisruptionScoreProperties.class,
        WeatherProperties.class
})
```

**Step 4: Verify pass**

Run: `cd skytrack && mvn test -Dtest=WeatherPropertiesTest -q`
Expected: PASS.

**Step 5: Commit**

```bash
git add skytrack/src/main/java/skytrack/demo/config/WeatherProperties.java \
        skytrack/src/test/java/skytrack/demo/config/WeatherPropertiesTest.java \
        skytrack/src/main/java/skytrack/demo/MyApplication.java
git commit -m "feat: add WeatherProperties config"
```

---

## Task 3: WeatherSource Interface & LiveAviationWeatherClient

Create the source interface (mirrors `FlightDataSource`) and the live client that hits `aviationweather.gov/api/data/metar`. The METAR JSON API returns one record per `ids` parameter.

**API contract:** `GET https://aviationweather.gov/api/data/metar?ids=KORD,KATL&format=json` returns an array:

```json
[
  {
    "icaoId": "KORD",
    "obsTime": 1714915200,
    "visib": "10+",
    "wdir": 270,
    "wspd": 18,
    "wgst": 25,
    "rawOb": "METAR KORD 051430Z 27018G25KT 10SM ...",
    "clouds": [{"cover": "OVC", "base": 1500}]
  }
]
```

Ceiling is the lowest cloud layer with cover BKN/OVC; visibility may be a string with `"+"` suffix.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/client/WeatherSource.java`
- Create: `skytrack/src/main/java/skytrack/demo/client/LiveAviationWeatherClient.java`
- Test: `skytrack/src/test/java/skytrack/demo/client/LiveAviationWeatherClientTest.java`

### Step 1: Write the failing test

**`LiveAviationWeatherClientTest.java`:**

```java
package skytrack.demo.client;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.WeatherObservation;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class LiveAviationWeatherClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void shouldFetchMetarForSingleAirport() {
        wm.stubFor(get(urlPathEqualTo("/metar"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "icaoId": "KORD",
                                    "obsTime": 1714915200,
                                    "visib": "10+",
                                    "wdir": 270,
                                    "wspd": 18,
                                    "wgst": 25,
                                    "rawOb": "METAR KORD 051430Z 27018G25KT 10SM CLR",
                                    "clouds": [{"cover": "CLR"}]
                                  }
                                ]
                                """)));

        var props = new WeatherProperties("live", wm.baseUrl() + "/metar",
                null, 5000, 15, 30, List.of("KORD"));
        var client = new LiveAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD"));

        assertThat(result).hasSize(1);
        WeatherObservation obs = result.get(0);
        assertThat(obs.airportIcao()).isEqualTo("KORD");
        assertThat(obs.visibilityStatuteMiles()).isEqualTo(10.0);
        assertThat(obs.windSpeedKnots()).isEqualTo(18);
        assertThat(obs.windGustKnots()).isEqualTo(25);
        assertThat(obs.flightCategory()).isEqualTo(FlightCategory.VFR);
    }

    @Test
    void shouldDeriveIfrFromLowCeiling() {
        wm.stubFor(get(urlPathEqualTo("/metar"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "icaoId": "KORD",
                                    "obsTime": 1714915200,
                                    "visib": "2",
                                    "wdir": 90,
                                    "wspd": 12,
                                    "rawOb": "METAR KORD 051430Z 09012KT 2SM BR OVC008",
                                    "clouds": [{"cover": "OVC", "base": 800}]
                                  }
                                ]
                                """)));

        var props = new WeatherProperties("live", wm.baseUrl() + "/metar",
                null, 5000, 15, 30, List.of("KORD"));
        var client = new LiveAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).flightCategory()).isEqualTo(FlightCategory.IFR);
        assertThat(result.get(0).ceilingFeet()).isEqualTo(800);
    }

    @Test
    void shouldReturnEmptyOnHttpError() {
        wm.stubFor(get(urlPathEqualTo("/metar"))
                .willReturn(aResponse().withStatus(500)));

        var props = new WeatherProperties("live", wm.baseUrl() + "/metar",
                null, 5000, 15, 30, List.of("KORD"));
        var client = new LiveAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD"));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldIgnoreFewAndSctClouds() {
        wm.stubFor(get(urlPathEqualTo("/metar"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "icaoId": "KORD",
                                    "obsTime": 1714915200,
                                    "visib": "10",
                                    "wdir": 0,
                                    "wspd": 5,
                                    "rawOb": "METAR KORD 051430Z 00005KT 10SM FEW015 SCT025",
                                    "clouds": [
                                      {"cover": "FEW", "base": 1500},
                                      {"cover": "SCT", "base": 2500}
                                    ]
                                  }
                                ]
                                """)));

        var props = new WeatherProperties("live", wm.baseUrl() + "/metar",
                null, 5000, 15, 30, List.of("KORD"));
        var client = new LiveAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD"));

        // FEW/SCT do not constitute a ceiling, so ceilingFeet should be null and category VFR
        assertThat(result.get(0).ceilingFeet()).isNull();
        assertThat(result.get(0).flightCategory()).isEqualTo(FlightCategory.VFR);
    }
}
```

**Step 2: Verify failure**

Run: `cd skytrack && mvn test -Dtest=LiveAviationWeatherClientTest -q`
Expected: FAIL — class missing.

**Step 3: Implement**

**`WeatherSource.java`:**

```java
package skytrack.demo.client;

import skytrack.demo.model.WeatherObservation;

import java.util.List;

public interface WeatherSource {
    /**
     * Fetch the most recent METAR observation for each requested airport ICAO.
     * Returns observations in airport order; airports with no data are omitted.
     */
    List<WeatherObservation> fetchObservations(List<String> airportIcaoCodes);
}
```

**`LiveAviationWeatherClient.java`:**

```java
package skytrack.demo.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.WeatherObservation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class LiveAviationWeatherClient implements WeatherSource {

    private static final Logger log = LoggerFactory.getLogger(LiveAviationWeatherClient.class);
    private static final Set<String> CEILING_COVERS = Set.of("BKN", "OVC", "VV");

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final WeatherProperties properties;

    public LiveAviationWeatherClient(WeatherProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.apiUrl())
                .build();
    }

    @Override
    public List<WeatherObservation> fetchObservations(List<String> airportIcaoCodes) {
        if (airportIcaoCodes.isEmpty()) {
            return List.of();
        }
        String ids = String.join(",", airportIcaoCodes);
        try {
            String body = restClient.get()
                    .uri(uri -> uri.queryParam("ids", ids).queryParam("format", "json").build())
                    .retrieve()
                    .body(String.class);
            return parse(body);
        } catch (Exception e) {
            log.error("Failed to fetch METAR for ids={}: {}", ids, e.getMessage());
            return List.of();
        }
    }

    List<WeatherObservation> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.isArray()) return List.of();
            List<WeatherObservation> result = new ArrayList<>();
            for (JsonNode node : root) {
                WeatherObservation obs = mapMetar(node);
                if (obs != null) result.add(obs);
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse METAR response: {}", e.getMessage());
            return List.of();
        }
    }

    private WeatherObservation mapMetar(JsonNode node) {
        String icao = textOrNull(node, "icaoId");
        if (icao == null) return null;
        Instant observedAt = epochSecondsOrNull(node, "obsTime");
        Double visibility = parseVisibility(textOrNull(node, "visib"));
        Integer ceiling = ceilingFromClouds(node.get("clouds"));
        Integer windSpeed = intOrNull(node, "wspd");
        Integer windGust = intOrNull(node, "wgst");
        FlightCategory category = FlightCategory.from(visibility, ceiling);
        String rawOb = textOrNull(node, "rawOb");
        return new WeatherObservation(icao, null, observedAt, visibility,
                ceiling, windSpeed, windGust, category, rawOb);
    }

    static Double parseVisibility(String visib) {
        if (visib == null) return null;
        String trimmed = visib.replace("+", "").trim();
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer ceilingFromClouds(JsonNode clouds) {
        if (clouds == null || !clouds.isArray()) return null;
        Integer lowest = null;
        for (JsonNode layer : clouds) {
            String cover = textOrNull(layer, "cover");
            if (cover == null || !CEILING_COVERS.contains(cover)) continue;
            Integer base = intOrNull(layer, "base");
            if (base == null) continue;
            if (lowest == null || base < lowest) lowest = base;
        }
        return lowest;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asString();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    private static Instant epochSecondsOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return Instant.ofEpochSecond(v.asLong());
    }
}
```

**Step 4: Verify pass**

Run: `cd skytrack && mvn test -Dtest=LiveAviationWeatherClientTest -q`
Expected: PASS, all 4 tests green.

**Step 5: Commit**

```bash
git add skytrack/src/main/java/skytrack/demo/client/WeatherSource.java \
        skytrack/src/main/java/skytrack/demo/client/LiveAviationWeatherClient.java \
        skytrack/src/test/java/skytrack/demo/client/LiveAviationWeatherClientTest.java
git commit -m "feat: add WeatherSource interface and LiveAviationWeatherClient"
```

---

## Task 4: ReplayAviationWeatherClient & Recorded METAR Fixture

For local development, the replay client reads METAR JSON from disk (committed fixture in `data/recorded-weather/`). One snapshot is enough; the cache TTL absorbs the staleness during local replay.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/client/ReplayAviationWeatherClient.java`
- Create: `data/recorded-weather/sample-metar-2026-05-05T1430Z.json` (committed fixture)
- Test: `skytrack/src/test/java/skytrack/demo/client/ReplayAviationWeatherClientTest.java`

### Step 1: Write the failing test

**`ReplayAviationWeatherClientTest.java`:**

```java
package skytrack.demo.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.WeatherObservation;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayAviationWeatherClientTest {

    @Test
    void shouldReadObservationsFromFixture(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("metar.json");
        Files.writeString(file, """
                [
                  {
                    "icaoId": "KORD",
                    "obsTime": 1714915200,
                    "visib": "10+",
                    "wdir": 270,
                    "wspd": 12,
                    "rawOb": "METAR KORD 051430Z 27012KT 10SM CLR",
                    "clouds": [{"cover": "CLR"}]
                  },
                  {
                    "icaoId": "KATL",
                    "obsTime": 1714915200,
                    "visib": "3",
                    "wdir": 90,
                    "wspd": 8,
                    "rawOb": "METAR KATL 051430Z 09008KT 3SM BR OVC012",
                    "clouds": [{"cover": "OVC", "base": 1200}]
                  }
                ]
                """);

        var props = new WeatherProperties("replay", null,
                tempDir.toString(), 5000, 15, 30, List.of("KORD", "KATL"));
        var client = new ReplayAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD", "KATL"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).airportIcao()).isEqualTo("KORD");
        assertThat(result.get(0).flightCategory()).isEqualTo(FlightCategory.VFR);
        assertThat(result.get(1).airportIcao()).isEqualTo("KATL");
        assertThat(result.get(1).flightCategory()).isEqualTo(FlightCategory.MVFR);
    }

    @Test
    void shouldFilterToRequestedAirports(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("metar.json");
        Files.writeString(file, """
                [
                  {"icaoId": "KORD", "obsTime": 1714915200, "visib": "10", "rawOb": "x", "clouds": []},
                  {"icaoId": "KJFK", "obsTime": 1714915200, "visib": "10", "rawOb": "x", "clouds": []},
                  {"icaoId": "KLAX", "obsTime": 1714915200, "visib": "10", "rawOb": "x", "clouds": []}
                ]
                """);

        var props = new WeatherProperties("replay", null,
                tempDir.toString(), 5000, 15, 30, List.of("KORD", "KLAX"));
        var client = new ReplayAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD", "KLAX"));

        assertThat(result).extracting(WeatherObservation::airportIcao)
                .containsExactlyInAnyOrder("KORD", "KLAX");
    }

    @Test
    void shouldReturnEmptyWhenDirectoryMissing() {
        var props = new WeatherProperties("replay", null,
                "/nonexistent/path/", 5000, 15, 30, List.of("KORD"));
        var client = new ReplayAviationWeatherClient(props, new ObjectMapper());

        assertThat(client.fetchObservations(List.of("KORD"))).isEmpty();
    }
}
```

**Step 2: Verify failure**

Run: `cd skytrack && mvn test -Dtest=ReplayAviationWeatherClientTest -q`
Expected: FAIL — class missing.

**Step 3: Implement**

**`ReplayAviationWeatherClient.java`:**

```java
package skytrack.demo.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.WeatherObservation;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class ReplayAviationWeatherClient implements WeatherSource {

    private static final Logger log = LoggerFactory.getLogger(ReplayAviationWeatherClient.class);

    private final WeatherProperties properties;
    private final LiveAviationWeatherClient parser;

    public ReplayAviationWeatherClient(WeatherProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        // Reuse LiveAviationWeatherClient.parse() for METAR-shaped JSON parsing.
        this.parser = new LiveAviationWeatherClient(properties, mapper);
    }

    @Override
    public List<WeatherObservation> fetchObservations(List<String> airportIcaoCodes) {
        Optional<String> body = readLatestFixture();
        if (body.isEmpty()) {
            log.warn("No replay weather fixture found in {}", properties.replayDir());
            return List.of();
        }
        Set<String> requested = Set.copyOf(airportIcaoCodes);
        return parser.parse(body.get()).stream()
                .filter(obs -> requested.isEmpty() || requested.contains(obs.airportIcao()))
                .toList();
    }

    private Optional<String> readLatestFixture() {
        Path dir = Path.of(properties.replayDir());
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            log.error("Failed to read fixture {}: {}", p, e.getMessage());
                            return null;
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to list weather replay directory {}: {}",
                    properties.replayDir(), e.getMessage());
            return Optional.empty();
        }
    }
}
```

**Create the committed fixture** at `data/recorded-weather/sample-metar-2026-05-05T1430Z.json`:

```json
[
  {
    "icaoId": "KORD",
    "obsTime": 1746455400,
    "visib": "10+",
    "wdir": 270,
    "wspd": 14,
    "wgst": 22,
    "rawOb": "METAR KORD 051430Z 27014G22KT 10SM FEW040 SCT100",
    "clouds": [{"cover": "FEW", "base": 4000}, {"cover": "SCT", "base": 10000}]
  },
  {
    "icaoId": "KATL",
    "obsTime": 1746455400,
    "visib": "4",
    "wdir": 90,
    "wspd": 8,
    "rawOb": "METAR KATL 051430Z 09008KT 4SM BR OVC025",
    "clouds": [{"cover": "OVC", "base": 2500}]
  },
  {
    "icaoId": "KJFK",
    "obsTime": 1746455400,
    "visib": "2",
    "wdir": 180,
    "wspd": 12,
    "wgst": 18,
    "rawOb": "METAR KJFK 051430Z 18012G18KT 2SM RA OVC008",
    "clouds": [{"cover": "OVC", "base": 800}]
  },
  {
    "icaoId": "KLAX",
    "obsTime": 1746455400,
    "visib": "10+",
    "wdir": 250,
    "wspd": 6,
    "rawOb": "METAR KLAX 051430Z 25006KT 10SM CLR",
    "clouds": [{"cover": "CLR"}]
  },
  {
    "icaoId": "KDFW",
    "obsTime": 1746455400,
    "visib": "10+",
    "wdir": 160,
    "wspd": 18,
    "wgst": 28,
    "rawOb": "METAR KDFW 051430Z 16018G28KT 10SM SCT040 BKN200",
    "clouds": [{"cover": "SCT", "base": 4000}, {"cover": "BKN", "base": 20000}]
  }
]
```

**Step 4: Verify pass**

Run: `cd skytrack && mvn test -Dtest=ReplayAviationWeatherClientTest -q`
Expected: PASS.

**Step 5: Commit**

```bash
git add skytrack/src/main/java/skytrack/demo/client/ReplayAviationWeatherClient.java \
        skytrack/src/test/java/skytrack/demo/client/ReplayAviationWeatherClientTest.java \
        data/recorded-weather/sample-metar-2026-05-05T1430Z.json
git commit -m "feat: add ReplayAviationWeatherClient with sample METAR fixture"
```

---

## Task 5: WeatherSourceConfig (Profile-based wiring)

Spring `@Configuration` that selects the correct `WeatherSource` bean based on `weather.mode`. Mirrors `FlightDataSourceConfig`.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/config/WeatherSourceConfig.java`
- Test: `skytrack/src/test/java/skytrack/demo/config/WeatherSourceConfigTest.java`
- Modify: `skytrack/src/main/resources/application.yml` (add `weather:` block)
- Modify: `skytrack/src/main/resources/application-local.yml` (add replay config)

### Step 1: Write the failing test

**`WeatherSourceConfigTest.java`:**

```java
package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import skytrack.demo.client.LiveAviationWeatherClient;
import skytrack.demo.client.ReplayAviationWeatherClient;
import skytrack.demo.client.WeatherSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeatherSourceConfigTest {

    private final WeatherSourceConfig config = new WeatherSourceConfig();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldReturnReplayClientForReplayMode() {
        var props = new WeatherProperties("replay", null, "./data/recorded-weather/",
                5000, 15, 30, List.of("KORD"));
        WeatherSource source = config.weatherSource(props, mapper);
        assertThat(source).isInstanceOf(ReplayAviationWeatherClient.class);
    }

    @Test
    void shouldReturnLiveClientForLiveMode() {
        var props = new WeatherProperties("live", "https://example.com/metar",
                null, 5000, 15, 30, List.of("KORD"));
        WeatherSource source = config.weatherSource(props, mapper);
        assertThat(source).isInstanceOf(LiveAviationWeatherClient.class);
    }

    @Test
    void shouldRejectUnknownMode() {
        var props = new WeatherProperties("garbage", null, null,
                5000, 15, 30, List.of());
        assertThatThrownBy(() -> config.weatherSource(props, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weather.mode");
    }
}
```

**Step 2: Verify failure**

Run: `cd skytrack && mvn test -Dtest=WeatherSourceConfigTest -q`
Expected: FAIL — class missing.

**Step 3: Implement**

**`WeatherSourceConfig.java`:**

```java
package skytrack.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import skytrack.demo.client.LiveAviationWeatherClient;
import skytrack.demo.client.ReplayAviationWeatherClient;
import skytrack.demo.client.WeatherSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class WeatherSourceConfig {

    @Bean
    public WeatherSource weatherSource(WeatherProperties properties, ObjectMapper mapper) {
        return switch (properties.mode()) {
            case "live" -> new LiveAviationWeatherClient(properties, mapper);
            case "replay" -> new ReplayAviationWeatherClient(properties, mapper);
            default -> throw new IllegalArgumentException(
                    "Unknown weather.mode: " + properties.mode() + ". Expected 'live' or 'replay'.");
        };
    }
}
```

**Update `application.yml`** — add at the bottom:

```yaml
weather:
  mode: replay
  request-timeout-ms: 5000
  poll-interval-minutes: 15
  cache-ttl-minutes: 30
  target-airports:
    - KORD
    - KATL
    - KJFK
    - KLAX
    - KDFW
```

**Update `application-local.yml`** — add:

```yaml
weather:
  mode: replay
  replay-dir: ./data/recorded-weather/
```

(Production `application-prod.yml` will override `mode: live` and `api-url:` in a later chunk.)

**Step 4: Verify pass**

Run: `cd skytrack && mvn test -Dtest=WeatherSourceConfigTest -q`
Expected: PASS, all 3 tests green.

Then run the full app context to ensure beans wire correctly:

Run: `cd skytrack && mvn test -Dtest=MyApplicationTests -q` (or the existing context-load test, if any).
Expected: PASS.

**Step 5: Commit**

```bash
git add skytrack/src/main/java/skytrack/demo/config/WeatherSourceConfig.java \
        skytrack/src/test/java/skytrack/demo/config/WeatherSourceConfigTest.java \
        skytrack/src/main/resources/application.yml \
        skytrack/src/main/resources/application-local.yml
git commit -m "feat: wire WeatherSource by profile and add weather config defaults"
```

---

## Task 6: WeatherCache Service

In-memory `ConcurrentHashMap<String, CachedObservation>` keyed by airport ICAO. `CachedObservation` wraps `WeatherObservation` + `cachedAt: Instant`. Lookups return `Optional.empty()` if not present or expired (older than `cacheTtlMinutes`).

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/WeatherCache.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/WeatherCacheTest.java`

### Step 1: Write the failing test

**`WeatherCacheTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.WeatherObservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherCacheTest {

    private final WeatherProperties props =
            new WeatherProperties("replay", null, null, 5000, 15, 30, List.of());

    @Test
    void shouldStoreAndReturnFreshObservation() {
        var clock = Clock.fixed(Instant.parse("2026-05-05T15:00:00Z"), ZoneOffset.UTC);
        var cache = new WeatherCache(props, clock);

        var obs = sample("KORD", clock.instant());
        cache.update(List.of(obs));

        Optional<WeatherObservation> result = cache.get("KORD");
        assertThat(result).isPresent();
        assertThat(result.get().airportIcao()).isEqualTo("KORD");
    }

    @Test
    void shouldReturnEmptyForUnknownAirport() {
        var cache = new WeatherCache(props, Clock.systemUTC());
        assertThat(cache.get("KZZZ")).isEmpty();
    }

    @Test
    void shouldExpireAfterTtl() {
        var fixed = Instant.parse("2026-05-05T15:00:00Z");
        var mutableClock = new MutableClock(fixed);
        var cache = new WeatherCache(props, mutableClock);

        cache.update(List.of(sample("KORD", fixed)));
        assertThat(cache.get("KORD")).isPresent();

        mutableClock.advance(Duration.ofMinutes(31)); // ttl is 30 min
        assertThat(cache.get("KORD")).isEmpty();
    }

    @Test
    void shouldOverwriteExistingEntry() {
        var clock = Clock.fixed(Instant.parse("2026-05-05T15:00:00Z"), ZoneOffset.UTC);
        var cache = new WeatherCache(props, clock);

        cache.update(List.of(sample("KORD", clock.instant(), FlightCategory.VFR)));
        cache.update(List.of(sample("KORD", clock.instant(), FlightCategory.IFR)));

        assertThat(cache.get("KORD")).hasValueSatisfying(o ->
                assertThat(o.flightCategory()).isEqualTo(FlightCategory.IFR));
    }

    @Test
    void shouldReportSize() {
        var clock = Clock.fixed(Instant.parse("2026-05-05T15:00:00Z"), ZoneOffset.UTC);
        var cache = new WeatherCache(props, clock);
        cache.update(List.of(
                sample("KORD", clock.instant()),
                sample("KATL", clock.instant())));
        assertThat(cache.size()).isEqualTo(2);
    }

    private static WeatherObservation sample(String icao, Instant observedAt) {
        return sample(icao, observedAt, FlightCategory.VFR);
    }

    private static WeatherObservation sample(String icao, Instant observedAt, FlightCategory cat) {
        return new WeatherObservation(icao, null, observedAt,
                10.0, 5000, 10, null, cat, "raw");
    }

    static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant initial) { this.now = initial; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
```

**Step 2: Verify failure**

Run: `cd skytrack && mvn test -Dtest=WeatherCacheTest -q`
Expected: FAIL — class missing.

**Step 3: Implement**

**`WeatherCache.java`:**

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.WeatherObservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WeatherCache {

    private static final Logger log = LoggerFactory.getLogger(WeatherCache.class);

    private final ConcurrentHashMap<String, CachedObservation> entries = new ConcurrentHashMap<>();
    private final WeatherProperties properties;
    private final Clock clock;

    public WeatherCache(WeatherProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<WeatherObservation> get(String airportIcao) {
        if (airportIcao == null) return Optional.empty();
        CachedObservation cached = entries.get(airportIcao);
        if (cached == null) return Optional.empty();
        Duration age = Duration.between(cached.cachedAt(), clock.instant());
        if (age.toMinutes() >= properties.cacheTtlMinutes()) {
            entries.remove(airportIcao, cached);
            return Optional.empty();
        }
        return Optional.of(cached.observation());
    }

    public void update(List<WeatherObservation> observations) {
        Instant now = clock.instant();
        for (WeatherObservation obs : observations) {
            entries.put(obs.airportIcao(), new CachedObservation(obs, now));
        }
        log.debug("Weather cache updated: size={}, refreshed={}",
                entries.size(), observations.size());
    }

    public int size() {
        return entries.size();
    }

    private record CachedObservation(WeatherObservation observation, Instant cachedAt) {}
}
```

**Register a `Clock` bean** so the cache is testable. If no `Clock` bean exists yet, add one — check first:

Run: `grep -r "Clock.systemUTC" skytrack/src/main/java/`

If no `@Bean Clock` is registered, add to `MyApplication.java`:

```java
@Bean
public Clock clock() {
    return Clock.systemUTC();
}
```

(Required imports: `import java.time.Clock; import org.springframework.context.annotation.Bean;`)

**Step 4: Verify pass**

Run: `cd skytrack && mvn test -Dtest=WeatherCacheTest -q`
Expected: PASS, all 5 tests green.

**Step 5: Commit**

```bash
git add skytrack/src/main/java/skytrack/demo/service/WeatherCache.java \
        skytrack/src/test/java/skytrack/demo/service/WeatherCacheTest.java \
        skytrack/src/main/java/skytrack/demo/MyApplication.java
git commit -m "feat: add WeatherCache with TTL-based expiration"
```

---

## Task 7: WeatherPollingService (Scheduled Refresh)

Spring `@Scheduled` job that runs every `weather.poll-interval-minutes`, calls `WeatherSource.fetchObservations(targetAirports)`, and pushes results into `WeatherCache`. Logs counts for observability.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/WeatherPollingService.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/WeatherPollingServiceTest.java`

### Step 1: Write the failing test

**`WeatherPollingServiceTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.client.WeatherSource;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.WeatherObservation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherPollingServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-05T15:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldFetchAndPopulateCache() {
        WeatherSource source = mock(WeatherSource.class);
        var props = new WeatherProperties("replay", null, null, 5000, 15, 30,
                List.of("KORD", "KATL"));
        var cache = new WeatherCache(props, clock);
        WeatherObservation obs = new WeatherObservation("KORD", null, clock.instant(),
                10.0, 5000, 10, null, FlightCategory.VFR, "raw");
        when(source.fetchObservations(eq(List.of("KORD", "KATL"))))
                .thenReturn(List.of(obs));

        var service = new WeatherPollingService(source, cache, props);
        service.refresh();

        assertThat(cache.get("KORD")).isPresent();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void shouldSkipWhenNoTargetAirports() {
        WeatherSource source = mock(WeatherSource.class);
        var props = new WeatherProperties("replay", null, null, 5000, 15, 30, List.of());
        var cache = new WeatherCache(props, clock);

        var service = new WeatherPollingService(source, cache, props);
        service.refresh();

        verify(source, never()).fetchObservations(org.mockito.ArgumentMatchers.anyList());
        assertThat(cache.size()).isZero();
    }

    @Test
    void shouldSwallowExceptionsFromSource() {
        WeatherSource source = mock(WeatherSource.class);
        var props = new WeatherProperties("replay", null, null, 5000, 15, 30,
                List.of("KORD"));
        var cache = new WeatherCache(props, clock);
        when(source.fetchObservations(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new RuntimeException("boom"));

        var service = new WeatherPollingService(source, cache, props);
        service.refresh(); // must not throw

        assertThat(cache.size()).isZero();
    }
}
```

**Step 2: Verify failure**

Run: `cd skytrack && mvn test -Dtest=WeatherPollingServiceTest -q`
Expected: FAIL — class missing.

**Step 3: Implement**

**`WeatherPollingService.java`:**

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.client.WeatherSource;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.WeatherObservation;

import java.util.List;

@Service
public class WeatherPollingService {

    private static final Logger log = LoggerFactory.getLogger(WeatherPollingService.class);

    private final WeatherSource source;
    private final WeatherCache cache;
    private final WeatherProperties properties;

    public WeatherPollingService(WeatherSource source, WeatherCache cache,
                                 WeatherProperties properties) {
        this.source = source;
        this.cache = cache;
        this.properties = properties;
    }

    @Scheduled(fixedRateString = "#{${weather.poll-interval-minutes:15} * 60 * 1000}",
               initialDelay = 5_000)
    public void refresh() {
        if (properties.targetAirports().isEmpty()) {
            log.debug("Weather poll skipped: no target airports configured");
            return;
        }
        try {
            List<WeatherObservation> observations =
                    source.fetchObservations(properties.targetAirports());
            cache.update(observations);
            log.info("Weather poll: fetched {} observations for {} target airports",
                    observations.size(), properties.targetAirports().size());
        } catch (Exception e) {
            log.error("Weather poll failed: {}", e.getMessage(), e);
        }
    }
}
```

**Verify `@EnableScheduling` is present** in the application — check first:

Run: `grep -r "EnableScheduling" skytrack/src/main/java/`

If not present, add `@EnableScheduling` to `MyApplication.java`. (It's likely already there from Chunk 1's polling service.)

**Step 4: Verify pass**

Run: `cd skytrack && mvn test -Dtest=WeatherPollingServiceTest -q`
Expected: PASS, all 3 tests green.

**Step 5: Commit**

```bash
git add skytrack/src/main/java/skytrack/demo/service/WeatherPollingService.java \
        skytrack/src/test/java/skytrack/demo/service/WeatherPollingServiceTest.java
git commit -m "feat: add WeatherPollingService scheduled refresh"
```

---

## Task 8: Extend DelayEvent + DelayComputer for Weather

Add `flightCategory`, `visibilityStatuteMiles`, `ceilingFeet`, `windSpeedKnots` fields to `DelayEvent`. Add an overloaded `compute(arrival, weather)` method on `DelayComputer` so callers can pass the weather observation; keep the existing `compute(arrival)` as a delegating helper that passes `Optional.empty()`.

**Files:**
- Modify: `skytrack/src/main/java/skytrack/demo/model/DelayEvent.java`
- Modify: `skytrack/src/main/java/skytrack/demo/service/DelayComputer.java`
- Modify: `skytrack/src/test/java/skytrack/demo/model/DelayEventTest.java` (add weather assertions)
- Modify: `skytrack/src/test/java/skytrack/demo/service/DelayComputerTest.java` (add weather variant)

### Step 1: Write the failing tests

Append to `DelayEventTest.java`:

```java
@Test
void shouldCarryWeatherFields() {
    var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
            "KORD", "ORD", 1709312400L, 1709311500L, 900L,
            DelayClassification.MODERATE, "API_CACHE", Instant.now(),
            FlightCategory.IFR, 2.0, 800, 18);
    assertThat(event.flightCategory()).isEqualTo(FlightCategory.IFR);
    assertThat(event.visibilityStatuteMiles()).isEqualTo(2.0);
    assertThat(event.ceilingFeet()).isEqualTo(800);
    assertThat(event.windSpeedKnots()).isEqualTo(18);
}

@Test
void shouldAllowNullWeatherFields() {
    var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
            "KORD", "ORD", 1709312400L, 1709311500L, 900L,
            DelayClassification.MODERATE, "API_CACHE", Instant.now(),
            null, null, null, null);
    assertThat(event.flightCategory()).isNull();
}
```

(Required import: `import skytrack.demo.model.FlightCategory;` — already same package, so no import needed; just remove if redundant.)

Append to `DelayComputerTest.java`:

```java
@Test
void shouldEnrichWithWeatherWhenProvided() {
    var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
            "KORD", "ORD", 1709312400L, 1709311500L, 900L, "API_CACHE");
    var weather = new WeatherObservation("KORD", "ORD",
            Instant.parse("2026-05-05T15:00:00Z"),
            2.0, 800, 18, 25, FlightCategory.IFR, "raw");

    DelayEvent event = new DelayComputer().compute(arrival, Optional.of(weather));

    assertThat(event.flightCategory()).isEqualTo(FlightCategory.IFR);
    assertThat(event.visibilityStatuteMiles()).isEqualTo(2.0);
    assertThat(event.ceilingFeet()).isEqualTo(800);
    assertThat(event.windSpeedKnots()).isEqualTo(18);
}

@Test
void shouldLeaveWeatherFieldsNullWhenAbsent() {
    var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
            "KORD", "ORD", 1709312400L, 1709311500L, 900L, "API_CACHE");

    DelayEvent event = new DelayComputer().compute(arrival, Optional.empty());

    assertThat(event.flightCategory()).isNull();
    assertThat(event.visibilityStatuteMiles()).isNull();
    assertThat(event.ceilingFeet()).isNull();
    assertThat(event.windSpeedKnots()).isNull();
}
```

(Required imports: `import java.util.Optional; import skytrack.demo.model.FlightCategory; import skytrack.demo.model.WeatherObservation; import java.time.Instant;` — most already present.)

**Step 2: Verify failure**

Run: `cd skytrack && mvn test -Dtest='DelayEventTest,DelayComputerTest' -q`
Expected: FAIL — `DelayEvent` constructor signature mismatch, `compute(arrival, Optional)` does not exist.

**Step 3: Implement**

**`DelayEvent.java`** — extend the record:

```java
package skytrack.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DelayEvent(
        String icao24,
        String callsign,
        String carrierCode,
        String flightNumber,
        String arrivalAirportIcao,
        String arrivalAirportIata,
        long actualArrivalTime,
        Long scheduledArrivalTime,
        Long delaySeconds,
        DelayClassification classification,
        String resolutionMethod,
        Instant createdAt,
        FlightCategory flightCategory,
        Double visibilityStatuteMiles,
        Integer ceilingFeet,
        Integer windSpeedKnots
) {}
```

**`DelayComputer.java`** — add weather-aware overload:

```java
package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.model.WeatherObservation;

import java.time.Instant;
import java.util.Optional;

@Service
public class DelayComputer {

    public DelayEvent compute(ResolvedArrival arrival) {
        return compute(arrival, Optional.empty());
    }

    public DelayEvent compute(ResolvedArrival arrival, Optional<WeatherObservation> weather) {
        DelayClassification classification = DelayClassification.fromDelaySeconds(arrival.delaySeconds());
        FlightCategory category = weather.map(WeatherObservation::flightCategory).orElse(null);
        Double visibility = weather.map(WeatherObservation::visibilityStatuteMiles).orElse(null);
        Integer ceiling = weather.map(WeatherObservation::ceilingFeet).orElse(null);
        Integer windSpeed = weather.map(WeatherObservation::windSpeedKnots).orElse(null);

        return new DelayEvent(
                arrival.icao24(),
                arrival.callsign(),
                arrival.carrierCode(),
                arrival.flightNumber(),
                arrival.arrivalAirportIcao(),
                arrival.arrivalAirportIata(),
                arrival.actualArrivalTime(),
                arrival.scheduledArrivalTime(),
                arrival.delaySeconds(),
                classification,
                arrival.resolutionMethod(),
                Instant.now(),
                category,
                visibility,
                ceiling,
                windSpeed);
    }
}
```

**Important — fix all existing call sites and tests** that construct `DelayEvent` directly. Find them:

Run: `grep -rn "new DelayEvent(" skytrack/src/`

For each match (likely in `DelayComputerTest`, `DisruptionScoreServiceTest`, `CascadeDetectorTest`, `SqsAirportEventProducerTest`, `DelayEventProcessorTest`, etc.), append `, null, null, null, null` to the constructor argument list, or use a small helper. **Verify each test still compiles and passes — do not change semantics.**

**Step 4: Verify pass**

Run: `cd skytrack && mvn test -q`
Expected: PASS — all existing tests still green and new weather assertions green.

**Step 5: Commit**

```bash
git add skytrack/src/main/java/skytrack/demo/model/DelayEvent.java \
        skytrack/src/main/java/skytrack/demo/service/DelayComputer.java \
        skytrack/src/test/
git commit -m "feat: extend DelayEvent and DelayComputer with weather enrichment"
```

---

## Task 9: Wire WeatherCache into DelayEventProcessor

`DelayEventProcessor` queries `WeatherCache` for the arrival airport, passes the result to `DelayComputer.compute(arrival, weather)`, and continues the existing flow. A cache miss is non-fatal — events are emitted with null weather fields.

**Files:**
- Modify: `skytrack/src/main/java/skytrack/demo/service/DelayEventProcessor.java`
- Modify: `skytrack/src/test/java/skytrack/demo/service/DelayEventProcessorTest.java`

### Step 1: Write the failing tests

Append to `DelayEventProcessorTest.java` (or modify existing tests if they fully cover the constructor):

```java
@Test
void shouldEnrichDelayEventWithWeatherFromCache() {
    DelayComputer realComputer = new DelayComputer();
    DisruptionScoreService scoreService = mock(DisruptionScoreService.class);
    SqsAirportEventProducer producer = mock(SqsAirportEventProducer.class);
    CascadeDetector cascade = mock(CascadeDetector.class);
    when(cascade.checkCascade(any())).thenReturn(Optional.empty());
    WeatherCache cache = mock(WeatherCache.class);
    when(cache.get("KORD")).thenReturn(Optional.of(new WeatherObservation(
            "KORD", "ORD", Instant.now(),
            2.0, 800, 18, 25, FlightCategory.IFR, "raw")));

    var processor = new DelayEventProcessor(realComputer, scoreService,
            producer, cascade, cache);

    var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
            "KORD", "ORD", 1709312400L, 1709311500L, 900L, "API_CACHE");
    processor.process(arrival);

    ArgumentCaptor<DelayEvent> captor = ArgumentCaptor.forClass(DelayEvent.class);
    verify(producer).send(captor.capture());
    assertThat(captor.getValue().flightCategory()).isEqualTo(FlightCategory.IFR);
    assertThat(captor.getValue().ceilingFeet()).isEqualTo(800);
}

@Test
void shouldEmitEventWithoutWeatherOnCacheMiss() {
    DelayComputer realComputer = new DelayComputer();
    DisruptionScoreService scoreService = mock(DisruptionScoreService.class);
    SqsAirportEventProducer producer = mock(SqsAirportEventProducer.class);
    CascadeDetector cascade = mock(CascadeDetector.class);
    when(cascade.checkCascade(any())).thenReturn(Optional.empty());
    WeatherCache cache = mock(WeatherCache.class);
    when(cache.get(anyString())).thenReturn(Optional.empty());

    var processor = new DelayEventProcessor(realComputer, scoreService,
            producer, cascade, cache);

    var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
            "KORD", "ORD", 1709312400L, 1709311500L, 900L, "API_CACHE");
    processor.process(arrival);

    ArgumentCaptor<DelayEvent> captor = ArgumentCaptor.forClass(DelayEvent.class);
    verify(producer).send(captor.capture());
    assertThat(captor.getValue().flightCategory()).isNull();
}
```

(Required imports: `org.mockito.ArgumentCaptor`, `skytrack.demo.model.FlightCategory`, `skytrack.demo.model.WeatherObservation`, `java.time.Instant`, `static org.mockito.ArgumentMatchers.any`, `static org.mockito.ArgumentMatchers.anyString`.)

**Step 2: Verify failure**

Run: `cd skytrack && mvn test -Dtest=DelayEventProcessorTest -q`
Expected: FAIL — `DelayEventProcessor` constructor takes 4 args, not 5.

**Step 3: Implement**

**`DelayEventProcessor.java`:**

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import skytrack.demo.model.DelayEvent;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.model.WeatherObservation;
import skytrack.demo.sqs.SqsAirportEventProducer;

import java.util.Optional;

@Service
public class DelayEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(DelayEventProcessor.class);

    private final DelayComputer delayComputer;
    private final DisruptionScoreService disruptionScoreService;
    private final SqsAirportEventProducer eventProducer;
    private final CascadeDetector cascadeDetector;
    private final WeatherCache weatherCache;

    public DelayEventProcessor(DelayComputer delayComputer,
                               DisruptionScoreService disruptionScoreService,
                               SqsAirportEventProducer eventProducer,
                               CascadeDetector cascadeDetector,
                               WeatherCache weatherCache) {
        this.delayComputer = delayComputer;
        this.disruptionScoreService = disruptionScoreService;
        this.eventProducer = eventProducer;
        this.cascadeDetector = cascadeDetector;
        this.weatherCache = weatherCache;
    }

    public void process(ResolvedArrival arrival) {
        Optional<WeatherObservation> weather = weatherCache.get(arrival.arrivalAirportIcao());
        DelayEvent delayEvent = delayComputer.compute(arrival, weather);

        disruptionScoreService.recordDelay(delayEvent);
        eventProducer.send(delayEvent);

        cascadeDetector.checkCascade(delayEvent).ifPresent(alert ->
                log.info("Cascade risk: {} at {} predicted downstream delay={}min",
                        alert.sourceCallsign(), alert.arrivalAirportIata(),
                        alert.predictedDownstreamDelaySeconds() / 60));

        log.debug("Processed delay event: {} {} at {} classification={} delay={}s weather={}",
                delayEvent.carrierCode(), delayEvent.flightNumber(),
                delayEvent.arrivalAirportIata(), delayEvent.classification(),
                delayEvent.delaySeconds(),
                delayEvent.flightCategory());
    }
}
```

**Step 4: Verify pass**

Run: `cd skytrack && mvn test -q`
Expected: PASS — full test suite green.

**Step 5: Commit**

```bash
git add skytrack/src/main/java/skytrack/demo/service/DelayEventProcessor.java \
        skytrack/src/test/java/skytrack/demo/service/DelayEventProcessorTest.java
git commit -m "feat: enrich DelayEvent with weather via WeatherCache lookup"
```

---

## Task 10: Final Verification & Cleanup

End-to-end verification: full app context loads, full test suite passes, the local replay flow emits delay events tagged with weather. Update the project memory.

### Step 1: Run the full test suite

Run: `cd skytrack && mvn test -q`
Expected: PASS — every test green. Note any flaky tests in the commit message.

### Step 2: Run the full build

Run: `cd skytrack && mvn -q -DskipTests=false verify`
Expected: BUILD SUCCESS.

### Step 3: Local end-to-end smoke test (optional but recommended)

```bash
docker compose up -d localstack wiremock
cd skytrack && mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Watch the logs for:
- `Weather poll: fetched N observations for 5 target airports` (within 5 seconds of startup)
- On any landing: `Processed delay event: ... weather=VFR|MVFR|IFR|LIFR`

Then `Ctrl-C` and `docker compose down`.

### Step 4: Update memory

Append to `/Users/ryanpascual/.claude/projects/-Users-ryanpascual-SkyTrack/memory/MEMORY.md`:

```markdown
- Chunk 6 plan: `docs/plans/2026-05-05-chunk6-weather-integration.md` (COMPLETE)
```

(Or update an existing chunks-status entry if one exists.)

### Step 5: Final commit & cleanup

```bash
git status  # confirm tree clean
```

If any leftover changes from cross-cutting fixups remain:

```bash
git add -A
git commit -m "chore: Chunk 6 cleanup and verification"
```

---

## Summary

### New Files (8 source + 7 test = 15 total)

| File | Package | Type |
|------|---------|------|
| `model/FlightCategory.java` | model | Enum |
| `model/WeatherObservation.java` | model | Record |
| `config/WeatherProperties.java` | config | Properties |
| `config/WeatherSourceConfig.java` | config | Configuration |
| `client/WeatherSource.java` | client | Interface |
| `client/LiveAviationWeatherClient.java` | client | Client |
| `client/ReplayAviationWeatherClient.java` | client | Client |
| `service/WeatherCache.java` | service | Service |
| `service/WeatherPollingService.java` | service | Service |

### Modified Files

| File | Change |
|------|--------|
| `model/DelayEvent.java` | Add `flightCategory`, `visibilityStatuteMiles`, `ceilingFeet`, `windSpeedKnots` |
| `service/DelayComputer.java` | Add `compute(arrival, Optional<WeatherObservation>)` overload |
| `service/DelayEventProcessor.java` | Inject `WeatherCache`, look up weather before compute |
| `MyApplication.java` | Register `WeatherProperties`; ensure `Clock` and `@EnableScheduling` beans present |
| `application.yml` | Add `weather:` block with defaults |
| `application-local.yml` | Add `weather: { mode: replay, replay-dir: ./data/recorded-weather/ }` |

### New Data Files

| Path | Purpose |
|------|---------|
| `data/recorded-weather/sample-metar-2026-05-05T1430Z.json` | Replay-mode fixture (5 airports) |

### Test Files

| File | Type |
|------|------|
| `model/FlightCategoryTest.java` | Unit |
| `model/WeatherObservationTest.java` | Unit |
| `config/WeatherPropertiesTest.java` | Unit |
| `config/WeatherSourceConfigTest.java` | Unit |
| `client/LiveAviationWeatherClientTest.java` | Integration (WireMock) |
| `client/ReplayAviationWeatherClientTest.java` | Unit (TempDir) |
| `service/WeatherCacheTest.java` | Unit (mutable Clock) |
| `service/WeatherPollingServiceTest.java` | Unit (Mockito) |
| `model/DelayEventTest.java` | Unit (extended) |
| `service/DelayComputerTest.java` | Unit (extended) |
| `service/DelayEventProcessorTest.java` | Unit (extended) |

### Data Flow After Chunk 6

```
WeatherPollingService (every 15 min)
  → WeatherSource (live/replay)
  → WeatherCache.update(observations)

FlightPollingService (30s)
  → FlightDataSource → SqsPositionProducer → skytrack-positions.fifo
  → SqsPositionConsumer → StatefulFlightPositionHandler
      → AircraftStateMachine.process(track, position)
      → [on landing] ScheduleResolver.resolve(landingEvent) → ResolvedArrival
      → [on landing] DelayEventProcessor.process(resolvedArrival)
          → WeatherCache.get(arrivalAirportIcao) → Optional<WeatherObservation>  ← NEW
          → DelayComputer.compute(arrival, weather) → DelayEvent (with flightCategory)  ← NEW
          → DisruptionScoreService.recordDelay(event)
          → SqsAirportEventProducer.send(event)
          → CascadeDetector.checkCascade(event) → Optional<CascadeAlert>
      → AircraftTrackRepository.save(track) → DynamoDB
```

### Risks & Notes

1. **METAR API stability:** `aviationweather.gov/api/data/metar` is free and unauthenticated, but it's a government service with no SLA. The `LiveAviationWeatherClient` swallows errors and returns an empty list — this means a temporary outage degrades silently to `flightCategory=null` on emitted events, never breaks the pipeline.
2. **METAR JSON shape variations:** The `visib` field can be a string (`"10+"`, `"3"`) or numeric depending on the report. The parser handles strings only; if the API ever returns a number, `parseVisibility` returns null — events get `flightCategory=UNKNOWN` rather than crashing. Worth re-checking after the first live run in Week 5.
3. **Cache TTL vs polling interval:** Default TTL (30 min) is 2× the poll interval (15 min). This gives a one-poll buffer in case a poll fails. Tighten with caution — if TTL ≤ poll interval, every successful poll could expire entries before the next refresh.
4. **Replay mode reads "latest" file by name:** `ReplayAviationWeatherClient` picks the lexicographically largest filename. Timestamp-prefixed names (e.g., `sample-metar-2026-05-05T1430Z.json`) sort correctly. If you add multiple recordings and use a different naming scheme, this needs updating.
5. **Target-airport list is static at startup:** `WeatherProperties.targetAirports` is a fixed list. A flight landing at an airport not in this list gets `flightCategory=null`. For Week 5 (live mode), consider deriving the list from recent landings in DynamoDB rather than config — but that's a future-chunk concern.
6. **Existing `new DelayEvent(...)` call sites must all be updated:** Adding 4 fields to the record changes the canonical constructor. The grep step in Task 8 is critical — missing one site causes a compile failure that may not point cleanly at the offending file.
7. **`Clock` bean dependency:** `WeatherCache` takes a `Clock` for testability. If no `Clock` bean exists yet in the application, Task 6 adds one. Check Chunk 5's existing services — `DisruptionScoreService` may already use one.

---

**Next chunk preview:** Chunk 7 will tackle Week 4, Days 24-25 — S3 Parquet historical storage. Every 5 minutes, serialize position updates and delay events (now weather-enriched) to Parquet and upload to S3 (LocalStack locally). Schema will include the new weather fields added in this chunk.
