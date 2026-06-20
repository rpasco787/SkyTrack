# Chunk 3: AeroAPI Client, WireMock & Synthetic Generator

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the flight schedule API integration layer — a `FlightScheduleApiClient` interface, an `AeroApiClient` implementation with rate limiting, WireMock for local dev, a synthetic flight generator for edge-case testing, and a daily prefetch service skeleton.

**Architecture:** Strategy pattern (mirroring `FlightDataSource`). `FlightScheduleApiClient` is the single interface the rest of the pipeline consumes. `AeroApiClient` calls FlightAware AeroAPI (or WireMock locally). Spring profiles control the target endpoint. A daily `@Scheduled` prefetch job drives the cache population (logs-only in this chunk; DynamoDB in Week 2).

**Tech Stack:** Spring Boot 4.0.2, Java 25 (records), Jackson 3.x (`tools.jackson.databind`), Spring `RestClient` for HTTP, WireMock 3.x for test/local mocking, JUnit 5 + Mockito for tests.

> **Commits:** This plan does NOT include git commit steps. The developer commits manually at their own discretion.

---

## Project Context

- **Module root:** `skytrack/` (Maven project, `pom.xml` at `skytrack/pom.xml`)
- **Base package:** `skytrack.demo`
- **Source root:** `skytrack/src/main/java/skytrack/demo/`
- **Test root:** `skytrack/src/test/java/skytrack/demo/`
- **Resources:** `skytrack/src/main/resources/`
- **Docker Compose:** `docker-compose.yml` (project root)

**Key patterns from existing code:**
- Properties → `@ConfigurationProperties` record with compact constructor defaults (see `SqsProperties.java`)
- Config → `@Configuration` class with `@Bean` methods (see `SqsConfig.java`)
- Clients → plain class (not `@Service`), constructor-injected, uses `RestClient` (see `LiveOpenSkyClient.java`)
- Parsing → package-private static methods for testability (see `LiveOpenSkyClient.parseStateVectors`)
- Jackson annotations use `com.fasterxml.jackson.annotation.*` (v2.20 compat via AWS SDK transitive dep)
- Jackson runtime uses `tools.jackson.databind.ObjectMapper`

---

## Task Dependency Graph

```
Task 1 (Maven + Docker) ──→ Task 2 (FlightSchedule) ──→ Task 3 (Interface) ──→ Task 4 (Properties)
                                                                                      │
                                                              Task 8 (Synthetic) ◄────┤
                                                                                      │
                                                          Task 5 (AeroApiClient) ◄────┘
                                                                    │
                                                          Task 6 (Config + YAML)
                                                                    │
                                              Task 7 (WireMock stubs) + Task 9 (Prefetch service)
                                                                    │
                                                          Task 10 (Integration tests)
```

**Parallelizable:** Task 8 (SyntheticFlightGenerator) can run in parallel with Tasks 5-7 since it only uses `FlightPosition` (already exists).

---

## Task 1: Add WireMock dependency + Docker service

### Files Modified
- `skytrack/pom.xml` — add WireMock test dependency
- `docker-compose.yml` — add WireMock service

### pom.xml — add inside `<dependencies>`

```xml
<!-- WireMock for AeroAPI integration tests -->
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.12.1</version>
    <scope>test</scope>
</dependency>
```

### docker-compose.yml — add `wiremock` service

```yaml
  wiremock:
    image: wiremock/wiremock:latest
    ports:
      - "9090:8080"
    volumes:
      - "./wiremock/mappings:/home/wiremock/mappings"
      - "./wiremock/__files:/home/wiremock/__files"
    command: ["--verbose"]
```

Port 9090 on host (8080 is taken by Spring Boot).

### Directories to create
- `wiremock/mappings/`
- `wiremock/__files/`

### Verification
```bash
mkdir -p wiremock/mappings wiremock/__files
docker compose up -d wiremock
curl http://localhost:9090/__admin/mappings
docker compose down
cd skytrack && mvn dependency:tree | grep wiremock
```

---

## Task 2: Define the `FlightSchedule` record

### Files
- Create: `skytrack/src/main/java/skytrack/demo/model/FlightSchedule.java`
- Test: `skytrack/src/test/java/skytrack/demo/model/FlightScheduleTest.java`

