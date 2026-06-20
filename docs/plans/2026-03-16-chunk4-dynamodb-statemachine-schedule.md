# Chunk 4: DynamoDB, Aircraft State Machine & Schedule Resolution

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the stateful processing engine that tracks aircraft state transitions in DynamoDB, detects landings via airport proximity, resolves flight schedules via AeroAPI, and replaces the logging handler with an intelligent state machine handler.

**Architecture:** Aircraft positions flow through SQS → `StatefulFlightPositionHandler` → `AircraftStateMachine` (standalone class). The state machine computes transitions using the OpenSky `onGround` flag plus Haversine proximity to US airports (loaded from OurAirports CSV). On landing detection, a `LandingEvent` record is passed synchronously to `ScheduleResolver`, which calls `AeroApiClient` to compute delay. Aircraft state is persisted to DynamoDB via the Enhanced Client. A `RouteAverageEstimator` provides fallback delay estimates from previously resolved AeroAPI data.

**Tech Stack:** Spring Boot 4.0.2, Java 25, DynamoDB Enhanced Client (AWS SDK v2), Lombok, Testcontainers + LocalStack, WireMock

**Depends on:** Chunks 1–3 (OpenSky clients, SQS pipeline, AeroAPI + WireMock)

---

## Task 1: DynamoDB Infrastructure

Add the DynamoDB Enhanced Client dependency, config beans, LocalStack table creation, and YAML properties.

**Files:**
- Modify: `skytrack/pom.xml`
- Create: `skytrack/src/main/java/skytrack/demo/config/DynamoDbProperties.java`
- Create: `skytrack/src/main/java/skytrack/demo/config/DynamoDbConfig.java`
- Modify: `skytrack/src/main/resources/application.yml`
- Modify: `skytrack/src/main/resources/application-local.yml`
- Modify: `skytrack/src/main/resources/application-prod.yml`
- Modify: `localstack/init-aws.sh`
- Test: `skytrack/src/test/java/skytrack/demo/config/DynamoDbPropertiesTest.java`
- Test: `skytrack/src/test/java/skytrack/demo/config/DynamoDbConfigTest.java`

### Step 1: Write the failing tests

**`DynamoDbPropertiesTest.java`:**

```java
package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbPropertiesTest {

    @Test
    void shouldBindProperties() {
        var props = new DynamoDbProperties("skytrack-aircraft", "http://localhost:4566", "us-east-1");
        assertThat(props.tableName()).isEqualTo("skytrack-aircraft");
        assertThat(props.endpoint()).isEqualTo("http://localhost:4566");
        assertThat(props.region()).isEqualTo("us-east-1");
    }

    @Test
    void shouldAllowNullEndpointForProd() {
        var props = new DynamoDbProperties("skytrack-aircraft", null, "us-east-1");
        assertThat(props.endpoint()).isNull();
    }
}
```

**`DynamoDbConfigTest.java`:**

```java
package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbConfigTest {

    @Test
    void shouldCreateDynamoDbClientWithEndpointOverride() {
        var props = new DynamoDbProperties("skytrack-aircraft", "http://localhost:4566", "us-east-1");
        var config = new DynamoDbConfig();
        DynamoDbClient client = config.dynamoDbClient(props);
        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    void shouldCreateEnhancedClient() {
        var props = new DynamoDbProperties("skytrack-aircraft", "http://localhost:4566", "us-east-1");
        var config = new DynamoDbConfig();
        DynamoDbClient client = config.dynamoDbClient(props);
        DynamoDbEnhancedClient enhanced = config.dynamoDbEnhancedClient(client);
        assertThat(enhanced).isNotNull();
        client.close();
    }
}
```

### Step 2: Run tests to verify they fail

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DynamoDbPropertiesTest,DynamoDbConfigTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — classes don't exist yet.

### Step 3: Add DynamoDB dependency to pom.xml

Add inside the `<dependencies>` block of `skytrack/pom.xml`, after the `sqs` dependency:

```xml
<!-- AWS SDK v2 - DynamoDB Enhanced Client -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>dynamodb-enhanced</artifactId>
</dependency>
```

The existing `software.amazon.awssdk:bom` in `<dependencyManagement>` manages the version.

### Step 4: Create DynamoDbProperties

```java
package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.dynamodb")
public record DynamoDbProperties(
        String tableName,
        String endpoint,
        String region) {}
```

### Step 5: Create DynamoDbConfig

```java
package skytrack.demo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(DynamoDbProperties.class)
public class DynamoDbConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(DynamoDbProperties props) {
        var builder = DynamoDbClient.builder()
                .region(Region.of(props.region()));

        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }

        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(client)
                .build();
    }
}
```

### Step 6: Update YAML configs

**Append to `application.yml`:**

```yaml
skytrack:
  dynamodb:
    table-name: skytrack-aircraft
    region: us-east-1
```

**Append to `application-local.yml`:**

```yaml
skytrack:
  dynamodb:
    endpoint: http://localhost:4566
```

**Append to `application-prod.yml`:**

```yaml
skytrack:
  dynamodb:
    table-name: skytrack-aircraft
    region: us-east-1
```

### Step 7: Update LocalStack init script

Append to `localstack/init-aws.sh` before the final echo:

```bash
echo "Creating DynamoDB table..."

awslocal dynamodb create-table \
  --table-name skytrack-aircraft \
  --attribute-definitions \
    AttributeName=icao24,AttributeType=S \
    AttributeName=sortKey,AttributeType=S \
  --key-schema \
    AttributeName=icao24,KeyType=HASH \
    AttributeName=sortKey,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

awslocal dynamodb update-time-to-live \
  --table-name skytrack-aircraft \
  --time-to-live-specification "Enabled=true,AttributeName=ttl"

echo "DynamoDB table created:"
awslocal dynamodb list-tables
```

### Step 8: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DynamoDbPropertiesTest,DynamoDbConfigTest"
```

Expected: PASS

### Step 9: Commit

```bash
git add skytrack/pom.xml \
  skytrack/src/main/java/skytrack/demo/config/DynamoDbProperties.java \
  skytrack/src/main/java/skytrack/demo/config/DynamoDbConfig.java \
  skytrack/src/main/resources/application.yml \
  skytrack/src/main/resources/application-local.yml \
  skytrack/src/main/resources/application-prod.yml \
  localstack/init-aws.sh \
  skytrack/src/test/java/skytrack/demo/config/DynamoDbPropertiesTest.java \
  skytrack/src/test/java/skytrack/demo/config/DynamoDbConfigTest.java
git commit -m "feat: add DynamoDB Enhanced Client infrastructure"
```

---

## Task 2: Airport Model & Lookup Service

Load US airports from `data/airports/airports.csv`, find nearest airport by Haversine distance.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/model/Airport.java`
- Create: `skytrack/src/main/java/skytrack/demo/service/AirportLookupService.java`
- Test: `skytrack/src/test/java/skytrack/demo/model/AirportTest.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/AirportLookupServiceTest.java`

### Step 1: Write the failing tests

**`AirportTest.java`:**

```java
package skytrack.demo.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AirportTest {

    @Test
    void shouldConstructAirportRecord() {
        var airport = new Airport("KLAX", "KLAX", "LAX", "Los Angeles International Airport",
                33.9425, -118.4081, "large_airport");
        assertThat(airport.ident()).isEqualTo("KLAX");
        assertThat(airport.iataCode()).isEqualTo("LAX");
        assertThat(airport.latitude()).isEqualTo(33.9425);
        assertThat(airport.longitude()).isEqualTo(-118.4081);
    }

    @Test
    void shouldSupportEquality() {
        var a1 = new Airport("KLAX", "KLAX", "LAX", "LAX", 33.9425, -118.4081, "large_airport");
        var a2 = new Airport("KLAX", "KLAX", "LAX", "LAX", 33.9425, -118.4081, "large_airport");
        assertThat(a1).isEqualTo(a2);
    }
}
```

