# Chunk 1: Data Recording & OpenSky Clients — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the foundation data layer — a `FlightPosition` record, a `FlightDataSource` interface, a live OpenSky client, a replay client that reads recorded JSON, Spring profile switching, and scheduled polling.

**Architecture:** Strategy pattern — `FlightDataSource` is the single interface the rest of the pipeline consumes. `LiveOpenSkyClient` calls the real OpenSky API; `ReplayOpenSkyClient` reads from disk. Spring profiles (`local` vs `prod`) control which implementation is injected. A `@Scheduled` poller drives the data loop.

**Tech Stack:** Spring Boot 4.0.2, Java 25 (records, sealed classes), Jackson for JSON, Spring `RestClient` for HTTP, JUnit 5 + Mockito for tests, Lombok (available but prefer records for DTOs).

> **Commits:** This plan does NOT include git commit steps. The developer commits manually after each task at their own discretion.

---

## Project Context

- **Module root:** `skytrack/` (Maven project, `pom.xml` at `skytrack/pom.xml`)
- **Base package:** `skytrack.demo`
- **Source root:** `skytrack/src/main/java/skytrack/demo/`
- **Test root:** `skytrack/src/test/java/skytrack/demo/`
- **Resources:** `skytrack/src/main/resources/`
- **Recorded data target:** `data/recorded-opensky/` (project root, outside module)
- **Existing code:** `Greeting.java` record, `GreetController.java`, `MyApplication.java` — leave these alone for now.

---

## Task 1: Add Jackson dependency (already transitive, verify)

Jackson is included transitively via `spring-boot-starter-web`. No POM changes needed, but verify.

**Step 1: Verify Jackson is on the classpath**

Run:
```bash
cd skytrack && mvn dependency:tree | grep jackson
```
Expected: Lines showing `jackson-databind`, `jackson-core`, `jackson-annotations`.

---

## Task 2: Define the `FlightPosition` record