### Step 1: Write failing test

```java
package skytrack.demo.model;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class FlightScheduleTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCreateFlightScheduleWithAllFields() {
        var schedule = new FlightSchedule(
                "UAL1234", "UA1234", "United Airlines", "ORD", "LAX",
                Instant.parse("2026-03-15T14:00:00Z"), Instant.parse("2026-03-15T16:30:00Z"),
                Instant.parse("2026-03-15T14:05:00Z"), Instant.parse("2026-03-15T16:45:00Z"),
                "B12", "A5", "B738");
        assertThat(schedule.callsign()).isEqualTo("UAL1234");
        assertThat(schedule.origin()).isEqualTo("ORD");
        assertThat(schedule.destination()).isEqualTo("LAX");
    }

    @Test
    void shouldSerializeToJsonAndBack() throws Exception {
        var original = new FlightSchedule(
                "DAL567", "DL567", "Delta Air Lines", "ATL", "JFK",
                Instant.parse("2026-03-15T10:00:00Z"), Instant.parse("2026-03-15T12:30:00Z"),
                null, null, "C14", null, "A321");
        String json = mapper.writeValueAsString(original);
        FlightSchedule deserialized = mapper.readValue(json, FlightSchedule.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void shouldHandleNullOptionalFields() {
        var schedule = new FlightSchedule(
                "AAL100", "AA100", "American Airlines", "DFW", "MIA",
                Instant.parse("2026-03-15T08:00:00Z"), Instant.parse("2026-03-15T11:00:00Z"),
                null, null, null, null, null);
        assertThat(schedule.actualDeparture()).isNull();
        assertThat(schedule.gateOrigin()).isNull();
        assertThat(schedule.aircraftType()).isNull();
    }
}
```

### Step 2: Write implementation

```java
package skytrack.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightSchedule(
        String callsign,
        String flightNumber,
        String airline,
        String origin,
        String destination,
        Instant scheduledDeparture,
        Instant scheduledArrival,
        Instant actualDeparture,
        Instant actualArrival,
        String gateOrigin,
        String gateDestination,
        String aircraftType
) {}
```

### Verification
```bash
cd skytrack && mvn test -pl . -Dtest="FlightScheduleTest"
```

---

## Task 3: Define the `FlightScheduleApiClient` interface

### Files
- Create: `skytrack/src/main/java/skytrack/demo/client/FlightScheduleApiClient.java`

### Implementation

```java
package skytrack.demo.client;

import skytrack.demo.model.FlightSchedule;
import java.util.List;
import java.util.Optional;

public interface FlightScheduleApiClient {
    Optional<FlightSchedule> getFlightSchedule(String callsign, String date);
    List<FlightSchedule> getDailyFlights(String date);
}
```

Provider-agnostic — no AeroAPI types leak through. `date` is `YYYY-MM-DD` string.

---

## Task 4: Define `AeroApiProperties` record

### Files
- Create: `skytrack/src/main/java/skytrack/demo/config/AeroApiProperties.java`
- Test: `skytrack/src/test/java/skytrack/demo/config/AeroApiPropertiesTest.java`

### Step 1: Write failing test

```java
package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AeroApiPropertiesTest {
    @Test
    void shouldApplyDefaults() {
        var props = new AeroApiProperties(null, null, null, 0, 0);
        assertThat(props.enabled()).isFalse();
        assertThat(props.baseUrl()).isEqualTo("https://aeroapi.flightaware.com/aeroapi");
        assertThat(props.maxMonthlyCalls()).isEqualTo(10_000);
        assertThat(props.requestTimeoutMs()).isEqualTo(5_000);
    }

    @Test
    void shouldPreserveExplicitValues() {
        var props = new AeroApiProperties(true, "http://localhost:9090/aeroapi", "test-key", 500, 3000);
        assertThat(props.enabled()).isTrue();
        assertThat(props.baseUrl()).isEqualTo("http://localhost:9090/aeroapi");
        assertThat(props.apiKey()).isEqualTo("test-key");
    }
}
```

### Step 2: Write implementation