**`AirportLookupServiceTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import skytrack.demo.model.Airport;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AirportLookupServiceTest {

    private AirportLookupService service;

    @BeforeEach
    void setUp() throws Exception {
        // Uses real airports.csv from project root
        service = new AirportLookupService("data/airports/airports.csv");
        service.loadAirports();
    }

    @Test
    void shouldLoadUsAirports() {
        assertThat(service.count()).isGreaterThan(100);
    }

    @Test
    void shouldFindLaxByIata() {
        Optional<Airport> lax = service.findByIata("LAX");
        assertThat(lax).isPresent();
        assertThat(lax.get().ident()).isEqualTo("KLAX");
    }

    @Test
    void shouldFindNearestAirportToLaxCoords() {
        // LAX coordinates
        Optional<Airport> nearest = service.findNearest(33.9425, -118.4081, 5.0);
        assertThat(nearest).isPresent();
        assertThat(nearest.get().iataCode()).isEqualTo("LAX");
    }

    @Test
    void shouldReturnEmptyWhenTooFarFromAnyAirport() {
        // Middle of the Pacific Ocean
        Optional<Airport> nearest = service.findNearest(30.0, -150.0, 5.0);
        assertThat(nearest).isEmpty();
    }

    @Test
    void shouldComputeHaversineDistanceLaxToSfo() {
        // LAX to SFO is approximately 543 km
        double dist = AirportLookupService.haversineKm(33.9425, -118.4081, 37.6213, -122.3790);
        assertThat(dist).isCloseTo(543.0, within(10.0));
    }

    @Test
    void shouldComputeHaversineDistanceZeroForSamePoint() {
        double dist = AirportLookupService.haversineKm(40.0, -74.0, 40.0, -74.0);
        assertThat(dist).isEqualTo(0.0);
    }

    @Test
    void shouldFindByIcaoCode() {
        Optional<Airport> ord = service.findByIcao("KORD");
        assertThat(ord).isPresent();
        assertThat(ord.get().iataCode()).isEqualTo("ORD");
    }

    @Test
    void shouldReturnEmptyForUnknownIata() {
        assertThat(service.findByIata("ZZZ")).isEmpty();
    }
}
```

### Step 2: Run tests to verify they fail

```bash
cd skytrack && ./mvnw test -pl . -Dtest="AirportTest,AirportLookupServiceTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — classes don't exist.

### Step 3: Create Airport record

```java
package skytrack.demo.model;

public record Airport(
        String ident,
        String icaoCode,
        String iataCode,
        String name,
        double latitude,
        double longitude,
        String type) {}