The core data model that flows through the entire pipeline.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/model/FlightPosition.java`
- Test: `skytrack/src/test/java/skytrack/demo/model/FlightPositionTest.java`

**Step 1: Write the failing test**

```java
package skytrack.demo.model;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FlightPositionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCreateFlightPositionWithAllFields() {
        var fp = new FlightPosition(
                "abc123", "UAL1234", 41.9742, -87.9073,
                10668.0, 230.5, 270.0, false,
                1709312400L, 1709312400L, Instant.now()
        );

        assertThat(fp.icao24()).isEqualTo("abc123");
        assertThat(fp.callsign()).isEqualTo("UAL1234");
        assertThat(fp.latitude()).isEqualTo(41.9742);
        assertThat(fp.longitude()).isEqualTo(-87.9073);
        assertThat(fp.onGround()).isFalse();
    }

    @Test
    void shouldSerializeToJsonAndBack() throws Exception {
        var original = new FlightPosition(
                "abc123", "UAL1234", 41.9742, -87.9073,
                10668.0, 230.5, 270.0, false,
                1709312400L, 1709312400L, Instant.now()
        );

        String json = mapper.writeValueAsString(original);
        FlightPosition deserialized = mapper.readValue(json, FlightPosition.class);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void shouldTrimCallsignWhitespace() {
        var fp = new FlightPosition(
                "abc123", "  UAL1234  ", 41.9742, -87.9073,
                10668.0, 230.5, 270.0, false,
                1709312400L, 1709312400L, Instant.now()
        );

        assertThat(fp.callsign()).isEqualTo("UAL1234");
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.model.FlightPositionTest" -Dsurefire.failIfNoTests=false
```
Expected: Compilation failure — `FlightPosition` does not exist.

**Step 3: Write minimal implementation**

```java
package skytrack.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightPosition(
        String icao24,
        String callsign,
        Double latitude,
        Double longitude,
        Double baroAltitude,
        Double velocity,
        Double heading,
        boolean onGround,
        long lastContact,
        long timePosition,
        Instant parsedAt
) {
    public FlightPosition {
        if (callsign != null) {
            callsign = callsign.trim();
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.model.FlightPositionTest"
```
Expected: 3 tests PASS.

---

## Task 3: Define the `FlightDataSource` interface

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/client/FlightDataSource.java`

**Step 1: Write the interface**

```java
package skytrack.demo.client;

import skytrack.demo.model.FlightPosition;

import java.util.List;

public interface FlightDataSource {

    List<FlightPosition> fetchPositions();
}
```

No test needed — it's a pure interface with no logic.

---

## Task 4: Build the `LiveOpenSkyClient`

Implements `FlightDataSource`. Calls `/api/states/all`, deserializes the OpenSky JSON (which uses positional arrays, not named fields), filters to US-relevant flights.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/client/LiveOpenSkyClient.java`
- Create: `skytrack/src/main/java/skytrack/demo/config/OpenSkyProperties.java`
- Test: `skytrack/src/test/java/skytrack/demo/client/LiveOpenSkyClientTest.java`

**Step 1: Write the config properties class**

```java
package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opensky")
public record OpenSkyProperties(
        String mode,
        String apiUrl,
        String username,
        String password,
        String replayDir,
        int replaySpeedMultiplier
) {
    public OpenSkyProperties {
        if (mode == null) mode = "replay";
        if (apiUrl == null) apiUrl = "https://opensky-network.org";
        if (replayDir == null) replayDir = "./data/recorded-opensky/";
        if (replaySpeedMultiplier <= 0) replaySpeedMultiplier = 1;
    }
}
```

**Step 2: Enable config properties in the app**

Add `@ConfigurationPropertiesScan` to `MyApplication.java`:

Modify: `skytrack/src/main/java/skytrack/demo/MyApplication.java`
```java
package skytrack.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

**Step 3: Write the failing test**

OpenSky returns state vectors as arrays within `{"time": ..., "states": [[...], [...]]}`. We need to test parsing this format.

```java
package skytrack.demo.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import skytrack.demo.model.FlightPosition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiveOpenSkyClientTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    void shouldParseOpenSkyStateVectorArray() throws Exception {
        // OpenSky returns state vectors as positional arrays:
        // [0]=icao24, [1]=callsign, [2]=origin_country, [3]=time_position,
        // [4]=last_contact, [5]=longitude, [6]=latitude, [7]=baro_altitude,
        // [8]=on_ground, [9]=velocity, [10]=true_track(heading), ...
        String json = """
                {
                  "time": 1709312400,
                  "states": [
                    ["abc123", "UAL1234 ", "United States", 1709312400, 1709312400,
                     -87.9073, 41.9742, 10668.0, false, 230.5, 270.0,
                     null, null, null, null, false, 0]
                  ]
                }
                """;

        JsonNode root = mapper.readTree(json);
        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        assertThat(positions).hasSize(1);
        FlightPosition fp = positions.getFirst();
        assertThat(fp.icao24()).isEqualTo("abc123");
        assertThat(fp.callsign()).isEqualTo("UAL1234");
        assertThat(fp.latitude()).isEqualTo(41.9742);
        assertThat(fp.longitude()).isEqualTo(-87.9073);
        assertThat(fp.baroAltitude()).isEqualTo(10668.0);
        assertThat(fp.velocity()).isEqualTo(230.5);
        assertThat(fp.heading()).isEqualTo(270.0);
        assertThat(fp.onGround()).isFalse();
    }

    @Test
    void shouldFilterToUSFlightsOnly() throws Exception {
        String json = """
                {
                  "time": 1709312400,
                  "states": [
                    ["abc123", "UAL1234 ", "United States", 1709312400, 1709312400,
                     -87.9073, 41.9742, 10668.0, false, 230.5, 270.0,
                     null, null, null, null, false, 0],
                    ["def456", "BAW456  ", "United Kingdom", 1709312400, 1709312400,
                     -0.4614, 51.4700, 11000.0, false, 250.0, 90.0,
                     null, null, null, null, false, 0]
                  ]
                }
                """;

        JsonNode root = mapper.readTree(json);
        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        // Only the US flight should be included
        assertThat(positions).hasSize(1);
        assertThat(positions.getFirst().icao24()).isEqualTo("abc123");
    }

    @Test
    void shouldHandleNullStatesGracefully() throws Exception {
        String json = """
                {
                  "time": 1709312400,
                  "states": null
                }
                """;

        JsonNode root = mapper.readTree(json);
        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        assertThat(positions).isEmpty();
    }

    @Test
    void shouldSkipStateVectorsWithNullPosition() throws Exception {
        String json = """
                {
                  "time": 1709312400,
                  "states": [
                    ["abc123", "UAL1234 ", "United States", null, 1709312400,
                     null, null, 10668.0, false, 230.5, 270.0,
                     null, null, null, null, false, 0]
                  ]
                }
                """;

        JsonNode root = mapper.readTree(json);
        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        assertThat(positions).isEmpty();
    }
}
```

**Step 4: Run test to verify it fails**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.client.LiveOpenSkyClientTest"
```
Expected: Compilation failure — `LiveOpenSkyClient` does not exist.

**Step 5: Write the implementation**

```java
package skytrack.demo.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import skytrack.demo.config.OpenSkyProperties;
import skytrack.demo.model.FlightPosition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class LiveOpenSkyClient implements FlightDataSource {

    private static final Logger log = LoggerFactory.getLogger(LiveOpenSkyClient.class);
    private static final String US_ORIGIN = "United States";

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public LiveOpenSkyClient(OpenSkyProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.apiUrl());

        if (properties.username() != null && properties.password() != null) {
            builder.defaultHeaders(headers ->
                    headers.setBasicAuth(properties.username(), properties.password()));
        }

        this.restClient = builder.build();
    }

    @Override
    public List<FlightPosition> fetchPositions() {
        try {
            String body = restClient.get()
                    .uri("/api/states/all")
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(body);
            return parseStateVectors(root);
        } catch (Exception e) {
            log.error("Failed to fetch from OpenSky API", e);
            return List.of();
        }
    }

    static List<FlightPosition> parseStateVectors(JsonNode root) {
        JsonNode states = root.get("states");
        if (states == null || states.isNull()) {
            return List.of();
        }

        List<FlightPosition> positions = new ArrayList<>();
        Instant parsedAt = Instant.now();

        for (JsonNode sv : states) {
            try {
                String originCountry = sv.get(2).asText("");
                if (!US_ORIGIN.equals(originCountry)) {
                    continue;
                }

                JsonNode lonNode = sv.get(5);
                JsonNode latNode = sv.get(6);
                if (lonNode.isNull() || latNode.isNull()) {
                    continue;
                }

                positions.add(new FlightPosition(
                        sv.get(0).asText(),
                        sv.get(1).asText("").trim(),
                        latNode.asDouble(),
                        lonNode.asDouble(),
                        sv.get(7).isNull() ? null : sv.get(7).asDouble(),
                        sv.get(9).isNull() ? null : sv.get(9).asDouble(),
                        sv.get(10).isNull() ? null : sv.get(10).asDouble(),
                        sv.get(8).asBoolean(),
                        sv.get(4).asLong(),
                        sv.get(3).isNull() ? 0L : sv.get(3).asLong(),
                        parsedAt
                ));
            } catch (Exception e) {
                LoggerFactory.getLogger(LiveOpenSkyClient.class)
                        .warn("Failed to parse state vector: {}", sv, e);
            }
        }

        return positions;
    }
}
```

**Step 6: Run tests to verify they pass**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.client.LiveOpenSkyClientTest"
```
Expected: 4 tests PASS.

---

## Task 5: Build the recording script

A standalone class (or shell script) to record raw OpenSky API responses to disk. This is run once manually — not part of the regular app lifecycle.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/client/OpenSkyRecorder.java`

**Step 1: Write the recorder**

```java
package skytrack.demo.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public class OpenSkyRecorder {

    private static final Logger log = LoggerFactory.getLogger(OpenSkyRecorder.class);

    public static void main(String[] args) throws InterruptedException, IOException {
        String username = System.getenv("OPENSKY_USERNAME");
        String password = System.getenv("OPENSKY_PASSWORD");
        Path outputDir = Path.of(args.length > 0 ? args[0] : "data/recorded-opensky");

        Files.createDirectories(outputDir);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://opensky-network.org");

        if (username != null && password != null) {
            builder.defaultHeaders(h -> h.setBasicAuth(username, password));
            log.info("Using authenticated access (5s rate limit)");
        } else {
            log.info("Using anonymous access (10s rate limit)");
        }

        RestClient client = builder.build();
        int pollIntervalSeconds = 30;
        int durationMinutes = 120;
        int totalPolls = (durationMinutes * 60) / pollIntervalSeconds;

        log.info("Recording {} polls over {} minutes to {}", totalPolls, durationMinutes, outputDir);

        for (int i = 0; i < totalPolls; i++) {
            try {
                String body = client.get()
                        .uri("/api/states/all")
                        .retrieve()
                        .body(String.class);

                String filename = Instant.now().getEpochSecond() + ".json";
                Files.writeString(outputDir.resolve(filename), body);
                log.info("Poll {}/{} saved: {}", i + 1, totalPolls, filename);
            } catch (Exception e) {
                log.error("Poll {}/{} failed", i + 1, totalPolls, e);
            }

            if (i < totalPolls - 1) {
                Thread.sleep(pollIntervalSeconds * 1000L);
            }
        }

        log.info("Recording complete. {} files in {}", totalPolls, outputDir);
    }
}
```

**Step 2: Verify it compiles**

Run:
```bash
cd skytrack && mvn compile
```
Expected: BUILD SUCCESS.

> **Note for executor:** Task 1.4 in the roadmap (the actual recording session) is a manual step — run `OpenSkyRecorder` during a weekday afternoon 2-6 PM Eastern. This plan does not automate that. For development, you can create a small sample file manually (see Task 6).

---

## Task 6: Build the `ReplayOpenSkyClient`

Reads from recorded JSON files in timestamp order. Supports configurable replay speed.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/client/ReplayOpenSkyClient.java`
- Create: `skytrack/src/test/java/skytrack/demo/client/ReplayOpenSkyClientTest.java`
- Create (test fixture): `skytrack/src/test/resources/recorded-opensky/1709312400.json`
- Create (test fixture): `skytrack/src/test/resources/recorded-opensky/1709312430.json`

**Step 1: Create test fixture files**

`skytrack/src/test/resources/recorded-opensky/1709312400.json`:
```json
{
  "time": 1709312400,
  "states": [
    ["abc123", "UAL1234 ", "United States", 1709312400, 1709312400,
     -87.9073, 41.9742, 10668.0, false, 230.5, 270.0,
     null, null, null, null, false, 0]
  ]
}
```

`skytrack/src/test/resources/recorded-opensky/1709312430.json`:
```json
{
  "time": 1709312430,
  "states": [
    ["abc123", "UAL1234 ", "United States", 1709312430, 1709312430,
     -87.8500, 41.9800, 10700.0, false, 232.0, 271.0,
     null, null, null, null, false, 0],
    ["def789", "DAL567  ", "United States", 1709312430, 1709312430,
     -73.7781, 40.6413, 0.0, true, 0.0, 0.0,
     null, null, null, null, true, 0]
  ]
}
```

**Step 2: Write the failing test**

```java
package skytrack.demo.client;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import skytrack.demo.config.OpenSkyProperties;
import skytrack.demo.model.FlightPosition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayOpenSkyClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldReplayFilesInTimestampOrder(@TempDir Path tempDir) throws Exception {
        // Write files out of order to verify sorting
        Files.writeString(tempDir.resolve("1709312430.json"), """
                {"time": 1709312430, "states": [
                    ["def789", "DAL567  ", "United States", 1709312430, 1709312430,
                     -73.7781, 40.6413, 0.0, true, 0.0, 0.0,
                     null, null, null, null, true, 0]
                ]}
                """);
        Files.writeString(tempDir.resolve("1709312400.json"), """
                {"time": 1709312400, "states": [
                    ["abc123", "UAL1234 ", "United States", 1709312400, 1709312400,
                     -87.9073, 41.9742, 10668.0, false, 230.5, 270.0,
                     null, null, null, null, false, 0]
                ]}
                """);

        var props = new OpenSkyProperties("replay", null, null, null, tempDir.toString(), 1);
        var client = new ReplayOpenSkyClient(props, mapper);

        // First call returns first file's data
        List<FlightPosition> first = client.fetchPositions();
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().icao24()).isEqualTo("abc123");

        // Second call returns second file's data
        List<FlightPosition> second = client.fetchPositions();
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().icao24()).isEqualTo("def789");
    }

    @Test
    void shouldReturnEmptyWhenAllFilesReplayed(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("1709312400.json"), """
                {"time": 1709312400, "states": [
                    ["abc123", "UAL1234 ", "United States", 1709312400, 1709312400,
                     -87.9073, 41.9742, 10668.0, false, 230.5, 270.0,
                     null, null, null, null, false, 0]
                ]}
                """);

        var props = new OpenSkyProperties("replay", null, null, null, tempDir.toString(), 1);
        var client = new ReplayOpenSkyClient(props, mapper);

        client.fetchPositions(); // consume the only file
        List<FlightPosition> empty = client.fetchPositions();

        assertThat(empty).isEmpty();
    }

    @Test
    void shouldHandleEmptyDirectory(@TempDir Path tempDir) {
        var props = new OpenSkyProperties("replay", null, null, null, tempDir.toString(), 1);
        var client = new ReplayOpenSkyClient(props, mapper);

        List<FlightPosition> result = client.fetchPositions();

        assertThat(result).isEmpty();
    }
}
```

**Step 3: Run test to verify it fails**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.client.ReplayOpenSkyClientTest"
```
Expected: Compilation failure — `ReplayOpenSkyClient` does not exist.

**Step 4: Write the implementation**

```java
package skytrack.demo.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skytrack.demo.config.OpenSkyProperties;
import skytrack.demo.model.FlightPosition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class ReplayOpenSkyClient implements FlightDataSource {

    private static final Logger log = LoggerFactory.getLogger(ReplayOpenSkyClient.class);

    private final List<Path> replayFiles;
    private final ObjectMapper mapper;
    private final AtomicInteger index = new AtomicInteger(0);

    public ReplayOpenSkyClient(OpenSkyProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.replayFiles = loadSortedFiles(Path.of(properties.replayDir()));
        log.info("Replay client loaded {} files from {}", replayFiles.size(), properties.replayDir());
    }

    private static List<Path> loadSortedFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LoggerFactory.getLogger(ReplayOpenSkyClient.class)
                    .error("Failed to list replay directory: {}", dir, e);
            return List.of();
        }
    }

    @Override
    public List<FlightPosition> fetchPositions() {
        int i = index.getAndIncrement();
        if (i >= replayFiles.size()) {
            log.info("Replay complete — no more files");
            return List.of();
        }

        Path file = replayFiles.get(i);
        try {
            String content = Files.readString(file);
            JsonNode root = mapper.readTree(content);
            List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);
            log.info("Replayed file {}/{}: {} — {} positions",
                    i + 1, replayFiles.size(), file.getFileName(), positions.size());
            return positions;
        } catch (IOException e) {
            log.error("Failed to read replay file: {}", file, e);
            return List.of();
        }
    }

    public int remainingFiles() {
        return Math.max(0, replayFiles.size() - index.get());
    }
}
```

**Step 5: Run tests to verify they pass**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.client.ReplayOpenSkyClientTest"
```
Expected: 3 tests PASS.

---

## Task 7: Wire up Spring profiles for data source switching

Migrate from `application.properties` to YAML. Create `local` and `prod` profiles. Use a `@Configuration` class to conditionally create the right `FlightDataSource` bean.

**Files:**
- Create: `skytrack/src/main/resources/application.yml`
- Create: `skytrack/src/main/resources/application-local.yml`
- Create: `skytrack/src/main/resources/application-prod.yml`
- Delete: `skytrack/src/main/resources/application.properties`
- Create: `skytrack/src/main/java/skytrack/demo/config/FlightDataSourceConfig.java`
- Test: `skytrack/src/test/java/skytrack/demo/config/FlightDataSourceConfigTest.java`

**Step 1: Create YAML config files**

`skytrack/src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: skytrack

opensky:
  mode: replay
```

`skytrack/src/main/resources/application-local.yml`:
```yaml
opensky:
  mode: replay
  replay-dir: ./data/recorded-opensky/
  replay-speed-multiplier: 1
```

`skytrack/src/main/resources/application-prod.yml`:
```yaml
opensky:
  mode: live
  api-url: https://opensky-network.org
  username: ${OPENSKY_USERNAME:}
  password: ${OPENSKY_PASSWORD:}
```

**Step 2: Delete old properties file**

Run:
```bash
rm skytrack/src/main/resources/application.properties
```

**Step 3: Write the failing test**

```java
package skytrack.demo.config;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.client.LiveOpenSkyClient;
import skytrack.demo.client.ReplayOpenSkyClient;

import static org.assertj.core.api.Assertions.assertThat;

class FlightDataSourceConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCreateReplayClientWhenModeIsReplay() {
        var props = new OpenSkyProperties("replay", null, null, null, "./data/recorded-opensky/", 1);
        var config = new FlightDataSourceConfig();

        FlightDataSource source = config.flightDataSource(props, mapper);

        assertThat(source).isInstanceOf(ReplayOpenSkyClient.class);
    }

    @Test
    void shouldCreateLiveClientWhenModeIsLive() {
        var props = new OpenSkyProperties("live", "https://opensky-network.org", null, null, null, 1);
        var config = new FlightDataSourceConfig();

        FlightDataSource source = config.flightDataSource(props, mapper);

        assertThat(source).isInstanceOf(LiveOpenSkyClient.class);
    }
}
```

**Step 4: Run test to verify it fails**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.config.FlightDataSourceConfigTest"
```
Expected: Compilation failure — `FlightDataSourceConfig` does not exist.

**Step 5: Write the implementation**

```java
package skytrack.demo.config;

import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.client.LiveOpenSkyClient;
import skytrack.demo.client.ReplayOpenSkyClient;

@Configuration
public class FlightDataSourceConfig {

    @Bean
    public FlightDataSource flightDataSource(OpenSkyProperties properties, ObjectMapper mapper) {
        return switch (properties.mode()) {
            case "live" -> new LiveOpenSkyClient(properties, mapper);
            case "replay" -> new ReplayOpenSkyClient(properties, mapper);
            default -> throw new IllegalArgumentException(
                    "Unknown opensky.mode: " + properties.mode() + ". Expected 'live' or 'replay'.");
        };
    }
}
```

**Step 6: Run tests to verify they pass**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.config.FlightDataSourceConfigTest"
```
Expected: 2 tests PASS.

---

## Task 8: Add scheduled polling

Wire up `@Scheduled` to poll `FlightDataSource` every 30 seconds, log results.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/FlightPollingService.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/FlightPollingServiceTest.java`

**Step 1: Enable scheduling in the app**

Modify `MyApplication.java` — add `@EnableScheduling`:
```java
package skytrack.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

**Step 2: Write the failing test**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.model.FlightPosition;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightPollingServiceTest {

    @Mock
    private FlightDataSource flightDataSource;

    @InjectMocks
    private FlightPollingService pollingService;

    @Test
    void shouldCallFlightDataSourceOnPoll() {
        when(flightDataSource.fetchPositions()).thenReturn(List.of(
                new FlightPosition("abc123", "UAL1234", 41.97, -87.91,
                        10668.0, 230.5, 270.0, false,
                        1709312400L, 1709312400L, Instant.now())
        ));

        pollingService.pollFlightData();

        verify(flightDataSource, times(1)).fetchPositions();
    }

    @Test
    void shouldHandleEmptyResults() {
        when(flightDataSource.fetchPositions()).thenReturn(List.of());

        pollingService.pollFlightData();

        verify(flightDataSource, times(1)).fetchPositions();
    }

    @Test
    void shouldHandleDataSourceException() {
        when(flightDataSource.fetchPositions()).thenThrow(new RuntimeException("Connection failed"));

        pollingService.pollFlightData();

        verify(flightDataSource, times(1)).fetchPositions();
    }
}
```

**Step 3: Run test to verify it fails**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.service.FlightPollingServiceTest"
```
Expected: Compilation failure — `FlightPollingService` does not exist.

**Step 4: Write the implementation**

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.model.FlightPosition;

import java.util.List;

@Service
public class FlightPollingService {

    private static final Logger log = LoggerFactory.getLogger(FlightPollingService.class);

    private final FlightDataSource flightDataSource;

    public FlightPollingService(FlightDataSource flightDataSource) {
        this.flightDataSource = flightDataSource;
    }

    @Scheduled(fixedRate = 30_000)
    public void pollFlightData() {
        try {
            List<FlightPosition> positions = flightDataSource.fetchPositions();
            log.info("Polled {} aircraft positions", positions.size());

            if (!positions.isEmpty()) {
                FlightPosition sample = positions.getFirst();
                log.debug("Sample: {} ({}) at [{}, {}] alt={}m",
                        sample.callsign(), sample.icao24(),
                        sample.latitude(), sample.longitude(),
                        sample.baroAltitude());
            }
        } catch (Exception e) {
            log.error("Flight data polling failed", e);
        }
    }
}
```

**Step 5: Run tests to verify they pass**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.service.FlightPollingServiceTest"
```
Expected: 3 tests PASS.

---

## Task 9: Run all tests and verify end-to-end

**Step 1: Run full test suite**

Run:
```bash
cd skytrack && mvn clean test
```
Expected: All tests pass (existing `DemoApplicationTests` + all new tests).

> **Note:** If `DemoApplicationTests` (Spring context load test) fails because of missing replay directory, create it:
> ```bash
> mkdir -p data/recorded-opensky
> ```
> Or add `opensky.mode: replay` and `opensky.replay-dir: ./data/recorded-opensky/` to `application.yml` defaults so the context loads even with empty dir.

**Step 2: Manual smoke test (optional)**

Create a sample recorded file and run the app:
```bash
mkdir -p data/recorded-opensky
echo '{"time":1709312400,"states":[["abc123","UAL1234 ","United States",1709312400,1709312400,-87.9073,41.9742,10668.0,false,230.5,270.0,null,null,null,null,false,0]]}' > data/recorded-opensky/1709312400.json
cd skytrack && mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Expected: App starts, logs show "Polled 1 aircraft positions" every 30 seconds, then "Replay complete — no more files" and "Polled 0 aircraft positions".

**Step 3: Verify all tests pass**

Run:
```bash
cd skytrack && mvn clean test && echo "All Chunk 1 tests pass"
```
Expected: All tests PASS. Chunk 1 complete.

---

## Summary

| Task | What | Files Created | Tests |
|------|------|---------------|-------|
| 1 | Verify Jackson dependency | — | — |
| 2 | `FlightPosition` record | 2 | 3 |
| 3 | `FlightDataSource` interface | 1 | — |
| 4 | `LiveOpenSkyClient` + `OpenSkyProperties` | 3 | 4 |
| 5 | `OpenSkyRecorder` | 1 | — |
| 6 | `ReplayOpenSkyClient` | 2 + fixtures | 3 |
| 7 | Spring profiles + `FlightDataSourceConfig` | 4 (+ delete 1) | 2 |
| 8 | `FlightPollingService` + `@EnableScheduling` | 2 | 3 |
| 9 | Integration verification | — | — |
| **Total** | | **~15 files** | **15 tests** |