```java
package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aeroapi")
public record AeroApiProperties(
        Boolean enabled,
        String baseUrl,
        String apiKey,
        int maxMonthlyCalls,
        int requestTimeoutMs
) {
    public AeroApiProperties {
        if (enabled == null) enabled = false;
        if (baseUrl == null) baseUrl = "https://aeroapi.flightaware.com/aeroapi";
        if (maxMonthlyCalls <= 0) maxMonthlyCalls = 10_000;
        if (requestTimeoutMs <= 0) requestTimeoutMs = 5_000;
    }
}
```

### Verification
```bash
cd skytrack && mvn test -pl . -Dtest="AeroApiPropertiesTest"
```

---

## Task 5: Implement `AeroApiClient`

The centerpiece of this chunk. Follows the `LiveOpenSkyClient` pattern: plain class, RestClient, static parsing methods.

### Files
- Create: `skytrack/src/main/java/skytrack/demo/client/AeroApiClient.java`
- Test: `skytrack/src/test/java/skytrack/demo/client/AeroApiClientTest.java`

### Step 1: Write failing unit tests

Test the static JSON parsing logic directly (no HTTP calls):

```java
package skytrack.demo.client;

import org.junit.jupiter.api.Test;
import skytrack.demo.config.AeroApiProperties;
import skytrack.demo.model.FlightSchedule;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class AeroApiClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldParseAeroApiFlightResponse() throws Exception {
        String json = """
                {
                  "flights": [{
                    "ident": "UAL1234", "ident_iata": "UA1234", "operator": "UAL",
                    "origin": {"code": "KORD", "code_iata": "ORD"},
                    "destination": {"code": "KLAX", "code_iata": "LAX"},
                    "scheduled_out": "2026-03-15T14:00:00Z", "scheduled_in": "2026-03-15T16:30:00Z",
                    "actual_out": "2026-03-15T14:10:00Z", "actual_in": "2026-03-15T16:45:00Z",
                    "gate_origin": "B12", "gate_destination": "A5", "aircraft_type": "B738"
                  }]
                }
                """;
        FlightSchedule result = AeroApiClient.parseFlightFromJson(mapper, json);
        assertThat(result).isNotNull();
        assertThat(result.callsign()).isEqualTo("UAL1234");
        assertThat(result.origin()).isEqualTo("ORD");
        assertThat(result.destination()).isEqualTo("LAX");
        assertThat(result.gateOrigin()).isEqualTo("B12");
    }

    @Test
    void shouldReturnNullWhenNoFlightsInResponse() throws Exception {
        FlightSchedule result = AeroApiClient.parseFlightFromJson(mapper, """
                { "flights": [] }
                """);
        assertThat(result).isNull();
    }

    @Test
    void shouldTrackCallCountForRateLimit() {
        var props = new AeroApiProperties(true, "http://localhost:9090/aeroapi", "key", 100, 5000);
        var client = new AeroApiClient(props, mapper);
        assertThat(client.getRemainingCalls()).isEqualTo(100);
    }
}
```

### Step 2: Write implementation

Key design points:
- `RestClient` with `x-apikey` default header
- `AtomicInteger callsThisMonth` for rate-limit guard (warns at 90%, blocks at max)
- Package-private static `parseFlightFromJson` / `parseFlightsFromJson` for testability
- All methods return empty/null on failure, never throw (matches `LiveOpenSkyClient` pattern)
- AeroAPI JSON has nested `origin.code_iata` / `destination.code_iata` — parse with `JsonNode`

Core structure:
```java
public class AeroApiClient implements FlightScheduleApiClient {
    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final AeroApiProperties properties;
    private final AtomicInteger callsThisMonth;

    // getFlightSchedule: GET /flights/{ident}?start={date}&end={date}
    // getDailyFlights: GET /flights?start={date}&end={date}
    // checkBudget(): warns at 90%, returns false at max
    // static parseFlightFromJson(mapper, json): parses nested AeroAPI format
    // static parseFlightsFromJson(mapper, json): parses multi-flight response
}
```

### Verification
```bash
cd skytrack && mvn test -pl . -Dtest="AeroApiClientTest"
```

---

## Task 6: Create `AeroApiConfig` + wire YAML properties