```

### Step 4: Create AirportLookupService

```java
package skytrack.demo.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import skytrack.demo.model.Airport;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class AirportLookupService {

    private static final Logger log = LoggerFactory.getLogger(AirportLookupService.class);
    private static final double EARTH_RADIUS_KM = 6371.0;

    private final Path csvPath;
    private List<Airport> airports = List.of();

    public AirportLookupService(
            @Value("${skytrack.airports.csv-path:data/airports/airports.csv}") String csvPath) {
        this.csvPath = Path.of(csvPath);
    }

    @PostConstruct
    void loadAirports() throws IOException {
        List<Airport> loaded = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                if (fields.length < 14) continue;
                String isoCountry = unquote(fields[8]);
                String type = unquote(fields[2]);
                if (!"US".equals(isoCountry)) continue;
                if (!"large_airport".equals(type) && !"medium_airport".equals(type)) continue;

                try {
                    loaded.add(new Airport(
                            unquote(fields[1]),   // ident
                            unquote(fields[12]),  // icao_code
                            unquote(fields[13]),  // iata_code
                            unquote(fields[3]),   // name
                            Double.parseDouble(unquote(fields[4])),  // latitude_deg
                            Double.parseDouble(unquote(fields[5])),  // longitude_deg
                            type
                    ));
                } catch (NumberFormatException e) {
                    log.warn("Skipping airport with invalid coordinates: {}", unquote(fields[1]));
                }
            }
        }
        this.airports = List.copyOf(loaded);
        log.info("Loaded {} US airports (large + medium)", airports.size());
    }

    public Optional<Airport> findNearest(double lat, double lon, double maxDistanceKm) {
        Airport nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Airport airport : airports) {
            double dist = haversineKm(lat, lon, airport.latitude(), airport.longitude());
            if (dist < minDist && dist <= maxDistanceKm) {
                minDist = dist;
                nearest = airport;
            }
        }
        return Optional.ofNullable(nearest);
    }

    public Optional<Airport> findByIcao(String icaoCode) {
        return airports.stream()
                .filter(a -> icaoCode.equals(a.icaoCode()) || icaoCode.equals(a.ident()))
                .findFirst();
    }

    public Optional<Airport> findByIata(String iataCode) {
        if (iataCode == null || iataCode.isBlank()) return Optional.empty();
        return airports.stream()
                .filter(a -> iataCode.equals(a.iataCode()))
                .findFirst();
    }

    public int count() {
        return airports.size();
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
```

### Step 5: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="AirportTest,AirportLookupServiceTest"
```

Expected: PASS

### Step 6: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/model/Airport.java \
  skytrack/src/main/java/skytrack/demo/service/AirportLookupService.java \
  skytrack/src/test/java/skytrack/demo/model/AirportTest.java \
  skytrack/src/test/java/skytrack/demo/service/AirportLookupServiceTest.java
git commit -m "feat: add Airport model and AirportLookupService with Haversine"
```

---

## Task 3: AircraftState, AircraftTrack & DynamoDB Repository

Create the DynamoDB entity for tracking aircraft state, and a repository for CRUD operations.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/model/AircraftState.java`
- Create: `skytrack/src/main/java/skytrack/demo/model/AircraftTrack.java`
- Create: `skytrack/src/main/java/skytrack/demo/repository/AircraftTrackRepository.java`
- Test: `skytrack/src/test/java/skytrack/demo/model/AircraftTrackTest.java`
- Test: `skytrack/src/test/java/skytrack/demo/repository/AircraftTrackRepositoryTest.java`

### Step 1: Write the failing tests

**`AircraftTrackTest.java`:**

```java
package skytrack.demo.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AircraftTrackTest {

    @Test
    void shouldBuildWithLombok() {
        var track = AircraftTrack.builder()
                .icao24("abc123")
                .sortKey("TRACK")
                .state("EN_ROUTE")
                .callsign("UAL1234")
                .latitude(41.9742)
                .longitude(-87.9073)
                .baroAltitude(10668.0)
                .build();

        assertThat(track.getIcao24()).isEqualTo("abc123");
        assertThat(track.getState()).isEqualTo("EN_ROUTE");
        assertThat(track.getCallsign()).isEqualTo("UAL1234");
    }

    @Test
    void shouldConvertAircraftStateEnum() {
        var track = AircraftTrack.builder()
                .icao24("abc123")
                .sortKey("TRACK")
                .state("EN_ROUTE")
                .build();

        assertThat(track.getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);

        track.setAircraftState(AircraftState.ON_GROUND);
        assertThat(track.getState()).isEqualTo("ON_GROUND");
        assertThat(track.getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
    }

    @Test
    void shouldCreateInitialTrack() {
        var track = AircraftTrack.initial("abc123");

        assertThat(track.getIcao24()).isEqualTo("abc123");
        assertThat(track.getSortKey()).isEqualTo("TRACK");
        assertThat(track.getAircraftState()).isEqualTo(AircraftState.UNKNOWN);
        assertThat(track.getStateEnteredAt()).isNotNull();
        assertThat(track.getUpdatedAt()).isNotNull();
        assertThat(track.getTtl()).isGreaterThan(track.getUpdatedAt());
    }

    @Test
    void shouldDefaultToUnknownWhenStateIsNull() {
        var track = new AircraftTrack();
        assertThat(track.getAircraftState()).isEqualTo(AircraftState.UNKNOWN);
    }
}
```

**`AircraftTrackRepositoryTest.java`** (integration test with LocalStack):

```java
package skytrack.demo.repository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import skytrack.demo.model.AircraftState;
import skytrack.demo.model.AircraftTrack;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AircraftTrackRepositoryTest {

    @SuppressWarnings("resource")
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    private static DynamoDbClient dynamoDbClient;
    private static AircraftTrackRepository repository;

    @BeforeAll
    static void setUpTable() {
        dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();

        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName("skytrack-aircraft")
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("icao24").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sortKey").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("icao24").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sortKey").keyType(KeyType.RANGE).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());

        var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
        DynamoDbTable<AircraftTrack> table = enhancedClient.table(
                "skytrack-aircraft", TableSchema.fromBean(AircraftTrack.class));
        repository = new AircraftTrackRepository(table);
    }

    @AfterAll
    static void tearDown() {
        if (dynamoDbClient != null) dynamoDbClient.close();
    }

    @Test
    void shouldSaveAndFindTrack() {
        var track = AircraftTrack.initial("test-save-find");
        track.setAircraftState(AircraftState.EN_ROUTE);
        track.setCallsign("UAL1234");
        track.setLatitude(41.9742);
        track.setLongitude(-87.9073);

        repository.save(track);
        Optional<AircraftTrack> found = repository.findByIcao24("test-save-find");

        assertThat(found).isPresent();
        assertThat(found.get().getCallsign()).isEqualTo("UAL1234");
        assertThat(found.get().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
        assertThat(found.get().getLatitude()).isEqualTo(41.9742);
    }

    @Test
    void shouldReturnEmptyForUnknownIcao24() {
        Optional<AircraftTrack> found = repository.findByIcao24("nonexistent-icao24");
        assertThat(found).isEmpty();
    }

    @Test
    void shouldOverwriteExistingTrack() {
        var track = AircraftTrack.initial("test-overwrite");
        track.setAircraftState(AircraftState.EN_ROUTE);
        repository.save(track);

        track.setAircraftState(AircraftState.ON_GROUND);
        track.setNearestAirportIcao("KORD");
        repository.save(track);

        var found = repository.findByIcao24("test-overwrite");
        assertThat(found).isPresent();
        assertThat(found.get().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(found.get().getNearestAirportIcao()).isEqualTo("KORD");
    }

    @Test
    void shouldDeleteTrack() {
        var track = AircraftTrack.initial("test-delete");
        repository.save(track);
        assertThat(repository.findByIcao24("test-delete")).isPresent();

        repository.delete("test-delete");
        assertThat(repository.findByIcao24("test-delete")).isEmpty();
    }

    @Test
    void shouldAutoSetUpdatedAtAndTtlOnSave() {
        var track = AircraftTrack.initial("test-timestamps");
        long beforeSave = System.currentTimeMillis() / 1000;
        repository.save(track);

        var found = repository.findByIcao24("test-timestamps").orElseThrow();
        assertThat(found.getUpdatedAt()).isGreaterThanOrEqualTo(beforeSave);
        assertThat(found.getTtl()).isGreaterThan(found.getUpdatedAt());
    }
}
```

### Step 2: Run tests to verify they fail

```bash
cd skytrack && ./mvnw test -pl . -Dtest="AircraftTrackTest,AircraftTrackRepositoryTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — classes don't exist.

### Step 3: Create AircraftState enum

```java
package skytrack.demo.model;

public enum AircraftState {
    UNKNOWN,
    EN_ROUTE,
    APPROACHING,
    ON_GROUND,
    DEPARTED
}
```

### Step 4: Create AircraftTrack entity

```java
package skytrack.demo.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@DynamoDbBean
public class AircraftTrack {

    private String icao24;
    private String sortKey;
    private String state;
    private String callsign;
    private Double latitude;
    private Double longitude;
    private Double baroAltitude;
    private Long lastSeen;
    private String nearestAirportIcao;
    private Long stateEnteredAt;
    private Long updatedAt;
    private Long ttl;

    @DynamoDbPartitionKey
    public String getIcao24() { return icao24; }

    @DynamoDbSortKey
    public String getSortKey() { return sortKey; }

    public AircraftState getAircraftState() {
        return state != null ? AircraftState.valueOf(state) : AircraftState.UNKNOWN;
    }

    public void setAircraftState(AircraftState aircraftState) {
        this.state = aircraftState.name();
    }

    public static AircraftTrack initial(String icao24) {
        long now = Instant.now().getEpochSecond();
        return AircraftTrack.builder()
                .icao24(icao24)
                .sortKey("TRACK")
                .state(AircraftState.UNKNOWN.name())
                .stateEnteredAt(now)
                .updatedAt(now)
                .ttl(now + 86400)
                .build();
    }
}
```

### Step 5: Create AircraftTrackRepository

```java
package skytrack.demo.repository;

import org.springframework.stereotype.Repository;
import skytrack.demo.model.AircraftTrack;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.time.Instant;
import java.util.Optional;

@Repository
public class AircraftTrackRepository {

    private final DynamoDbTable<AircraftTrack> table;

    public AircraftTrackRepository(DynamoDbTable<AircraftTrack> table) {
        this.table = table;
    }

    public Optional<AircraftTrack> findByIcao24(String icao24) {
        AircraftTrack track = table.getItem(Key.builder()
                .partitionValue(icao24)
                .sortValue("TRACK")
                .build());
        return Optional.ofNullable(track);
    }

    public void save(AircraftTrack track) {
        long now = Instant.now().getEpochSecond();
        track.setUpdatedAt(now);
        track.setTtl(now + 86400);
        table.putItem(track);
    }

    public void delete(String icao24) {
        table.deleteItem(Key.builder()
                .partitionValue(icao24)
                .sortValue("TRACK")
                .build());
    }
}
```

### Step 6: Wire DynamoDbTable bean in DynamoDbConfig

Add this bean to `DynamoDbConfig.java`:

```java
@Bean
public DynamoDbTable<AircraftTrack> aircraftTrackTable(
        DynamoDbEnhancedClient enhancedClient, DynamoDbProperties props) {
    return enhancedClient.table(props.tableName(), TableSchema.fromBean(AircraftTrack.class));
}
```

Add the import: `import skytrack.demo.model.AircraftTrack;` and `import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;` and `import software.amazon.awssdk.enhanced.dynamodb.TableSchema;`.

### Step 7: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="AircraftTrackTest,AircraftTrackRepositoryTest"
```

Expected: PASS

### Step 8: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/model/AircraftState.java \
  skytrack/src/main/java/skytrack/demo/model/AircraftTrack.java \
  skytrack/src/main/java/skytrack/demo/repository/AircraftTrackRepository.java \
  skytrack/src/main/java/skytrack/demo/config/DynamoDbConfig.java \
  skytrack/src/test/java/skytrack/demo/model/AircraftTrackTest.java \
  skytrack/src/test/java/skytrack/demo/repository/AircraftTrackRepositoryTest.java
git commit -m "feat: add AircraftTrack DynamoDB entity and repository"
```

---

## Task 4: Callsign Parser

Parse ICAO-format callsigns (e.g., `UAL1234` → carrier `UAL`/`UA`, flight `1234`).

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/model/ParsedCallsign.java`
- Create: `skytrack/src/main/java/skytrack/demo/service/CallsignParser.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/CallsignParserTest.java`

### Step 1: Write the failing test

**`CallsignParserTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.ParsedCallsign;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CallsignParserTest {

    private final CallsignParser parser = new CallsignParser();

    @Test
    void shouldParseUnitedCallsign() {
        Optional<ParsedCallsign> result = parser.parse("UAL1234");
        assertThat(result).isPresent();
        assertThat(result.get().icaoCarrierCode()).isEqualTo("UAL");
        assertThat(result.get().flightNumber()).isEqualTo("1234");
        assertThat(result.get().iataCarrierCode()).isEqualTo("UA");
    }

    @Test
    void shouldParseDeltaCallsign() {
        Optional<ParsedCallsign> result = parser.parse("DAL567");
        assertThat(result).isPresent();
        assertThat(result.get().icaoCarrierCode()).isEqualTo("DAL");
        assertThat(result.get().iataCarrierCode()).isEqualTo("DL");
    }

    @Test
    void shouldParseAmericanCallsign() {
        Optional<ParsedCallsign> result = parser.parse("AAL100");
        assertThat(result).isPresent();
        assertThat(result.get().icaoCarrierCode()).isEqualTo("AAL");
        assertThat(result.get().iataCarrierCode()).isEqualTo("AA");
    }

    @Test
    void shouldHandleCallsignWithWhitespace() {
        Optional<ParsedCallsign> result = parser.parse("  UAL1234  ");
        assertThat(result).isPresent();
        assertThat(result.get().icaoCarrierCode()).isEqualTo("UAL");
    }

    @Test
    void shouldReturnEmptyForNull() {
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void shouldReturnEmptyForBlank() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
    }

    @Test
    void shouldReturnEmptyForInvalidFormat() {
        assertThat(parser.parse("UA1234")).isEmpty();  // only 2 letters
        assertThat(parser.parse("UALA")).isEmpty();    // no digits
        assertThat(parser.parse("12345")).isEmpty();   // all digits
    }

    @Test
    void shouldReturnEmptyForUnknownCarrier() {
        assertThat(parser.parse("ZZZ999")).isEmpty();
    }
}
```

### Step 2: Run test to verify it fails

```bash
cd skytrack && ./mvnw test -pl . -Dtest="CallsignParserTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL

### Step 3: Create ParsedCallsign record

```java
package skytrack.demo.model;

public record ParsedCallsign(
        String icaoCarrierCode,
        String flightNumber,
        String iataCarrierCode) {}
```

### Step 4: Create CallsignParser

```java
package skytrack.demo.service;

import org.springframework.stereotype.Component;
import skytrack.demo.model.ParsedCallsign;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CallsignParser {

    private static final Pattern CALLSIGN_PATTERN = Pattern.compile("^([A-Z]{3})(\\d+)$");

    private static final Map<String, String> ICAO_TO_IATA = Map.ofEntries(
            Map.entry("UAL", "UA"),
            Map.entry("AAL", "AA"),
            Map.entry("DAL", "DL"),
            Map.entry("SWA", "WN"),
            Map.entry("JBU", "B6"),
            Map.entry("ASA", "AS"),
            Map.entry("NKS", "NK"),
            Map.entry("FFT", "F9"),
            Map.entry("SKW", "OO"),
            Map.entry("RPA", "YX"),
            Map.entry("ENY", "MQ"),
            Map.entry("HAL", "HA"),
            Map.entry("ACA", "AC"),
            Map.entry("WJA", "WS"),
            Map.entry("FDX", "FX"),
            Map.entry("UPS", "5X")
    );

    public Optional<ParsedCallsign> parse(String callsign) {
        if (callsign == null || callsign.isBlank()) return Optional.empty();
        Matcher matcher = CALLSIGN_PATTERN.matcher(callsign.trim().toUpperCase());
        if (!matcher.matches()) return Optional.empty();

        String icaoCode = matcher.group(1);
        String flightNumber = matcher.group(2);
        String iataCode = ICAO_TO_IATA.get(icaoCode);
        if (iataCode == null) return Optional.empty();

        return Optional.of(new ParsedCallsign(icaoCode, flightNumber, iataCode));
    }
}
```

### Step 5: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="CallsignParserTest"
```

Expected: PASS

### Step 6: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/model/ParsedCallsign.java \
  skytrack/src/main/java/skytrack/demo/service/CallsignParser.java \
  skytrack/src/test/java/skytrack/demo/service/CallsignParserTest.java
git commit -m "feat: add CallsignParser for ICAO callsign parsing"
```

---

## Task 5: Aircraft State Machine

Standalone class that computes state transitions from FlightPosition updates. Emits `LandingEvent` when an aircraft transitions to ON_GROUND.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/model/LandingEvent.java`
- Create: `skytrack/src/main/java/skytrack/demo/model/StateTransitionResult.java`
- Create: `skytrack/src/main/java/skytrack/demo/config/StateMachineProperties.java`
- Create: `skytrack/src/main/java/skytrack/demo/service/AircraftStateMachine.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/AircraftStateMachineTest.java`

### Step 1: Write the failing test

**`AircraftStateMachineTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AircraftStateMachineTest {

    private AircraftStateMachine stateMachine;
    private AirportLookupService airportLookup;

    // ORD coords and test airport
    private static final Airport ORD = new Airport(
            "KORD", "KORD", "ORD", "O'Hare International Airport",
            41.9742, -87.9073, "large_airport");

    @BeforeEach
    void setUp() {
        airportLookup = mock(AirportLookupService.class);
        var props = new StateMachineProperties(150.0, 50.0, 5.0, 300);
        stateMachine = new AircraftStateMachine(airportLookup, props);
    }

    private FlightPosition position(String callsign, double lat, double lon,
                                     double alt, boolean onGround, long time) {
        return new FlightPosition("abc123", callsign, lat, lon, alt,
                230.0, 270.0, onGround, time, time - 5, Instant.ofEpochSecond(time));
    }

    @Test
    void shouldTransitionFromUnknownToEnRouteWhenAirborne() {
        var track = AircraftTrack.initial("abc123");
        var pos = position("UAL1234", 41.0, -88.0, 10000.0, false, 1000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.empty());
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldTransitionFromEnRouteToOnGroundAndEmitLandingEvent() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.EN_ROUTE);
        track.setBaroAltitude(3000.0);
        track.setLastSeen(900L);

        var pos = position("UAL1234", 41.9742, -87.9073, 0.0, true, 1000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.of(ORD));

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(result.landingEvent()).isPresent();

        LandingEvent event = result.landingEvent().get();
        assertThat(event.icao24()).isEqualTo("abc123");
        assertThat(event.callsign()).isEqualTo("UAL1234");
        assertThat(event.arrivalAirportIcao()).isEqualTo("KORD");
        assertThat(event.arrivalAirportIata()).isEqualTo("ORD");
    }

    @Test
    void shouldTransitionFromOnGroundToDeparted() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.ON_GROUND);
        track.setBaroAltitude(0.0);
        track.setLastSeen(900L);

        var pos = position("UAL1234", 41.9742, -87.9073, 500.0, false, 1000L);

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.DEPARTED);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldNotEmitDuplicateLandingEventWhenStayingOnGround() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.ON_GROUND);
        track.setBaroAltitude(0.0);
        track.setLastSeen(900L);

        var pos = position("UAL1234", 41.9742, -87.9073, 0.0, true, 1000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.of(ORD));

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldTransitionToApproachingWhenDescendingNearAirport() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.EN_ROUTE);
        track.setBaroAltitude(5000.0);
        track.setLastSeen(900L);

        // Position at 3000m, descending, within approach radius
        var pos = position("UAL1234", 42.1, -87.8, 3000.0, false, 1000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.of(ORD));
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.APPROACHING);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldResetToUnknownOnStaleTimeout() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.EN_ROUTE);
        track.setLastSeen(100L);

        // Position arrives 400 seconds after last seen (> 300s timeout)
        var pos = position("UAL1234", 41.0, -88.0, 10000.0, false, 500L);

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.UNKNOWN);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldTransitionFromDepartedToEnRoute() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.DEPARTED);
        track.setBaroAltitude(500.0);
        track.setLastSeen(900L);

        var pos = position("UAL1234", 41.0, -88.0, 3000.0, false, 1000L);

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
    }

    @Test
    void shouldHandleFullFlightSequence() {
        var track = AircraftTrack.initial("abc123");
        long t = 1000L;

        // 1. UNKNOWN → EN_ROUTE (airborne, far from airports)
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.empty());
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var r1 = stateMachine.process(track, position("UAL1234", 40.0, -90.0, 10000.0, false, t));
        assertThat(r1.updatedTrack().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
        track = r1.updatedTrack();
        t += 100;

        // 2. EN_ROUTE → APPROACHING (descending near ORD)
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.of(ORD));
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var r2 = stateMachine.process(track, position("UAL1234", 42.1, -87.8, 3000.0, false, t));
        assertThat(r2.updatedTrack().getAircraftState()).isEqualTo(AircraftState.APPROACHING);
        track = r2.updatedTrack();
        t += 100;

        // 3. APPROACHING → ON_GROUND (landed at ORD)
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.of(ORD));

        var r3 = stateMachine.process(track, position("UAL1234", 41.9742, -87.9073, 0.0, true, t));
        assertThat(r3.updatedTrack().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(r3.landingEvent()).isPresent();
        assertThat(r3.landingEvent().get().arrivalAirportIata()).isEqualTo("ORD");
        track = r3.updatedTrack();
        t += 100;

        // 4. ON_GROUND → DEPARTED (taking off)
        var r4 = stateMachine.process(track, position("UAL1234", 41.9742, -87.9073, 500.0, false, t));
        assertThat(r4.updatedTrack().getAircraftState()).isEqualTo(AircraftState.DEPARTED);
        track = r4.updatedTrack();
        t += 100;

        // 5. DEPARTED → EN_ROUTE (climbing)
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.empty());
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var r5 = stateMachine.process(track, position("UAL1234", 41.0, -88.0, 5000.0, false, t));
        assertThat(r5.updatedTrack().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
    }

    @Test
    void shouldNotEmitLandingForUnknownToOnGround() {
        // First-ever position is on the ground — we didn't observe the landing
        var track = AircraftTrack.initial("abc123");
        var pos = position("UAL1234", 41.9742, -87.9073, 0.0, true, 1000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.of(ORD));

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldUpdateTrackFieldsOnEveryPosition() {
        var track = AircraftTrack.initial("abc123");
        var pos = position("UAL1234", 41.5, -88.5, 10000.0, false, 2000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.empty());
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var result = stateMachine.process(track, pos);
        var updated = result.updatedTrack();

        assertThat(updated.getCallsign()).isEqualTo("UAL1234");
        assertThat(updated.getLatitude()).isEqualTo(41.5);
        assertThat(updated.getLongitude()).isEqualTo(-88.5);
        assertThat(updated.getBaroAltitude()).isEqualTo(10000.0);
        assertThat(updated.getLastSeen()).isEqualTo(2000L);
    }
}
```

### Step 2: Run tests to verify they fail

```bash
cd skytrack && ./mvnw test -pl . -Dtest="AircraftStateMachineTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL

### Step 3: Create model records

**`LandingEvent.java`:**

```java
package skytrack.demo.model;

public record LandingEvent(
        String icao24,
        String callsign,
        String arrivalAirportIcao,
        String arrivalAirportIata,
        long arrivalTime,
        double latitude,
        double longitude) {}
```

**`StateTransitionResult.java`:**

```java
package skytrack.demo.model;

import java.util.Optional;

public record StateTransitionResult(
        AircraftTrack updatedTrack,
        Optional<LandingEvent> landingEvent) {}
```

### Step 4: Create StateMachineProperties

```java
package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.state-machine")
public record StateMachineProperties(
        double groundAltitudeMeters,
        double approachRadiusKm,
        double groundRadiusKm,
        long staleTimeoutSeconds) {}
```

Add to `application.yml` under the existing `skytrack:` block:

```yaml
skytrack:
  state-machine:
    ground-altitude-meters: 150
    approach-radius-km: 50
    ground-radius-km: 5
    stale-timeout-seconds: 300
```

### Step 5: Create AircraftStateMachine

```java
package skytrack.demo.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.*;

import java.util.Optional;

@Component
@EnableConfigurationProperties(StateMachineProperties.class)
public class AircraftStateMachine {

    private final AirportLookupService airportLookup;
    private final StateMachineProperties props;

    public AircraftStateMachine(AirportLookupService airportLookup, StateMachineProperties props) {
        this.airportLookup = airportLookup;
        this.props = props;
    }

    public StateTransitionResult process(AircraftTrack track, FlightPosition position) {
        AircraftState currentState = track.getAircraftState();

        // Check for stale timeout
        if (track.getLastSeen() != null
                && (position.lastContact() - track.getLastSeen()) > props.staleTimeoutSeconds()) {
            updateTrackFields(track, position);
            track.setAircraftState(AircraftState.UNKNOWN);
            track.setStateEnteredAt(position.lastContact());
            track.setNearestAirportIcao(null);
            return new StateTransitionResult(track, Optional.empty());
        }

        Optional<Airport> groundAirport = airportLookup.findNearest(
                position.latitude(), position.longitude(), props.groundRadiusKm());

        AircraftState newState = currentState;
        Optional<LandingEvent> landingEvent = Optional.empty();

        switch (currentState) {
            case UNKNOWN -> {
                if (position.onGround() && groundAirport.isPresent()) {
                    // First position is on ground — set state but don't emit landing
                    newState = AircraftState.ON_GROUND;
                    track.setNearestAirportIcao(groundAirport.get().icaoCode());
                } else if (!position.onGround()) {
                    newState = AircraftState.EN_ROUTE;
                }
            }
            case EN_ROUTE -> {
                if (position.onGround() && groundAirport.isPresent()) {
                    newState = AircraftState.ON_GROUND;
                    track.setNearestAirportIcao(groundAirport.get().icaoCode());
                    landingEvent = Optional.of(createLandingEvent(position, groundAirport.get()));
                } else {
                    Optional<Airport> approachAirport = airportLookup.findNearest(
                            position.latitude(), position.longitude(), props.approachRadiusKm());
                    if (approachAirport.isPresent() && isDescending(track, position)) {
                        newState = AircraftState.APPROACHING;
                        track.setNearestAirportIcao(approachAirport.get().icaoCode());
                    }
                }
            }
            case APPROACHING -> {
                if (position.onGround() && groundAirport.isPresent()) {
                    newState = AircraftState.ON_GROUND;
                    track.setNearestAirportIcao(groundAirport.get().icaoCode());
                    landingEvent = Optional.of(createLandingEvent(position, groundAirport.get()));
                } else {
                    Optional<Airport> approachAirport = airportLookup.findNearest(
                            position.latitude(), position.longitude(), props.approachRadiusKm());
                    if (approachAirport.isEmpty() || isClimbing(track, position)) {
                        newState = AircraftState.EN_ROUTE;
                        track.setNearestAirportIcao(null);
                    }
                }
            }
            case ON_GROUND -> {
                if (!position.onGround()) {
                    newState = AircraftState.DEPARTED;
                    track.setNearestAirportIcao(null);
                }
            }
            case DEPARTED -> {
                if (!position.onGround()) {
                    newState = AircraftState.EN_ROUTE;
                }
            }
        }

        // Update state if changed
        if (newState != currentState) {
            track.setAircraftState(newState);
            track.setStateEnteredAt(position.lastContact());
        }

        updateTrackFields(track, position);
        return new StateTransitionResult(track, landingEvent);
    }

    private void updateTrackFields(AircraftTrack track, FlightPosition position) {
        track.setCallsign(position.callsign());
        track.setLatitude(position.latitude());
        track.setLongitude(position.longitude());
        track.setBaroAltitude(position.baroAltitude());
        track.setLastSeen(position.lastContact());
    }

    private boolean isDescending(AircraftTrack track, FlightPosition position) {
        if (track.getBaroAltitude() == null || position.baroAltitude() == null) return false;
        return position.baroAltitude() < track.getBaroAltitude();
    }

    private boolean isClimbing(AircraftTrack track, FlightPosition position) {
        if (track.getBaroAltitude() == null || position.baroAltitude() == null) return false;
        return position.baroAltitude() > track.getBaroAltitude() + 100;
    }

    private LandingEvent createLandingEvent(FlightPosition position, Airport airport) {
        return new LandingEvent(
                position.icao24(),
                position.callsign(),
                airport.icaoCode() != null ? airport.icaoCode() : airport.ident(),
                airport.iataCode(),
                position.lastContact(),
                position.latitude(),
                position.longitude());
    }
}
```

### Step 6: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="AircraftStateMachineTest"
```

Expected: PASS

### Step 7: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/model/LandingEvent.java \
  skytrack/src/main/java/skytrack/demo/model/StateTransitionResult.java \
  skytrack/src/main/java/skytrack/demo/config/StateMachineProperties.java \
  skytrack/src/main/java/skytrack/demo/service/AircraftStateMachine.java \
  skytrack/src/main/resources/application.yml \
  skytrack/src/test/java/skytrack/demo/service/AircraftStateMachineTest.java
git commit -m "feat: add AircraftStateMachine with state transitions and landing detection"
```

---

## Task 6: Route Average Estimator

In-memory running average of delay by carrier+airport from resolved AeroAPI responses. Provides fallback when AeroAPI is unavailable.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/RouteAverageEstimator.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/RouteAverageEstimatorTest.java`

### Step 1: Write the failing test

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.FlightSchedule;

import java.time.Instant;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RouteAverageEstimatorTest {

    private final RouteAverageEstimator estimator = new RouteAverageEstimator();

    @Test
    void shouldReturnEmptyWhenNoDataRecorded() {
        OptionalDouble result = estimator.estimateDelaySeconds("UAL", "LAX");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWithInsufficientObservations() {
        // Record only 2 observations (below minimum of 3)
        recordSchedule("UAL", "LAX", 600);
        recordSchedule("UAL", "LAX", 900);

        assertThat(estimator.estimateDelaySeconds("UAL", "LAX")).isEmpty();
    }

    @Test
    void shouldReturnAverageWithSufficientObservations() {
        recordSchedule("UAL", "LAX", 600);
        recordSchedule("UAL", "LAX", 900);
        recordSchedule("UAL", "LAX", 300);

        OptionalDouble result = estimator.estimateDelaySeconds("UAL", "LAX");
        assertThat(result).isPresent();
        assertThat(result.getAsDouble()).isCloseTo(600.0, within(0.01));
    }

    @Test
    void shouldKeySeparatelyByCarrierAndAirport() {
        recordSchedule("UAL", "LAX", 600);
        recordSchedule("UAL", "LAX", 600);
        recordSchedule("UAL", "LAX", 600);

        recordSchedule("DAL", "LAX", 1200);
        recordSchedule("DAL", "LAX", 1200);
        recordSchedule("DAL", "LAX", 1200);

        assertThat(estimator.estimateDelaySeconds("UAL", "LAX").getAsDouble())
                .isCloseTo(600.0, within(0.01));
        assertThat(estimator.estimateDelaySeconds("DAL", "LAX").getAsDouble())
                .isCloseTo(1200.0, within(0.01));
    }

    @Test
    void shouldSkipSchedulesWithMissingTimestamps() {
        var schedule = new FlightSchedule("UAL1234", "UA1234", "UAL", "ORD", "LAX",
                null, null, null, null, null, null, "B738");
        estimator.record(schedule);

        assertThat(estimator.estimateDelaySeconds("UAL", "LAX")).isEmpty();
    }

    private void recordSchedule(String airline, String destination, long delaySeconds) {
        Instant scheduledArrival = Instant.parse("2026-03-15T16:00:00Z");
        Instant actualArrival = scheduledArrival.plusSeconds(delaySeconds);
        var schedule = new FlightSchedule("call", "fn", airline, "ORD", destination,
                Instant.parse("2026-03-15T14:00:00Z"), scheduledArrival,
                null, actualArrival, null, null, "B738");
        estimator.record(schedule);
    }
}
```

### Step 2: Run test to verify it fails

```bash
cd skytrack && ./mvnw test -pl . -Dtest="RouteAverageEstimatorTest" -Dsurefire.failIfNoSpecifiedTests=false
```

### Step 3: Create RouteAverageEstimator

```java
package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.FlightSchedule;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RouteAverageEstimator {

    private static final int MIN_OBSERVATIONS = 3;

    // Key: "ICAO_CARRIER:IATA_AIRPORT" e.g. "UAL:LAX"
    private final Map<String, RunningAverage> averages = new ConcurrentHashMap<>();

    public void record(FlightSchedule schedule) {
        if (schedule.scheduledArrival() == null || schedule.actualArrival() == null) return;
        if (schedule.destination() == null || schedule.airline() == null) return;

        long delaySeconds = schedule.actualArrival().getEpochSecond()
                - schedule.scheduledArrival().getEpochSecond();
        String key = schedule.airline() + ":" + schedule.destination();
        averages.computeIfAbsent(key, k -> new RunningAverage()).add(delaySeconds);
    }

    public OptionalDouble estimateDelaySeconds(String carrierIcao, String airportIata) {
        String key = carrierIcao + ":" + airportIata;
        RunningAverage avg = averages.get(key);
        if (avg == null || avg.count() < MIN_OBSERVATIONS) return OptionalDouble.empty();
        return OptionalDouble.of(avg.average());
    }

    private static class RunningAverage {
        private final AtomicLong sum = new AtomicLong(0);
        private final AtomicInteger count = new AtomicInteger(0);

        void add(long value) {
            sum.addAndGet(value);
            count.incrementAndGet();
        }

        int count() { return count.get(); }
        double average() { return (double) sum.get() / count.get(); }
    }
}
```

### Step 4: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="RouteAverageEstimatorTest"
```

Expected: PASS

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/service/RouteAverageEstimator.java \
  skytrack/src/test/java/skytrack/demo/service/RouteAverageEstimatorTest.java
git commit -m "feat: add RouteAverageEstimator for fallback delay estimation"
```

---

## Task 7: Schedule Resolver

Coordinates landing events with AeroAPI lookup and route-average fallback. Returns a `ResolvedArrival` with delay and resolution method.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/model/ResolvedArrival.java`
- Create: `skytrack/src/main/java/skytrack/demo/service/ScheduleResolver.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/ScheduleResolverTest.java`

### Step 1: Write the failing test

```java
package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.model.FlightSchedule;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.model.ResolvedArrival;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleResolverTest {

    @Mock private FlightScheduleApiClient apiClient;
    private CallsignParser callsignParser;
    @Mock private RouteAverageEstimator routeAverageEstimator;

    private ScheduleResolver resolver;

    @BeforeEach
    void setUp() {
        callsignParser = new CallsignParser(); // use real parser
        resolver = new ScheduleResolver(apiClient, callsignParser, routeAverageEstimator);
    }

    private LandingEvent landingEvent(String callsign, long arrivalTime) {
        return new LandingEvent("abc123", callsign, "KORD", "ORD",
                arrivalTime, 41.9742, -87.9073);
    }

    @Test
    void shouldResolveViaAeroApi() {
        long scheduledArrival = Instant.parse("2026-03-15T16:30:00Z").getEpochSecond();
        long actualArrival = Instant.parse("2026-03-15T16:45:00Z").getEpochSecond();

        var schedule = new FlightSchedule("UAL1234", "UA1234", "UAL", "ORD", "LAX",
                Instant.parse("2026-03-15T14:00:00Z"),
                Instant.ofEpochSecond(scheduledArrival),
                null,
                Instant.ofEpochSecond(actualArrival),
                null, null, "B738");

        when(apiClient.getFlightSchedule(eq("UAL1234"), anyString()))
                .thenReturn(Optional.of(schedule));

        ResolvedArrival result = resolver.resolve(landingEvent("UAL1234", actualArrival));

        assertThat(result.resolutionMethod()).isEqualTo("AEROAPI");
        assertThat(result.carrierCode()).isEqualTo("UA");
        assertThat(result.flightNumber()).isEqualTo("1234");
        assertThat(result.scheduledArrivalTime()).isEqualTo(scheduledArrival);
        assertThat(result.delaySeconds()).isEqualTo(actualArrival - scheduledArrival);
        verify(routeAverageEstimator).record(schedule);
    }

    @Test
    void shouldFallBackToRouteAverageWhenApiReturnsEmpty() {
        when(apiClient.getFlightSchedule(eq("UAL1234"), anyString()))
                .thenReturn(Optional.empty());
        when(routeAverageEstimator.estimateDelaySeconds("UAL", "ORD"))
                .thenReturn(OptionalDouble.of(900.0));

        long arrivalTime = Instant.parse("2026-03-15T16:45:00Z").getEpochSecond();
        ResolvedArrival result = resolver.resolve(landingEvent("UAL1234", arrivalTime));

        assertThat(result.resolutionMethod()).isEqualTo("ROUTE_AVERAGE");
        assertThat(result.delaySeconds()).isEqualTo(900L);
    }

    @Test
    void shouldReturnUnresolvedWhenCallsignUnparseable() {
        long arrivalTime = Instant.parse("2026-03-15T16:45:00Z").getEpochSecond();
        ResolvedArrival result = resolver.resolve(landingEvent("UNKNOWN", arrivalTime));

        assertThat(result.resolutionMethod()).isEqualTo("UNRESOLVED");
        assertThat(result.carrierCode()).isNull();
        assertThat(result.delaySeconds()).isNull();
        verify(apiClient, never()).getFlightSchedule(anyString(), anyString());
    }

    @Test
    void shouldReturnUnresolvedWhenBothAeroApiAndRouteAverageFail() {
        when(apiClient.getFlightSchedule(eq("DAL567"), anyString()))
                .thenReturn(Optional.empty());
        when(routeAverageEstimator.estimateDelaySeconds("DAL", "ORD"))
                .thenReturn(OptionalDouble.empty());

        long arrivalTime = Instant.parse("2026-03-15T16:45:00Z").getEpochSecond();
        ResolvedArrival result = resolver.resolve(landingEvent("DAL567", arrivalTime));

        assertThat(result.resolutionMethod()).isEqualTo("UNRESOLVED");
    }

    @Test
    void shouldHandleApiException() {
        when(apiClient.getFlightSchedule(eq("UAL1234"), anyString()))
                .thenThrow(new RuntimeException("API error"));
        when(routeAverageEstimator.estimateDelaySeconds("UAL", "ORD"))
                .thenReturn(OptionalDouble.empty());

        long arrivalTime = Instant.parse("2026-03-15T16:45:00Z").getEpochSecond();
        ResolvedArrival result = resolver.resolve(landingEvent("UAL1234", arrivalTime));

        assertThat(result.resolutionMethod()).isEqualTo("UNRESOLVED");
    }
}
```

### Step 2: Run test to verify it fails

```bash
cd skytrack && ./mvnw test -pl . -Dtest="ScheduleResolverTest" -Dsurefire.failIfNoSpecifiedTests=false
```

### Step 3: Create ResolvedArrival record

```java
package skytrack.demo.model;

public record ResolvedArrival(
        String icao24,
        String callsign,
        String carrierCode,
        String flightNumber,
        String arrivalAirportIcao,
        String arrivalAirportIata,
        long actualArrivalTime,
        Long scheduledArrivalTime,
        Long delaySeconds,
        String resolutionMethod) {}
```

### Step 4: Create ScheduleResolver

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.model.ResolvedArrival;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class ScheduleResolver {

    private static final Logger log = LoggerFactory.getLogger(ScheduleResolver.class);

    private final FlightScheduleApiClient apiClient;
    private final CallsignParser callsignParser;
    private final RouteAverageEstimator routeAverageEstimator;

    public ScheduleResolver(FlightScheduleApiClient apiClient,
                            CallsignParser callsignParser,
                            RouteAverageEstimator routeAverageEstimator) {
        this.apiClient = apiClient;
        this.callsignParser = callsignParser;
        this.routeAverageEstimator = routeAverageEstimator;
    }

    public ResolvedArrival resolve(LandingEvent event) {
        var parsed = callsignParser.parse(event.callsign());
        if (parsed.isEmpty()) {
            log.info("Could not parse callsign '{}' for icao24={}", event.callsign(), event.icao24());
            return unresolved(event);
        }

        var callsign = parsed.get();
        String date = LocalDate.ofInstant(
                Instant.ofEpochSecond(event.arrivalTime()), ZoneOffset.UTC).toString();

        // Try AeroAPI
        try {
            var schedule = apiClient.getFlightSchedule(event.callsign(), date);
            if (schedule.isPresent()) {
                var sched = schedule.get();
                Long scheduledArrival = sched.scheduledArrival() != null
                        ? sched.scheduledArrival().getEpochSecond() : null;
                Long delay = scheduledArrival != null
                        ? event.arrivalTime() - scheduledArrival : null;

                routeAverageEstimator.record(sched);

                log.info("Resolved {} via AeroAPI: delay={}s at {}",
                        event.callsign(), delay, event.arrivalAirportIata());
                return new ResolvedArrival(
                        event.icao24(), event.callsign(),
                        callsign.iataCarrierCode(), callsign.flightNumber(),
                        event.arrivalAirportIcao(), event.arrivalAirportIata(),
                        event.arrivalTime(), scheduledArrival, delay, "AEROAPI");
            }
        } catch (Exception e) {
            log.warn("AeroAPI lookup failed for {}: {}", event.callsign(), e.getMessage());
        }

        // Try route average
        var avgDelay = routeAverageEstimator.estimateDelaySeconds(
                callsign.icaoCarrierCode(), event.arrivalAirportIata());
        if (avgDelay.isPresent()) {
            long estimated = (long) avgDelay.getAsDouble();
            log.info("Resolved {} via route average: estimated delay={}s at {}",
                    event.callsign(), estimated, event.arrivalAirportIata());
            return new ResolvedArrival(
                    event.icao24(), event.callsign(),
                    callsign.iataCarrierCode(), callsign.flightNumber(),
                    event.arrivalAirportIcao(), event.arrivalAirportIata(),
                    event.arrivalTime(), null, estimated, "ROUTE_AVERAGE");
        }

        log.info("Could not resolve schedule for {} at {}",
                event.callsign(), event.arrivalAirportIata());
        return unresolved(event);
    }

    private ResolvedArrival unresolved(LandingEvent event) {
        return new ResolvedArrival(
                event.icao24(), event.callsign(),
                null, null,
                event.arrivalAirportIcao(), event.arrivalAirportIata(),
                event.arrivalTime(), null, null, "UNRESOLVED");
    }
}
```

### Step 5: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="ScheduleResolverTest"
```

Expected: PASS

### Step 6: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/model/ResolvedArrival.java \
  skytrack/src/main/java/skytrack/demo/service/ScheduleResolver.java \
  skytrack/src/test/java/skytrack/demo/service/ScheduleResolverTest.java
git commit -m "feat: add ScheduleResolver with AeroAPI + route-average fallback"
```

---

## Task 8: StatefulFlightPositionHandler

Replaces `LoggingFlightPositionHandler` as the primary `FlightPositionHandler`. Wires the state machine, repository, and schedule resolver together.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/StatefulFlightPositionHandler.java`
- Modify: `skytrack/src/main/java/skytrack/demo/service/LoggingFlightPositionHandler.java` (remove `@Component`)
- Test: `skytrack/src/test/java/skytrack/demo/service/StatefulFlightPositionHandlerTest.java`

### Step 1: Write the failing test

```java
package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.model.*;
import skytrack.demo.repository.AircraftTrackRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatefulFlightPositionHandlerTest {

    @Mock private AircraftTrackRepository repository;
    @Mock private AircraftStateMachine stateMachine;
    @Mock private ScheduleResolver scheduleResolver;

    private StatefulFlightPositionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StatefulFlightPositionHandler(repository, stateMachine, scheduleResolver);
    }

    private FlightPosition position(String icao24, String callsign) {
        return new FlightPosition(icao24, callsign, 41.9742, -87.9073,
                10668.0, 230.0, 270.0, false,
                1709312400L, 1709312400L, Instant.parse("2026-03-01T00:00:00Z"));
    }

    @Test
    void shouldLoadTrackProcessAndSave() {
        var existingTrack = AircraftTrack.initial("abc123");
        when(repository.findByIcao24("abc123")).thenReturn(Optional.of(existingTrack));

        var result = new StateTransitionResult(existingTrack, Optional.empty());
        when(stateMachine.process(eq(existingTrack), any(FlightPosition.class))).thenReturn(result);

        handler.handle(List.of(position("abc123", "UAL1234")));

        verify(repository).findByIcao24("abc123");
        verify(stateMachine).process(eq(existingTrack), any());
        verify(repository).save(existingTrack);
        verify(scheduleResolver, never()).resolve(any());
    }

    @Test
    void shouldCreateInitialTrackForNewAircraft() {
        when(repository.findByIcao24("new123")).thenReturn(Optional.empty());

        var newTrack = AircraftTrack.initial("new123");
        var result = new StateTransitionResult(newTrack, Optional.empty());
        when(stateMachine.process(any(AircraftTrack.class), any(FlightPosition.class))).thenReturn(result);

        handler.handle(List.of(position("new123", "DAL567")));

        verify(repository).findByIcao24("new123");
        verify(repository).save(any(AircraftTrack.class));
    }

    @Test
    void shouldCallScheduleResolverOnLanding() {
        var track = AircraftTrack.initial("abc123");
        when(repository.findByIcao24("abc123")).thenReturn(Optional.of(track));

        var landingEvent = new LandingEvent("abc123", "UAL1234", "KORD", "ORD",
                1709312400L, 41.9742, -87.9073);
        var result = new StateTransitionResult(track, Optional.of(landingEvent));
        when(stateMachine.process(eq(track), any())).thenReturn(result);

        var resolved = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709312000L, 400L, "AEROAPI");
        when(scheduleResolver.resolve(landingEvent)).thenReturn(resolved);

        handler.handle(List.of(position("abc123", "UAL1234")));

        verify(scheduleResolver).resolve(landingEvent);
    }

    @Test
    void shouldContinueProcessingAfterErrorOnOnePosition() {
        // First position causes an error
        when(repository.findByIcao24("bad123")).thenThrow(new RuntimeException("DynamoDB error"));

        // Second position is fine
        var track = AircraftTrack.initial("good456");
        when(repository.findByIcao24("good456")).thenReturn(Optional.of(track));
        var result = new StateTransitionResult(track, Optional.empty());
        when(stateMachine.process(eq(track), any())).thenReturn(result);

        handler.handle(List.of(
                position("bad123", "ERR1"),
                position("good456", "OK1")
        ));

        // Second position should still be processed
        verify(repository).save(track);
    }
}
```

### Step 2: Run test to verify it fails

```bash
cd skytrack && ./mvnw test -pl . -Dtest="StatefulFlightPositionHandlerTest" -Dsurefire.failIfNoSpecifiedTests=false
```

### Step 3: Create StatefulFlightPositionHandler

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import skytrack.demo.model.AircraftTrack;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.repository.AircraftTrackRepository;

import java.util.List;

@Service
@Primary
public class StatefulFlightPositionHandler implements FlightPositionHandler {

    private static final Logger log = LoggerFactory.getLogger(StatefulFlightPositionHandler.class);

    private final AircraftTrackRepository repository;
    private final AircraftStateMachine stateMachine;
    private final ScheduleResolver scheduleResolver;

    public StatefulFlightPositionHandler(AircraftTrackRepository repository,
                                         AircraftStateMachine stateMachine,
                                         ScheduleResolver scheduleResolver) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.scheduleResolver = scheduleResolver;
    }

    @Override
    public void handle(List<FlightPosition> positions) {
        int landings = 0;
        for (FlightPosition position : positions) {
            try {
                AircraftTrack track = repository.findByIcao24(position.icao24())
                        .orElseGet(() -> AircraftTrack.initial(position.icao24()));

                var result = stateMachine.process(track, position);
                repository.save(result.updatedTrack());

                if (result.landingEvent().isPresent()) {
                    landings++;
                    var resolved = scheduleResolver.resolve(result.landingEvent().get());
                    log.info("Landing resolved: {} {} at {} delay={}s method={}",
                            resolved.carrierCode(), resolved.flightNumber(),
                            resolved.arrivalAirportIata(), resolved.delaySeconds(),
                            resolved.resolutionMethod());
                }
            } catch (Exception e) {
                log.error("Error processing position for icao24={}: {}",
                        position.icao24(), e.getMessage(), e);
            }
        }
        log.debug("Processed {} positions, {} landings detected", positions.size(), landings);
    }
}
```

### Step 4: Remove @Component from LoggingFlightPositionHandler

In `LoggingFlightPositionHandler.java`, change:
```java
@Component
public class LoggingFlightPositionHandler implements FlightPositionHandler {
```
to:
```java
public class LoggingFlightPositionHandler implements FlightPositionHandler {
```

This lets `StatefulFlightPositionHandler` (with `@Primary`) be the sole auto-detected handler. `LoggingFlightPositionHandler` remains available for manual instantiation in tests.

### Step 5: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="StatefulFlightPositionHandlerTest"
```

Expected: PASS

### Step 6: Check that existing tests still pass

The `LoggingFlightPositionHandlerTest` should still pass since it directly instantiates the class. But `SqsConsumerServiceTest` may need adjustment if it relied on auto-wiring `LoggingFlightPositionHandler`. Run:

```bash
cd skytrack && ./mvnw test -pl . -Dtest="LoggingFlightPositionHandlerTest,SqsConsumerServiceTest"
```

If `SqsConsumerServiceTest` fails because it uses `@Mock FlightPositionHandler`, it should be fine — the mock doesn't depend on a specific implementation. Investigate and fix any failures.

### Step 7: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/service/StatefulFlightPositionHandler.java \
  skytrack/src/main/java/skytrack/demo/service/LoggingFlightPositionHandler.java \
  skytrack/src/test/java/skytrack/demo/service/StatefulFlightPositionHandlerTest.java
git commit -m "feat: add StatefulFlightPositionHandler, replace LoggingFlightPositionHandler"
```

---

## Task 9: End-to-End Integration Test

Full pipeline test: positions → state machine → DynamoDB → schedule resolution. Uses Testcontainers (LocalStack for DynamoDB) and mocked AeroAPI.

**Files:**
- Create: `skytrack/src/test/java/skytrack/demo/service/FlightPipelineIntegrationTest.java`

### Step 1: Write the integration test

```java
package skytrack.demo.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.*;
import skytrack.demo.repository.AircraftTrackRepository;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlightPipelineIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    private static DynamoDbClient dynamoDbClient;
    private static AircraftTrackRepository repository;
    private static StatefulFlightPositionHandler handler;

    @BeforeAll
    static void setUp() throws Exception {
        dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();

        // Create DynamoDB table
        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName("skytrack-aircraft")
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("icao24").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sortKey").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("icao24").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sortKey").keyType(KeyType.RANGE).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());

        var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
        DynamoDbTable<AircraftTrack> table = enhancedClient.table(
                "skytrack-aircraft", TableSchema.fromBean(AircraftTrack.class));
        repository = new AircraftTrackRepository(table);

        // Build the handler with real components
        var airportLookup = new AirportLookupService("data/airports/airports.csv");
        airportLookup.loadAirports();

        var props = new StateMachineProperties(150.0, 50.0, 5.0, 300);
        var stateMachine = new AircraftStateMachine(airportLookup, props);
        var callsignParser = new CallsignParser();
        var routeAverageEstimator = new RouteAverageEstimator();

        // Stub AeroAPI client — returns a schedule for UAL1234
        FlightScheduleApiClient apiClient = new FlightScheduleApiClient() {
            @Override
            public Optional<FlightSchedule> getFlightSchedule(String callsign, String date) {
                if ("UAL1234".equals(callsign)) {
                    return Optional.of(new FlightSchedule(
                            "UAL1234", "UA1234", "UAL", "LAX", "ORD",
                            Instant.parse("2026-03-15T14:00:00Z"),
                            Instant.parse("2026-03-15T18:00:00Z"),
                            Instant.parse("2026-03-15T14:10:00Z"),
                            null, null, null, "B738"));
                }
                return Optional.empty();
            }

            @Override
            public List<FlightSchedule> getDailyFlights(String date) {
                return List.of();
            }
        };

        var scheduleResolver = new ScheduleResolver(apiClient, callsignParser, routeAverageEstimator);
        handler = new StatefulFlightPositionHandler(repository, stateMachine, scheduleResolver);
    }

    @AfterAll
    static void tearDown() {
        if (dynamoDbClient != null) dynamoDbClient.close();
    }

    @Test
    void shouldTrackFlightFromEnRouteToLanding() {
        String icao24 = "integ-test-1";
        long t = Instant.parse("2026-03-15T17:50:00Z").getEpochSecond();

        // 1. Airborne, far from ORD
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 40.0, -90.0, 10000.0, 450.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));

        var track1 = repository.findByIcao24(icao24);
        assertThat(track1).isPresent();
        assertThat(track1.get().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);

        // 2. Descending near ORD (within 50km approach radius)
        t += 60;
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 42.1, -87.8, 3000.0, 250.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));

        var track2 = repository.findByIcao24(icao24);
        assertThat(track2).isPresent();
        assertThat(track2.get().getAircraftState()).isEqualTo(AircraftState.APPROACHING);

        // 3. Landed at ORD (onGround=true, within 5km)
        t += 60;
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 41.9742, -87.9073, 0.0, 0.0, 0.0,
                true, t, t - 5, Instant.ofEpochSecond(t))));

        var track3 = repository.findByIcao24(icao24);
        assertThat(track3).isPresent();
        assertThat(track3.get().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(track3.get().getNearestAirportIcao()).isEqualTo("KORD");
    }

    @Test
    void shouldTransitionToDepartedWhenTakingOff() {
        String icao24 = "integ-test-2";
        long t = Instant.parse("2026-03-15T18:00:00Z").getEpochSecond();

        // Start on ground at ORD
        handler.handle(List.of(new FlightPosition(
                icao24, "DAL567", 41.9742, -87.9073, 0.0, 0.0, 0.0,
                true, t, t - 5, Instant.ofEpochSecond(t))));

        var track1 = repository.findByIcao24(icao24);
        assertThat(track1.get().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);

        // Take off
        t += 120;
        handler.handle(List.of(new FlightPosition(
                icao24, "DAL567", 41.98, -87.91, 500.0, 150.0, 270.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));

        var track2 = repository.findByIcao24(icao24);
        assertThat(track2.get().getAircraftState()).isEqualTo(AircraftState.DEPARTED);
    }

    @Test
    void shouldHandleUnknownCallsignGracefully() {
        String icao24 = "integ-test-3";
        long t = Instant.parse("2026-03-15T19:00:00Z").getEpochSecond();

        // Airborne with unknown callsign
        handler.handle(List.of(new FlightPosition(
                icao24, "ZZZ999", 40.0, -90.0, 10000.0, 450.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));

        // Should still track state, just can't resolve schedule
        var track = repository.findByIcao24(icao24);
        assertThat(track).isPresent();
        assertThat(track.get().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
    }
}
```

### Step 2: Run the integration test

```bash
cd skytrack && ./mvnw test -pl . -Dtest="FlightPipelineIntegrationTest"
```

Expected: PASS — full pipeline works end-to-end against LocalStack DynamoDB with real airport data.

### Step 3: Run ALL tests to verify nothing is broken

```bash
cd skytrack && ./mvnw test
```

Investigate and fix any failures. Common issues:
- `SqsConsumerServiceTest` may fail if it expected `LoggingFlightPositionHandler` via `@Component`
- Spring context tests (`DemoApplicationTests`) may fail if new beans can't be wired (missing DynamoDB endpoint in test context)

For `DemoApplicationTests`, either add test properties or skip if it requires full context:

```java
@SpringBootTest(properties = {
    "skytrack.dynamodb.endpoint=http://localhost:4566",
    "skytrack.dynamodb.table-name=test-table",
    "skytrack.dynamodb.region=us-east-1",
    "skytrack.state-machine.ground-altitude-meters=150",
    "skytrack.state-machine.approach-radius-km=50",
    "skytrack.state-machine.ground-radius-km=5",
    "skytrack.state-machine.stale-timeout-seconds=300"
})
```

### Step 4: Commit

```bash
git add skytrack/src/test/java/skytrack/demo/service/FlightPipelineIntegrationTest.java
git commit -m "feat: add end-to-end flight pipeline integration test"
```

---

## Task 10: Final Verification & Cleanup

### Step 1: Run full test suite

```bash
cd skytrack && ./mvnw clean test
```

All tests must pass. Fix any failures discovered.

### Step 2: Verify docker-compose still works

```bash
docker compose up -d
# Wait for LocalStack to initialize
sleep 5
# Verify DynamoDB table was created
aws --endpoint-url=http://localhost:4566 dynamodb list-tables --region us-east-1
# Expected output includes "skytrack-aircraft"
docker compose down
```

### Step 3: Commit any remaining fixes

```bash
git add -A
git commit -m "chore: Chunk 4 cleanup and verification"
```

---

## Summary

### New Files (16 source + 9 test = 25 total)

| File | Package | Type |
|------|---------|------|
| `model/AircraftState.java` | model | Enum |
| `model/AircraftTrack.java` | model | DynamoDB Entity |
| `model/Airport.java` | model | Record |
| `model/LandingEvent.java` | model | Record |
| `model/ParsedCallsign.java` | model | Record |
| `model/ResolvedArrival.java` | model | Record |
| `model/StateTransitionResult.java` | model | Record |
| `config/DynamoDbProperties.java` | config | Properties |
| `config/DynamoDbConfig.java` | config | Configuration |
| `config/StateMachineProperties.java` | config | Properties |
| `repository/AircraftTrackRepository.java` | repository | Repository |
| `service/AirportLookupService.java` | service | Service |
| `service/CallsignParser.java` | service | Component |
| `service/AircraftStateMachine.java` | service | Component |
| `service/ScheduleResolver.java` | service | Service |
| `service/RouteAverageEstimator.java` | service | Service |
| `service/StatefulFlightPositionHandler.java` | service | Service |

### Modified Files

| File | Change |
|------|--------|
| `pom.xml` | Add `dynamodb-enhanced` dependency |
| `localstack/init-aws.sh` | Add DynamoDB table creation + TTL |
| `application.yml` | Add DynamoDB + state machine properties |
| `application-local.yml` | Add DynamoDB endpoint override |
| `application-prod.yml` | Add DynamoDB prod config |
| `service/LoggingFlightPositionHandler.java` | Remove `@Component` annotation |

### Test Summary

| Category | Count |
|----------|-------|
| Unit tests | ~28 |
| Integration tests (LocalStack) | ~8 |
| **Total** | **~36** |

### Risks & Notes

1. **DynamoDB Enhanced Client + Lombok**: `@DynamoDbBean` requires getters/setters and no-arg constructor. Lombok `@Data` provides these, but annotated getters (`@DynamoDbPartitionKey`) must be written explicitly — Lombok skips generating a getter when one already exists in source.
2. **Airport CSV size**: ~500 US airports. Linear scan for `findNearest` is sub-millisecond. No spatial index needed at this scale.
3. **Route average estimator cold start**: Returns empty until ≥3 AeroAPI responses are cached for a given carrier+airport pair. First few landings will resolve as UNRESOLVED if AeroAPI is disabled.
4. **DynamoDB reads per position**: One getItem + one putItem per position per aircraft. For local dev with recorded data this is fine. Production optimization (batching, in-memory cache with periodic flush) is a future concern.