### Files
- Create: `skytrack/src/main/java/skytrack/demo/config/AeroApiConfig.java`
- Test: `skytrack/src/test/java/skytrack/demo/config/AeroApiConfigTest.java`
- Modify: `skytrack/src/main/resources/application.yml`
- Modify: `skytrack/src/main/resources/application-local.yml`
- Modify: `skytrack/src/main/resources/application-prod.yml`

### Step 1: Write failing test

```java
package skytrack.demo.config;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import skytrack.demo.client.AeroApiClient;
import skytrack.demo.client.FlightScheduleApiClient;
import static org.assertj.core.api.Assertions.assertThat;

class AeroApiConfigTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCreateAeroApiClientBean() {
        var props = new AeroApiProperties(true, "http://localhost:9090/aeroapi", "test-key", 100, 5000);
        var config = new AeroApiConfig();
        FlightScheduleApiClient client = config.flightScheduleApiClient(props, mapper);
        assertThat(client).isInstanceOf(AeroApiClient.class);
    }
}
```

### Step 2: Write implementation

```java
package skytrack.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import skytrack.demo.client.AeroApiClient;
import skytrack.demo.client.FlightScheduleApiClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class AeroApiConfig {
    @Bean
    public FlightScheduleApiClient flightScheduleApiClient(AeroApiProperties properties, ObjectMapper mapper) {
        return new AeroApiClient(properties, mapper);
    }
}
```

Bean type is `FlightScheduleApiClient` (the interface), not `AeroApiClient`.

### Step 3: Add YAML properties

**`application.yml`** — add:
```yaml
aeroapi:
  enabled: false
  max-monthly-calls: 10000
  request-timeout-ms: 5000
```

**`application-local.yml`** — add:
```yaml
aeroapi:
  enabled: true
  base-url: http://localhost:9090/aeroapi
  api-key: mock-api-key
```

**`application-prod.yml`** — add:
```yaml
aeroapi:
  enabled: true
  base-url: https://aeroapi.flightaware.com/aeroapi
  api-key: ${AEROAPI_KEY:}
```

### Verification
```bash
cd skytrack && mvn test -pl . -Dtest="AeroApiConfigTest"
```

---

## Task 7: Create WireMock stub mappings

5 stubs covering: on-time, delayed departure, delayed arrival, missing flight (catch-all), daily flights.

### Files to create

**Mappings (in `wiremock/mappings/`):**
- `ual1234-ontime.json` — matches `/aeroapi/flights/UAL1234.*`
- `dal567-delayed-departure.json` — matches `/aeroapi/flights/DAL567.*`
- `aal100-delayed-arrival.json` — matches `/aeroapi/flights/AAL100.*`
- `unknown-missing.json` — catch-all at priority 10, matches `/aeroapi/flights/.*`, returns empty
- `daily-flights.json` — matches `/aeroapi/flights\?.*`, returns all 3 flights

**Response bodies (in `wiremock/__files/`):**
- `ual1234-ontime-response.json` — UAL1234 ORD→LAX, on time
- `dal567-delayed-departure-response.json` — DAL567 ATL→JFK, 45min departure delay
- `aal100-delayed-arrival-response.json` — AAL100 DFW→MIA, 30min arrival delay
- `unknown-missing-response.json` — `{"flights": []}`
- `daily-flights-response.json` — array of all 3 flights (no actuals yet)

Each mapping follows this structure:
```json
{
  "request": {"method": "GET", "urlPathPattern": "/aeroapi/flights/UAL1234.*"},
  "response": {"status": 200, "bodyFileName": "ual1234-ontime-response.json",
               "headers": {"Content-Type": "application/json"}}
}
```

### Verification
```bash
docker compose up -d wiremock
curl -s http://localhost:9090/aeroapi/flights/UAL1234 | python3 -m json.tool
curl -s http://localhost:9090/aeroapi/flights/UNKNOWN999 | python3 -m json.tool
docker compose down
```

---

## Task 8: Build `SyntheticFlightGenerator` (parallelizable with Tasks 5-7)

### Files
- Create: `skytrack/src/main/java/skytrack/demo/testing/SyntheticFlightGenerator.java`
- Test: `skytrack/src/test/java/skytrack/demo/testing/SyntheticFlightGeneratorTest.java`

Package `skytrack.demo.testing` — in `src/main/java` so it's available from both tests and a future simulation profile.

### Step 1: Write failing tests

```java
package skytrack.demo.testing;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.FlightPosition;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SyntheticFlightGeneratorTest {
    @Test
    void shouldGenerateCascadeWithCorrectLegCount() {
        List<FlightPosition> positions = SyntheticFlightGenerator.generateCascadeScenario(3, 45, 0.85);
        long distinctCallsigns = positions.stream().map(FlightPosition::callsign).distinct().count();
        assertThat(distinctCallsigns).isEqualTo(3);
        // Sorted by time
        for (int i = 1; i < positions.size(); i++) {
            assertThat(positions.get(i).lastContact())
                    .isGreaterThanOrEqualTo(positions.get(i - 1).lastContact());
        }
    }

    @Test
    void shouldGenerateHoldingPattern() {
        List<FlightPosition> positions = SyntheticFlightGenerator.generateHoldingPattern("UAL999", "ORD");
        assertThat(positions).isNotEmpty().allMatch(fp -> "UAL999".equals(fp.callsign()));
        long distinctHeadings = positions.stream().filter(fp -> !fp.onGround())
                .map(FlightPosition::heading).distinct().count();
        assertThat(distinctHeadings).isGreaterThan(2);
    }

    @Test
    void shouldGenerateBurstArrivals() {
        List<FlightPosition> positions = SyntheticFlightGenerator.generateBurstArrivals("ORD", 5, 30);
        long distinctCallsigns = positions.stream().map(FlightPosition::callsign).distinct().count();
        assertThat(distinctCallsigns).isEqualTo(5);
    }

    @Test
    void shouldProduceValidCoordinates() {
        List<FlightPosition> positions = SyntheticFlightGenerator.generateHoldingPattern("DAL100", "ATL");
        for (FlightPosition fp : positions) {
            assertThat(fp.latitude()).isBetween(-90.0, 90.0);
            assertThat(fp.longitude()).isBetween(-180.0, 180.0);
        }
    }
}
```

### Step 2: Write implementation

Utility class (final, private constructor) with static methods:
- `generateCascadeScenario(legs, initialDelayMinutes, decayFactor)` — N legs, decaying delay
- `generateHoldingPattern(callsign, airport)` — ~20 positions circling near airport
- `generateBurstArrivals(airport, count, windowMinutes)` — N flights arriving within window

Uses hardcoded coordinates for ORD, ATL, JFK, LAX, MIA. Each method returns `List<FlightPosition>` sorted by time.

### Verification
```bash
cd skytrack && mvn test -pl . -Dtest="SyntheticFlightGeneratorTest"
```

---

## Task 9: Daily prefetch service skeleton

### Files
- Create: `skytrack/src/main/java/skytrack/demo/service/DailySchedulePrefetchService.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/DailySchedulePrefetchServiceTest.java`

### Step 1: Write failing test

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.model.FlightSchedule;
import java.time.Instant;
import java.util.List;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailySchedulePrefetchServiceTest {
    @Mock private FlightScheduleApiClient scheduleApiClient;
    @InjectMocks private DailySchedulePrefetchService prefetchService;

    @Test
    void shouldCallGetDailyFlights() {
        when(scheduleApiClient.getDailyFlights(anyString())).thenReturn(List.of(
                new FlightSchedule("UAL1234", "UA1234", "UAL", "ORD", "LAX",
                        Instant.now(), Instant.now().plusSeconds(9000),
                        null, null, null, null, "B738")));
        prefetchService.prefetchDailySchedules();
        verify(scheduleApiClient, times(1)).getDailyFlights(anyString());
    }

    @Test
    void shouldHandleEmptyResults() {
        when(scheduleApiClient.getDailyFlights(anyString())).thenReturn(List.of());
        prefetchService.prefetchDailySchedules();
        verify(scheduleApiClient, times(1)).getDailyFlights(anyString());
    }

    @Test
    void shouldHandleApiException() {
        when(scheduleApiClient.getDailyFlights(anyString())).thenThrow(new RuntimeException("API down"));
        prefetchService.prefetchDailySchedules();
        verify(scheduleApiClient, times(1)).getDailyFlights(anyString());
    }
}
```

### Step 2: Write implementation

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.model.FlightSchedule;
import java.time.LocalDate;
import java.util.List;

@Service
public class DailySchedulePrefetchService {
    private static final Logger log = LoggerFactory.getLogger(DailySchedulePrefetchService.class);
    private final FlightScheduleApiClient scheduleApiClient;

    public DailySchedulePrefetchService(FlightScheduleApiClient scheduleApiClient) {
        this.scheduleApiClient = scheduleApiClient;
    }

    @Scheduled(cron = "0 0 0 * * *")  // midnight daily
    public void prefetchDailySchedules() {
        String today = LocalDate.now().toString();
        try {
            List<FlightSchedule> schedules = scheduleApiClient.getDailyFlights(today);
            log.info("Prefetched {} flight schedules for {}", schedules.size(), today);
            // TODO (Week 2): Write to DynamoDB cache
        } catch (Exception e) {
            log.error("Daily schedule prefetch failed for {}", today, e);
        }
    }
}
```

### Verification
```bash
cd skytrack && mvn test -pl . -Dtest="DailySchedulePrefetchServiceTest"
```

---

## Task 10: WireMock integration tests for `AeroApiClient`

### Files
- Create: `skytrack/src/test/java/skytrack/demo/client/AeroApiClientIntegrationTest.java`

Uses WireMock JUnit 5 extension (`@WireMockTest`) — no Docker needed for tests.

### Write the test

Key test scenarios:
1. Successful fetch + deserialization with all fields
2. `x-apikey` header is sent correctly
3. Empty response (flight not found) → `Optional.empty()`
4. Server error → `Optional.empty()` (no exception)
5. Daily flights endpoint → `List<FlightSchedule>`
6. Rate-limit budget enforcement (low budget, verify HTTP call count)
7. Disabled client returns empty without making HTTP calls

```java
@WireMockTest
class AeroApiClientIntegrationTest {
    // WireMock injects the running server
    // Tests create AeroApiClient pointing at wireMock.baseUrl() + "/aeroapi"
    // Each test stubs specific endpoints and verifies behavior
}
```

### Verification
```bash
cd skytrack && mvn test -pl . -Dtest="AeroApiClientIntegrationTest"
```

---

## Final Verification

### 1. Full test suite
```bash
cd skytrack && mvn clean test
```
Expected: all existing tests + ~24 new tests pass.

### 2. Docker Compose smoke test
```bash
docker compose up -d
curl -s http://localhost:9090/aeroapi/flights/UAL1234 | python3 -m json.tool
curl -s http://localhost:9090/aeroapi/flights/UNKNOWN | python3 -m json.tool
docker compose down
```

### 3. Spring Boot startup with local profile
```bash
docker compose up -d localstack wiremock
cd skytrack && mvn spring-boot:run -Dspring-boot.run.profiles=local
# Verify: AeroApiClient bean created, no startup errors
```

---

## Summary

| Task | What | Files Created | Tests |
|------|------|---------------|-------|
| 1 | Maven dep + Docker WireMock | 2 dirs | — |
| 2 | `FlightSchedule` record | 2 | 3 |
| 3 | `FlightScheduleApiClient` interface | 1 | — |
| 4 | `AeroApiProperties` record | 2 | 2 |
| 5 | `AeroApiClient` implementation | 2 | 3 |
| 6 | `AeroApiConfig` + YAML | 2 (+ 3 modified) | 1 |
| 7 | WireMock stubs | 10 | — |
| 8 | `SyntheticFlightGenerator` | 2 | 4 |
| 9 | `DailySchedulePrefetchService` | 2 | 3 |
| 10 | Integration tests | 1 | ~6 |
| **Total** | | **~26 files** | **~22 tests** |

### Critical files to reference during implementation
- `skytrack/src/main/java/skytrack/demo/client/LiveOpenSkyClient.java` — pattern for RestClient usage + static parsing
- `skytrack/src/main/java/skytrack/demo/config/SqsProperties.java` — pattern for properties records
- `skytrack/src/main/java/skytrack/demo/config/SqsConfig.java` — pattern for config beans
- `skytrack/src/main/java/skytrack/demo/model/FlightPosition.java` — pattern for data records with Jackson annotations
