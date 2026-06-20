# Chunk 5: Delay Detection & Airport Disruption Scoring

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the delay computation engine, airport disruption scoring with sliding windows, cascade detection, and wire the full delay pipeline into the existing StatefulFlightPositionHandler — publishing delay events to the airport events SQS FIFO queue.

**Architecture:** When `StatefulFlightPositionHandler` detects a landing and resolves the schedule (via Chunk 4's `ScheduleResolver`), it now passes the `ResolvedArrival` to a `DelayEventProcessor` orchestrator. This orchestrator: (1) classifies the delay via `DelayComputer`, (2) updates the airport disruption score via `DisruptionScoreService` (in-memory sliding window with 1-minute buckets over a 60-minute window), (3) publishes the `DelayEvent` to `skytrack-airport-events.fifo` via `SqsAirportEventProducer`, and (4) checks for cascade risk via `CascadeDetector`. The disruption score is a 0–100 weighted composite of delayed flight count, average severity, trend direction, and percentage delayed — queryable via `getTopDisruptedAirports(n)`.

**Tech Stack:** Spring Boot 4.0.2, Java 25, AWS SDK v2 SQS, ConcurrentHashMap + TreeMap sliding windows, Lombok, Testcontainers + LocalStack, Mockito

**Depends on:** Chunks 1–4 (OpenSky clients, SQS pipeline, AeroAPI + WireMock, DynamoDB + state machine + schedule resolution)

---

## Task 1: DelayClassification Enum & DelayEvent Model

Create the delay classification categories (FAA standard: >15 min = delayed) and the enriched delay event record that flows through the rest of the pipeline.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/model/DelayClassification.java`
- Create: `skytrack/src/main/java/skytrack/demo/model/DelayEvent.java`
- Test: `skytrack/src/test/java/skytrack/demo/model/DelayClassificationTest.java`
- Test: `skytrack/src/test/java/skytrack/demo/model/DelayEventTest.java`

### Step 1: Write the failing tests

**`DelayClassificationTest.java`:**

```java
package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelayClassificationTest {

    @Test
    void shouldClassifyOnTime() {
        assertThat(DelayClassification.fromDelaySeconds(0L)).isEqualTo(DelayClassification.ON_TIME);
        assertThat(DelayClassification.fromDelaySeconds(-300L)).isEqualTo(DelayClassification.ON_TIME);
    }

    @Test
    void shouldClassifyMinor() {
        assertThat(DelayClassification.fromDelaySeconds(60L)).isEqualTo(DelayClassification.MINOR);
        assertThat(DelayClassification.fromDelaySeconds(900L)).isEqualTo(DelayClassification.MINOR);
    }

    @Test
    void shouldClassifyModerate() {
        assertThat(DelayClassification.fromDelaySeconds(960L)).isEqualTo(DelayClassification.MODERATE);
        assertThat(DelayClassification.fromDelaySeconds(2700L)).isEqualTo(DelayClassification.MODERATE);
    }

    @Test
    void shouldClassifyMajor() {
        assertThat(DelayClassification.fromDelaySeconds(2760L)).isEqualTo(DelayClassification.MAJOR);
        assertThat(DelayClassification.fromDelaySeconds(7200L)).isEqualTo(DelayClassification.MAJOR);
    }

    @Test
    void shouldClassifySevere() {
        assertThat(DelayClassification.fromDelaySeconds(7260L)).isEqualTo(DelayClassification.SEVERE);
    }

    @Test
    void shouldClassifyUnknownForNull() {
        assertThat(DelayClassification.fromDelaySeconds(null)).isEqualTo(DelayClassification.UNKNOWN);
    }

    @Test
    void shouldIdentifyFaaDelayedFlights() {
        assertThat(DelayClassification.ON_TIME.isDelayed()).isFalse();
        assertThat(DelayClassification.MINOR.isDelayed()).isFalse();
        assertThat(DelayClassification.MODERATE.isDelayed()).isTrue();
        assertThat(DelayClassification.MAJOR.isDelayed()).isTrue();
        assertThat(DelayClassification.SEVERE.isDelayed()).isTrue();
        assertThat(DelayClassification.UNKNOWN.isDelayed()).isFalse();
    }
}
```

**`DelayEventTest.java`:**

```java
package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DelayEventTest {

    @Test
    void shouldConstructDelayEvent() {
        var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L,
                DelayClassification.MODERATE, "AEROAPI", Instant.now());
        assertThat(event.icao24()).isEqualTo("abc123");
        assertThat(event.delaySeconds()).isEqualTo(900L);
        assertThat(event.classification()).isEqualTo(DelayClassification.MODERATE);
        assertThat(event.resolutionMethod()).isEqualTo("AEROAPI");
    }

    @Test
    void shouldSupportNullDelayForUnresolved() {
        var event = new DelayEvent("abc123", "ZZZ999", null, null,
                "KORD", "ORD", 1709312400L, null, null,
                DelayClassification.UNKNOWN, "UNRESOLVED", Instant.now());
        assertThat(event.delaySeconds()).isNull();
        assertThat(event.scheduledArrivalTime()).isNull();
        assertThat(event.classification()).isEqualTo(DelayClassification.UNKNOWN);
    }
}
```

### Step 2: Run tests to verify they fail

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DelayClassificationTest,DelayEventTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — classes don't exist yet.

### Step 3: Create DelayClassification enum

```java
package skytrack.demo.model;

public enum DelayClassification {
    ON_TIME,
    MINOR,
    MODERATE,
    MAJOR,
    SEVERE,
    UNKNOWN;

    public static DelayClassification fromDelaySeconds(Long delaySeconds) {
        if (delaySeconds == null) return UNKNOWN;
        long minutes = delaySeconds / 60;
        if (minutes <= 0) return ON_TIME;
        if (minutes <= 15) return MINOR;
        if (minutes <= 45) return MODERATE;
        if (minutes <= 120) return MAJOR;
        return SEVERE;
    }

    public boolean isDelayed() {
        return this == MODERATE || this == MAJOR || this == SEVERE;
    }
}
```

### Step 4: Create DelayEvent record

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
        Instant createdAt) {}
```

### Step 5: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DelayClassificationTest,DelayEventTest"
```

Expected: PASS

### Step 6: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/model/DelayClassification.java \
  skytrack/src/main/java/skytrack/demo/model/DelayEvent.java \
  skytrack/src/test/java/skytrack/demo/model/DelayClassificationTest.java \
  skytrack/src/test/java/skytrack/demo/model/DelayEventTest.java
git commit -m "feat: add DelayClassification enum and DelayEvent model"
```

---

## Task 2: DelayComputer Service

Converts a `ResolvedArrival` (from Chunk 4's `ScheduleResolver`) into a classified `DelayEvent`.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/DelayComputer.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/DelayComputerTest.java`

### Step 1: Write the failing test

**`DelayComputerTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.ResolvedArrival;

import static org.assertj.core.api.Assertions.assertThat;

class DelayComputerTest {

    private final DelayComputer computer = new DelayComputer();

    @Test
    void shouldComputeModerateDelay() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L, "AEROAPI");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.MODERATE);
        assertThat(event.delaySeconds()).isEqualTo(900L);
        assertThat(event.resolutionMethod()).isEqualTo("AEROAPI");
    }

    @Test
    void shouldComputeOnTimeArrival() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709312400L, 0L, "AEROAPI");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.ON_TIME);
    }

    @Test
    void shouldComputeEarlyArrival() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709313000L, -600L, "AEROAPI");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.ON_TIME);
    }

    @Test
    void shouldHandleUnresolvedArrival() {
        var arrival = new ResolvedArrival("abc123", "ZZZ999", null, null,
                "KORD", "ORD", 1709312400L, null, null, "UNRESOLVED");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.UNKNOWN);
        assertThat(event.delaySeconds()).isNull();
    }

    @Test
    void shouldPreserveAllFieldsFromResolvedArrival() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L, "AEROAPI");

        var event = computer.compute(arrival);

        assertThat(event.icao24()).isEqualTo("abc123");
        assertThat(event.callsign()).isEqualTo("UAL1234");
        assertThat(event.carrierCode()).isEqualTo("UA");
        assertThat(event.flightNumber()).isEqualTo("1234");
        assertThat(event.arrivalAirportIcao()).isEqualTo("KORD");
        assertThat(event.arrivalAirportIata()).isEqualTo("ORD");
        assertThat(event.actualArrivalTime()).isEqualTo(1709312400L);
        assertThat(event.scheduledArrivalTime()).isEqualTo(1709311500L);
        assertThat(event.createdAt()).isNotNull();
    }

    @Test
    void shouldComputeSevereDelay() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709305200L, 7200L, "AEROAPI");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.MAJOR);
    }

    @Test
    void shouldComputeRouteAverageDelay() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, null, 1800L, "ROUTE_AVERAGE");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.MODERATE);
        assertThat(event.resolutionMethod()).isEqualTo("ROUTE_AVERAGE");
        assertThat(event.scheduledArrivalTime()).isNull();
    }
}
```

### Step 2: Run test to verify it fails

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DelayComputerTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL

### Step 3: Create DelayComputer

```java
package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;
import skytrack.demo.model.ResolvedArrival;

import java.time.Instant;

@Service
public class DelayComputer {

    public DelayEvent compute(ResolvedArrival arrival) {
        DelayClassification classification = DelayClassification.fromDelaySeconds(arrival.delaySeconds());
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
                Instant.now());
    }
}
```

### Step 4: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DelayComputerTest"
```

Expected: PASS

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/service/DelayComputer.java \
  skytrack/src/test/java/skytrack/demo/service/DelayComputerTest.java
git commit -m "feat: add DelayComputer for delay classification"
```

---

## Task 3: SqsAirportEventProducer

Publishes `DelayEvent` records to the `skytrack-airport-events.fifo` SQS FIFO queue with `MessageGroupId = airport IATA code`. Follows the same pattern as the existing `SqsPositionProducer`.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/sqs/SqsAirportEventProducer.java`
- Modify: `skytrack/src/main/java/skytrack/demo/config/SqsConfig.java`
- Test: `skytrack/src/test/java/skytrack/demo/sqs/SqsAirportEventProducerTest.java`

### Step 1: Write the failing test

**`SqsAirportEventProducerTest.java`:**

```java
package skytrack.demo.sqs;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SqsAirportEventProducerTest {

    @SuppressWarnings("resource")
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices(LocalStackContainer.Service.SQS);

    private static SqsClient sqsClient;
    private static String queueUrl;
    private static SqsAirportEventProducer producer;

    @BeforeAll
    static void setUp() {
        sqsClient = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();

        queueUrl = sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName("skytrack-airport-events.fifo")
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                .build()).queueUrl();

        producer = new SqsAirportEventProducer(sqsClient, queueUrl);
    }

    @AfterAll
    static void tearDown() {
        if (sqsClient != null) sqsClient.close();
    }

    @Test
    void shouldPublishDelayEventToQueue() {
        var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L,
                DelayClassification.MODERATE, "AEROAPI", Instant.now());

        producer.send(event);

        var messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(5)
                .build()).messages();

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).body()).contains("UAL1234");
        assertThat(messages.get(0).body()).contains("ORD");
        assertThat(messages.get(0).body()).contains("MODERATE");
    }

    @Test
    void shouldUseAirportCodeAsMessageGroupId() {
        var event = new DelayEvent("def456", "DAL567", "DL", "567",
                "KLAX", "LAX", 1709312500L, 1709311600L, 900L,
                DelayClassification.MODERATE, "AEROAPI", Instant.now());

        producer.send(event);

        // Verify message was sent (message group ID is internal to SQS FIFO ordering)
        var messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(5)
                .build()).messages();

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).body()).contains("LAX");
    }
}
```

### Step 2: Run test to verify it fails

```bash
cd skytrack && ./mvnw test -pl . -Dtest="SqsAirportEventProducerTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — class doesn't exist.

### Step 3: Create SqsAirportEventProducer

```java
package skytrack.demo.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skytrack.demo.model.DelayEvent;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

public class SqsAirportEventProducer {

    private static final Logger log = LoggerFactory.getLogger(SqsAirportEventProducer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SqsClient sqsClient;
    private final String queueUrl;

    public SqsAirportEventProducer(SqsClient sqsClient, String queueUrl) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    public void send(DelayEvent event) {
        try {
            String body = mapper.writeValueAsString(event);
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .messageGroupId(event.arrivalAirportIata())
                    .messageDeduplicationId(event.icao24() + "-" + event.actualArrivalTime())
                    .build());
            log.debug("Published delay event for {} at {} to airport events queue",
                    event.callsign(), event.arrivalAirportIata());
        } catch (Exception e) {
            log.error("Failed to publish delay event for {} at {}: {}",
                    event.callsign(), event.arrivalAirportIata(), e.getMessage(), e);
        }
    }
}
```

### Step 4: Wire the producer bean in SqsConfig

Add this bean method to `skytrack/src/main/java/skytrack/demo/config/SqsConfig.java`, after the existing `sqsPositionConsumer` bean. Also add the import for `SqsAirportEventProducer`:

Add import:
```java
import skytrack.demo.sqs.SqsAirportEventProducer;
```

Add bean method:
```java
@Bean
public SqsAirportEventProducer sqsAirportEventProducer(SqsClient sqsClient, SqsProperties properties) {
    String queueUrl = resolveQueueUrl(sqsClient, properties.airportEventsQueueName());
    return new SqsAirportEventProducer(sqsClient, queueUrl);
}
```

### Step 5: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="SqsAirportEventProducerTest"
```

Expected: PASS

### Step 6: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/sqs/SqsAirportEventProducer.java \
  skytrack/src/main/java/skytrack/demo/config/SqsConfig.java \
  skytrack/src/test/java/skytrack/demo/sqs/SqsAirportEventProducerTest.java
git commit -m "feat: add SqsAirportEventProducer for delay event publishing"
```

---

## Task 4: DisruptionScoreProperties & AirportDisruptionScore Model

Configuration for the disruption scoring system and the score result record.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/config/DisruptionScoreProperties.java`
- Create: `skytrack/src/main/java/skytrack/demo/model/AirportDisruptionScore.java`
- Modify: `skytrack/src/main/resources/application.yml`
- Test: `skytrack/src/test/java/skytrack/demo/config/DisruptionScorePropertiesTest.java`
- Test: `skytrack/src/test/java/skytrack/demo/model/AirportDisruptionScoreTest.java`

### Step 1: Write the failing tests

**`DisruptionScorePropertiesTest.java`:**

```java
package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisruptionScorePropertiesTest {

    @Test
    void shouldBindProperties() {
        var props = new DisruptionScoreProperties(60, 1, 15, 30, 0.85);
        assertThat(props.windowMinutes()).isEqualTo(60);
        assertThat(props.bucketSizeMinutes()).isEqualTo(1);
        assertThat(props.delayThresholdMinutes()).isEqualTo(15);
        assertThat(props.cascadeThresholdMinutes()).isEqualTo(30);
        assertThat(props.cascadePropagationFactor()).isEqualTo(0.85);
    }

    @Test
    void shouldApplyDefaults() {
        var props = new DisruptionScoreProperties(0, 0, 0, 0, 0);
        assertThat(props.windowMinutes()).isEqualTo(60);
        assertThat(props.bucketSizeMinutes()).isEqualTo(1);
        assertThat(props.delayThresholdMinutes()).isEqualTo(15);
        assertThat(props.cascadeThresholdMinutes()).isEqualTo(30);
        assertThat(props.cascadePropagationFactor()).isEqualTo(0.85);
    }
}
```

**`AirportDisruptionScoreTest.java`:**

```java
package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AirportDisruptionScoreTest {

    @Test
    void shouldConstructScore() {
        var score = new AirportDisruptionScore("ORD", 75.5, 12, 30, 42.3, 0.15, Instant.now());
        assertThat(score.airportIata()).isEqualTo("ORD");
        assertThat(score.score()).isEqualTo(75.5);
        assertThat(score.activeDelayCount()).isEqualTo(12);
        assertThat(score.totalFlightsInWindow()).isEqualTo(30);
        assertThat(score.averageDelayMinutes()).isEqualTo(42.3);
        assertThat(score.trendDirection()).isEqualTo(0.15);
    }
}
```

### Step 2: Run tests to verify they fail

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DisruptionScorePropertiesTest,AirportDisruptionScoreTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — classes don't exist.

### Step 3: Create DisruptionScoreProperties

```java
package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.disruption")
public record DisruptionScoreProperties(
        int windowMinutes,
        int bucketSizeMinutes,
        int delayThresholdMinutes,
        int cascadeThresholdMinutes,
        double cascadePropagationFactor) {

    public DisruptionScoreProperties {
        if (windowMinutes <= 0) windowMinutes = 60;
        if (bucketSizeMinutes <= 0) bucketSizeMinutes = 1;
        if (delayThresholdMinutes <= 0) delayThresholdMinutes = 15;
        if (cascadeThresholdMinutes <= 0) cascadeThresholdMinutes = 30;
        if (cascadePropagationFactor <= 0) cascadePropagationFactor = 0.85;
    }
}
```

### Step 4: Create AirportDisruptionScore record

```java
package skytrack.demo.model;

import java.time.Instant;

public record AirportDisruptionScore(
        String airportIata,
        double score,
        int activeDelayCount,
        int totalFlightsInWindow,
        double averageDelayMinutes,
        double trendDirection,
        Instant computedAt) {}
```

### Step 5: Update application.yml

Append under the existing `skytrack:` block, after the `state-machine` section:

```yaml
  disruption:
    window-minutes: 60
    bucket-size-minutes: 1
    delay-threshold-minutes: 15
    cascade-threshold-minutes: 30
    cascade-propagation-factor: 0.85
```

The full `skytrack:` block should now look like:

```yaml
skytrack:
  dynamodb:
    table-name: skytrack-aircraft
    region: us-east-1
  state-machine:
    ground-altitude-meters: 150
    approach-radius-km: 50
    ground-radius-km: 5
    stale-timeout-seconds: 300
  disruption:
    window-minutes: 60
    bucket-size-minutes: 1
    delay-threshold-minutes: 15
    cascade-threshold-minutes: 30
    cascade-propagation-factor: 0.85
```

### Step 6: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DisruptionScorePropertiesTest,AirportDisruptionScoreTest"
```

Expected: PASS

### Step 7: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/config/DisruptionScoreProperties.java \
  skytrack/src/main/java/skytrack/demo/model/AirportDisruptionScore.java \
  skytrack/src/main/resources/application.yml \
  skytrack/src/test/java/skytrack/demo/config/DisruptionScorePropertiesTest.java \
  skytrack/src/test/java/skytrack/demo/model/AirportDisruptionScoreTest.java
git commit -m "feat: add DisruptionScoreProperties and AirportDisruptionScore model"
```

---

## Task 5: DisruptionScoreService

In-memory sliding window scoring engine. Uses `ConcurrentHashMap<String, TreeMap<Long, BucketMetrics>>` keyed by airport IATA code. Each bucket covers 1 minute. The score is a 0–100 weighted composite:

- **Delayed flight count** (weight 0.3): `min(delayedCount / 10, 1.0) × 30`
- **Average delay severity** (weight 0.3): `min(avgDelayMinutes / 60, 1.0) × 30`
- **Trend direction** (weight 0.2): compares delay rate in first half vs second half of window
- **Percentage of flights delayed** (weight 0.2): `delayedPct × 20`

The window reference point is the latest event timestamp (not wall clock), so scoring works correctly with both live and replayed data.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/DisruptionScoreService.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/DisruptionScoreServiceTest.java`

### Step 1: Write the failing test

**`DisruptionScoreServiceTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.model.AirportDisruptionScore;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DisruptionScoreServiceTest {

    private DisruptionScoreService service;

    @BeforeEach
    void setUp() {
        var props = new DisruptionScoreProperties(60, 1, 15, 30, 0.85);
        service = new DisruptionScoreService(props);
    }

    private DelayEvent delayEvent(String airport, long arrivalTime,
                                   long delaySeconds, DelayClassification classification) {
        return new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "K" + airport, airport, arrivalTime,
                arrivalTime - delaySeconds, delaySeconds,
                classification, "AEROAPI", Instant.ofEpochSecond(arrivalTime));
    }

    @Test
    void shouldReturnZeroScoreForUnknownAirport() {
        var score = service.computeScore("ZZZ");
        assertThat(score.score()).isEqualTo(0.0);
        assertThat(score.totalFlightsInWindow()).isEqualTo(0);
    }

    @Test
    void shouldRecordAndComputeScore() {
        long baseTime = 1709312400L;
        service.recordDelay(delayEvent("ORD", baseTime, 1800, DelayClassification.MODERATE));
        service.recordDelay(delayEvent("ORD", baseTime + 60, 2700, DelayClassification.MODERATE));
        service.recordDelay(delayEvent("ORD", baseTime + 120, 3600, DelayClassification.MAJOR));

        var score = service.computeScore("ORD");
        assertThat(score.score()).isGreaterThan(0.0);
        assertThat(score.activeDelayCount()).isEqualTo(3);
        assertThat(score.totalFlightsInWindow()).isEqualTo(3);
        assertThat(score.airportIata()).isEqualTo("ORD");
    }

    @Test
    void shouldCountOnlyFaaDelayedFlightsInActiveDelayCount() {
        long baseTime = 1709312400L;
        service.recordDelay(delayEvent("ORD", baseTime, 0, DelayClassification.ON_TIME));
        service.recordDelay(delayEvent("ORD", baseTime + 60, 600, DelayClassification.MINOR));
        service.recordDelay(delayEvent("ORD", baseTime + 120, 1800, DelayClassification.MODERATE));

        var score = service.computeScore("ORD");
        assertThat(score.activeDelayCount()).isEqualTo(1);
        assertThat(score.totalFlightsInWindow()).isEqualTo(3);
    }

    @Test
    void shouldEvictExpiredBuckets() {
        long oldTime = 1709308800L;
        long recentTime = oldTime + 7200; // 2 hours later

        service.recordDelay(delayEvent("ORD", oldTime, 1800, DelayClassification.MODERATE));
        service.recordDelay(delayEvent("ORD", recentTime, 900, DelayClassification.MODERATE));

        var score = service.computeScore("ORD");
        // Old event (2 hours before latest) should be evicted from the 60-min window
        assertThat(score.totalFlightsInWindow()).isEqualTo(1);
    }

    @Test
    void shouldGetTopDisruptedAirports() {
        long baseTime = 1709312400L;

        // ORD: 3 major delays
        for (int i = 0; i < 3; i++) {
            service.recordDelay(delayEvent("ORD", baseTime + i * 60, 3600, DelayClassification.MAJOR));
        }

        // LAX: 1 minor delay (not FAA-delayed)
        service.recordDelay(delayEvent("LAX", baseTime, 600, DelayClassification.MINOR));

        // JFK: 5 severe delays
        for (int i = 0; i < 5; i++) {
            service.recordDelay(delayEvent("JFK", baseTime + i * 60, 7800, DelayClassification.SEVERE));
        }

        List<AirportDisruptionScore> top = service.getTopDisruptedAirports(2);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).airportIata()).isEqualTo("JFK");
        assertThat(top.get(0).score()).isGreaterThan(top.get(1).score());
    }

    @Test
    void shouldCapScoreAt100() {
        long baseTime = 1709312400L;
        for (int i = 0; i < 20; i++) {
            service.recordDelay(delayEvent("ORD", baseTime + i * 60, 7200, DelayClassification.SEVERE));
        }

        var score = service.computeScore("ORD");
        assertThat(score.score()).isLessThanOrEqualTo(100.0);
    }

    @Test
    void shouldTrackSeparateAirports() {
        long baseTime = 1709312400L;
        service.recordDelay(delayEvent("ORD", baseTime, 1800, DelayClassification.MODERATE));
        service.recordDelay(delayEvent("LAX", baseTime, 3600, DelayClassification.MAJOR));

        var ordScore = service.computeScore("ORD");
        var laxScore = service.computeScore("LAX");

        assertThat(ordScore.totalFlightsInWindow()).isEqualTo(1);
        assertThat(laxScore.totalFlightsInWindow()).isEqualTo(1);
        assertThat(laxScore.score()).isGreaterThan(ordScore.score());
    }

    @Test
    void shouldComputeAverageDelayMinutes() {
        long baseTime = 1709312400L;
        // 3 flights: 30 min, 60 min, 90 min delays → avg = 60 min
        service.recordDelay(delayEvent("ORD", baseTime, 1800, DelayClassification.MODERATE));
        service.recordDelay(delayEvent("ORD", baseTime + 60, 3600, DelayClassification.MAJOR));
        service.recordDelay(delayEvent("ORD", baseTime + 120, 5400, DelayClassification.MAJOR));

        var score = service.computeScore("ORD");
        assertThat(score.averageDelayMinutes()).isEqualTo(60.0);
    }

    @Test
    void shouldIgnoreNullAirportCode() {
        var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", null, 1709312400L, null, null,
                DelayClassification.UNKNOWN, "UNRESOLVED", Instant.now());
        service.recordDelay(event);
        // Should not throw
    }
}
```

### Step 2: Run test to verify it fails

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DisruptionScoreServiceTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL

### Step 3: Create DisruptionScoreService

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.model.AirportDisruptionScore;
import skytrack.demo.model.DelayEvent;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Service
@EnableConfigurationProperties(DisruptionScoreProperties.class)
public class DisruptionScoreService {

    private static final Logger log = LoggerFactory.getLogger(DisruptionScoreService.class);

    private final DisruptionScoreProperties props;
    private final Map<String, TreeMap<Long, BucketMetrics>> airportBuckets = new ConcurrentHashMap<>();

    public DisruptionScoreService(DisruptionScoreProperties props) {
        this.props = props;
    }

    public void recordDelay(DelayEvent event) {
        if (event.arrivalAirportIata() == null) return;

        long bucketKey = toBucketKey(event.actualArrivalTime());
        airportBuckets.computeIfAbsent(event.arrivalAirportIata(), k -> new TreeMap<>())
                .computeIfAbsent(bucketKey, k -> new BucketMetrics())
                .record(event);
    }

    public AirportDisruptionScore computeScore(String airportIata) {
        TreeMap<Long, BucketMetrics> buckets = airportBuckets.get(airportIata);
        if (buckets == null || buckets.isEmpty()) {
            return emptyScore(airportIata);
        }

        long latestBucket = buckets.lastKey();
        long windowStart = latestBucket - (props.windowMinutes() * 60L);
        evictExpiredBuckets(buckets, windowStart);

        if (buckets.isEmpty()) {
            return emptyScore(airportIata);
        }

        int totalFlights = 0;
        int delayedFlights = 0;
        long totalDelaySeconds = 0;

        for (BucketMetrics bucket : buckets.values()) {
            totalFlights += bucket.totalFlights;
            delayedFlights += bucket.delayedFlights;
            totalDelaySeconds += bucket.totalDelaySeconds;
        }

        double avgDelayMinutes = totalFlights > 0
                ? (totalDelaySeconds / 60.0) / totalFlights : 0;
        double delayedPct = totalFlights > 0
                ? (double) delayedFlights / totalFlights : 0;
        double trend = computeTrend(buckets, windowStart, latestBucket);

        double delayedFlightScore = Math.min(delayedFlights / 10.0, 1.0) * 30;
        double severityScore = Math.min(avgDelayMinutes / 60.0, 1.0) * 30;
        double trendScore = Math.max(Math.min(trend, 1.0), 0.0) * 20;
        double percentageScore = delayedPct * 20;
        double score = Math.min(
                delayedFlightScore + severityScore + trendScore + percentageScore, 100.0);

        return new AirportDisruptionScore(
                airportIata, score, delayedFlights, totalFlights,
                avgDelayMinutes, trend, Instant.now());
    }

    public List<AirportDisruptionScore> getTopDisruptedAirports(int limit) {
        return airportBuckets.keySet().stream()
                .map(this::computeScore)
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(AirportDisruptionScore::score).reversed())
                .limit(limit)
                .toList();
    }

    private double computeTrend(TreeMap<Long, BucketMetrics> buckets,
                                 long windowStart, long latestBucket) {
        long midpoint = windowStart + (latestBucket - windowStart) / 2;
        int firstHalfDelayed = 0, secondHalfDelayed = 0;
        int firstHalfTotal = 0, secondHalfTotal = 0;

        for (var entry : buckets.entrySet()) {
            if (entry.getKey() < midpoint) {
                firstHalfDelayed += entry.getValue().delayedFlights;
                firstHalfTotal += entry.getValue().totalFlights;
            } else {
                secondHalfDelayed += entry.getValue().delayedFlights;
                secondHalfTotal += entry.getValue().totalFlights;
            }
        }

        double firstRate = firstHalfTotal > 0
                ? (double) firstHalfDelayed / firstHalfTotal : 0;
        double secondRate = secondHalfTotal > 0
                ? (double) secondHalfDelayed / secondHalfTotal : 0;
        return secondRate - firstRate;
    }

    private void evictExpiredBuckets(TreeMap<Long, BucketMetrics> buckets, long windowStart) {
        buckets.headMap(windowStart).clear();
    }

    private long toBucketKey(long epochSeconds) {
        long bucketSize = props.bucketSizeMinutes() * 60L;
        return (epochSeconds / bucketSize) * bucketSize;
    }

    private AirportDisruptionScore emptyScore(String airportIata) {
        return new AirportDisruptionScore(airportIata, 0.0, 0, 0, 0.0, 0.0, Instant.now());
    }

    static class BucketMetrics {
        int totalFlights;
        int delayedFlights;
        long totalDelaySeconds;

        void record(DelayEvent event) {
            totalFlights++;
            if (event.classification() != null && event.classification().isDelayed()) {
                delayedFlights++;
            }
            if (event.delaySeconds() != null && event.delaySeconds() > 0) {
                totalDelaySeconds += event.delaySeconds();
            }
        }
    }
}
```

### Step 4: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DisruptionScoreServiceTest"
```

Expected: PASS

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/service/DisruptionScoreService.java \
  skytrack/src/test/java/skytrack/demo/service/DisruptionScoreServiceTest.java
git commit -m "feat: add DisruptionScoreService with sliding window scoring"
```

---

## Task 6: CascadeAlert Model & CascadeDetector Service

Predicts downstream delay propagation. When an aircraft arrives with delay above the cascade threshold (default: 30 minutes), the detector predicts downstream delay using a propagation factor (default: 0.85). Only emits a `CascadeAlert` if the predicted downstream delay exceeds the FAA 15-minute threshold.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/model/CascadeAlert.java`
- Create: `skytrack/src/main/java/skytrack/demo/service/CascadeDetector.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/CascadeDetectorTest.java`

### Step 1: Write the failing test

**`CascadeDetectorTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.model.CascadeAlert;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CascadeDetectorTest {

    private CascadeDetector detector;

    @BeforeEach
    void setUp() {
        var props = new DisruptionScoreProperties(60, 1, 15, 30, 0.85);
        detector = new CascadeDetector(props);
    }

    private DelayEvent delayEvent(long delaySeconds) {
        return new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L,
                1709312400L - delaySeconds, delaySeconds,
                DelayClassification.fromDelaySeconds(delaySeconds),
                "AEROAPI", Instant.now());
    }

    @Test
    void shouldEmitCascadeAlertForMajorDelay() {
        // 60 min delay → predicted downstream = 51 min (3600 * 0.85 = 3060s)
        Optional<CascadeAlert> alert = detector.checkCascade(delayEvent(3600));

        assertThat(alert).isPresent();
        assertThat(alert.get().currentDelaySeconds()).isEqualTo(3600);
        assertThat(alert.get().predictedDownstreamDelaySeconds()).isEqualTo(3060);
        assertThat(alert.get().propagationFactor()).isEqualTo(0.85);
    }

    @Test
    void shouldNotEmitForDelayBelowCascadeThreshold() {
        // 20 min delay (below 30 min cascade threshold)
        Optional<CascadeAlert> alert = detector.checkCascade(delayEvent(1200));
        assertThat(alert).isEmpty();
    }

    @Test
    void shouldNotEmitForNullDelay() {
        var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, null, null,
                DelayClassification.UNKNOWN, "UNRESOLVED", Instant.now());
        assertThat(detector.checkCascade(event)).isEmpty();
    }

    @Test
    void shouldEmitAtExactCascadeThreshold() {
        // 30 min delay → predicted = 25.5 min (1800 * 0.85 = 1530s = 25.5 min > 15 min)
        Optional<CascadeAlert> alert = detector.checkCascade(delayEvent(1800));
        assertThat(alert).isPresent();
        assertThat(alert.get().predictedDownstreamDelaySeconds()).isEqualTo(1530);
    }

    @Test
    void shouldNotEmitJustBelowCascadeThreshold() {
        // 29 min = 1740s (below 30 min)
        assertThat(detector.checkCascade(delayEvent(1740))).isEmpty();
    }

    @Test
    void shouldIncludeSourceFlightDetails() {
        var alert = detector.checkCascade(delayEvent(3600)).orElseThrow();
        assertThat(alert.sourceCallsign()).isEqualTo("UAL1234");
        assertThat(alert.arrivalAirportIata()).isEqualTo("ORD");
        assertThat(alert.createdAt()).isNotNull();
    }
}
```

### Step 2: Run test to verify it fails

```bash
cd skytrack && ./mvnw test -pl . -Dtest="CascadeDetectorTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL

### Step 3: Create CascadeAlert record

```java
package skytrack.demo.model;

import java.time.Instant;

public record CascadeAlert(
        String sourceCallsign,
        String arrivalAirportIata,
        long currentDelaySeconds,
        long predictedDownstreamDelaySeconds,
        double propagationFactor,
        Instant createdAt) {}
```

### Step 4: Create CascadeDetector

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.model.CascadeAlert;
import skytrack.demo.model.DelayEvent;

import java.time.Instant;
import java.util.Optional;

@Service
@EnableConfigurationProperties(DisruptionScoreProperties.class)
public class CascadeDetector {

    private static final Logger log = LoggerFactory.getLogger(CascadeDetector.class);

    private final DisruptionScoreProperties props;

    public CascadeDetector(DisruptionScoreProperties props) {
        this.props = props;
    }

    public Optional<CascadeAlert> checkCascade(DelayEvent event) {
        if (event.delaySeconds() == null) return Optional.empty();

        long delayMinutes = event.delaySeconds() / 60;
        if (delayMinutes < props.cascadeThresholdMinutes()) return Optional.empty();

        long predictedDownstream = (long) (event.delaySeconds() * props.cascadePropagationFactor());

        if (predictedDownstream / 60 < props.delayThresholdMinutes()) return Optional.empty();

        var alert = new CascadeAlert(
                event.callsign(),
                event.arrivalAirportIata(),
                event.delaySeconds(),
                predictedDownstream,
                props.cascadePropagationFactor(),
                Instant.now());

        log.info("Cascade alert: {} at {} delay={}min -> predicted downstream={}min",
                event.callsign(), event.arrivalAirportIata(),
                delayMinutes, predictedDownstream / 60);

        return Optional.of(alert);
    }
}
```

### Step 5: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="CascadeDetectorTest"
```

Expected: PASS

### Step 6: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/model/CascadeAlert.java \
  skytrack/src/main/java/skytrack/demo/service/CascadeDetector.java \
  skytrack/src/test/java/skytrack/demo/service/CascadeDetectorTest.java
git commit -m "feat: add CascadeDetector for downstream delay prediction"
```

---

## Task 7: DelayEventProcessor

Orchestrator that ties together the delay pipeline. Receives a `ResolvedArrival` from the handler, runs it through `DelayComputer` → `DisruptionScoreService` → `SqsAirportEventProducer` → `CascadeDetector`. This keeps `StatefulFlightPositionHandler` clean with only one new dependency.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/DelayEventProcessor.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/DelayEventProcessorTest.java`

### Step 1: Write the failing test

**`DelayEventProcessorTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.model.*;
import skytrack.demo.sqs.SqsAirportEventProducer;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DelayEventProcessorTest {

    @Mock private DelayComputer delayComputer;
    @Mock private DisruptionScoreService disruptionScoreService;
    @Mock private SqsAirportEventProducer eventProducer;
    @Mock private CascadeDetector cascadeDetector;

    private DelayEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DelayEventProcessor(
                delayComputer, disruptionScoreService, eventProducer, cascadeDetector);
    }

    private ResolvedArrival resolvedArrival(long delaySeconds) {
        return new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L,
                1709312400L - delaySeconds, delaySeconds, "AEROAPI");
    }

    private DelayEvent delayEvent(long delaySeconds) {
        return new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L,
                1709312400L - delaySeconds, delaySeconds,
                DelayClassification.fromDelaySeconds(delaySeconds),
                "AEROAPI", Instant.now());
    }

    @Test
    void shouldProcessArrivalThroughFullPipeline() {
        var arrival = resolvedArrival(900);
        var event = delayEvent(900);

        when(delayComputer.compute(arrival)).thenReturn(event);
        when(cascadeDetector.checkCascade(event)).thenReturn(Optional.empty());

        processor.process(arrival);

        verify(delayComputer).compute(arrival);
        verify(disruptionScoreService).recordDelay(event);
        verify(eventProducer).send(event);
        verify(cascadeDetector).checkCascade(event);
    }

    @Test
    void shouldHandleCascadeAlert() {
        var arrival = resolvedArrival(3600);
        var event = delayEvent(3600);
        var alert = new CascadeAlert("UAL1234", "ORD", 3600, 3060, 0.85, Instant.now());

        when(delayComputer.compute(arrival)).thenReturn(event);
        when(cascadeDetector.checkCascade(event)).thenReturn(Optional.of(alert));

        processor.process(arrival);

        verify(cascadeDetector).checkCascade(event);
        // Cascade alert is logged — no additional side effect to verify beyond the call
    }

    @Test
    void shouldProcessUnresolvedArrival() {
        var arrival = new ResolvedArrival("abc123", "ZZZ999", null, null,
                "KORD", "ORD", 1709312400L, null, null, "UNRESOLVED");
        var event = new DelayEvent("abc123", "ZZZ999", null, null,
                "KORD", "ORD", 1709312400L, null, null,
                DelayClassification.UNKNOWN, "UNRESOLVED", Instant.now());

        when(delayComputer.compute(arrival)).thenReturn(event);
        when(cascadeDetector.checkCascade(event)).thenReturn(Optional.empty());

        processor.process(arrival);

        verify(disruptionScoreService).recordDelay(event);
        verify(eventProducer).send(event);
    }
}
```

### Step 2: Run test to verify it fails

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DelayEventProcessorTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL

### Step 3: Create DelayEventProcessor

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.sqs.SqsAirportEventProducer;

@Service
public class DelayEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(DelayEventProcessor.class);

    private final DelayComputer delayComputer;
    private final DisruptionScoreService disruptionScoreService;
    private final SqsAirportEventProducer eventProducer;
    private final CascadeDetector cascadeDetector;

    public DelayEventProcessor(DelayComputer delayComputer,
                               DisruptionScoreService disruptionScoreService,
                               SqsAirportEventProducer eventProducer,
                               CascadeDetector cascadeDetector) {
        this.delayComputer = delayComputer;
        this.disruptionScoreService = disruptionScoreService;
        this.eventProducer = eventProducer;
        this.cascadeDetector = cascadeDetector;
    }

    public void process(ResolvedArrival arrival) {
        var delayEvent = delayComputer.compute(arrival);

        disruptionScoreService.recordDelay(delayEvent);
        eventProducer.send(delayEvent);

        cascadeDetector.checkCascade(delayEvent).ifPresent(alert ->
                log.info("Cascade risk: {} at {} predicted downstream delay={}min",
                        alert.sourceCallsign(), alert.arrivalAirportIata(),
                        alert.predictedDownstreamDelaySeconds() / 60));

        log.debug("Processed delay event: {} {} at {} classification={} delay={}s",
                delayEvent.carrierCode(), delayEvent.flightNumber(),
                delayEvent.arrivalAirportIata(), delayEvent.classification(),
                delayEvent.delaySeconds());
    }
}
```

### Step 4: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="DelayEventProcessorTest"
```

Expected: PASS

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/service/DelayEventProcessor.java \
  skytrack/src/test/java/skytrack/demo/service/DelayEventProcessorTest.java
git commit -m "feat: add DelayEventProcessor orchestrator"
```

---

## Task 8: Wire DelayEventProcessor into StatefulFlightPositionHandler

Add `DelayEventProcessor` as a dependency of `StatefulFlightPositionHandler`. Replace the inline log statement after schedule resolution with a call to `delayEventProcessor.process(resolved)`. Update the existing test.

**Files:**
- Modify: `skytrack/src/main/java/skytrack/demo/service/StatefulFlightPositionHandler.java`
- Modify: `skytrack/src/test/java/skytrack/demo/service/StatefulFlightPositionHandlerTest.java`

### Step 1: Update StatefulFlightPositionHandlerTest

Replace the existing test file with the updated version that includes the new `DelayEventProcessor` dependency:

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
    @Mock private DelayEventProcessor delayEventProcessor;

    private StatefulFlightPositionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StatefulFlightPositionHandler(
                repository, stateMachine, scheduleResolver, delayEventProcessor);
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
        verify(delayEventProcessor, never()).process(any());
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
    void shouldCallScheduleResolverAndDelayProcessorOnLanding() {
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
        verify(delayEventProcessor).process(resolved);
    }

    @Test
    void shouldContinueProcessingAfterErrorOnOnePosition() {
        when(repository.findByIcao24("bad123")).thenThrow(new RuntimeException("DynamoDB error"));

        var track = AircraftTrack.initial("good456");
        when(repository.findByIcao24("good456")).thenReturn(Optional.of(track));
        var result = new StateTransitionResult(track, Optional.empty());
        when(stateMachine.process(eq(track), any())).thenReturn(result);

        handler.handle(List.of(
                position("bad123", "ERR1"),
                position("good456", "OK1")
        ));

        verify(repository).save(track);
    }
}
```

### Step 2: Run test to verify it fails

```bash
cd skytrack && ./mvnw test -pl . -Dtest="StatefulFlightPositionHandlerTest"
```

Expected: FAIL — constructor signature mismatch (3 args vs 4).

### Step 3: Update StatefulFlightPositionHandler

Replace the contents of `skytrack/src/main/java/skytrack/demo/service/StatefulFlightPositionHandler.java`:

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
    private final DelayEventProcessor delayEventProcessor;

    public StatefulFlightPositionHandler(AircraftTrackRepository repository,
                                         AircraftStateMachine stateMachine,
                                         ScheduleResolver scheduleResolver,
                                         DelayEventProcessor delayEventProcessor) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.scheduleResolver = scheduleResolver;
        this.delayEventProcessor = delayEventProcessor;
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
                    delayEventProcessor.process(resolved);
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

### Step 4: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="StatefulFlightPositionHandlerTest"
```

Expected: PASS

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/service/StatefulFlightPositionHandler.java \
  skytrack/src/test/java/skytrack/demo/service/StatefulFlightPositionHandlerTest.java
git commit -m "feat: wire DelayEventProcessor into StatefulFlightPositionHandler"
```

---

## Task 9: Update FlightPipelineIntegrationTest

The existing `FlightPipelineIntegrationTest` from Chunk 4 creates a `StatefulFlightPositionHandler` with 3 constructor args. Update it to 4 args and add delay-specific assertions. This test uses a mock `SqsAirportEventProducer` to avoid needing a second SQS queue in the same Testcontainer.

**Files:**
- Modify: `skytrack/src/test/java/skytrack/demo/service/FlightPipelineIntegrationTest.java`

### Step 1: Update FlightPipelineIntegrationTest

Replace the contents of the test with the updated version:

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
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.*;
import skytrack.demo.repository.AircraftTrackRepository;
import skytrack.demo.sqs.SqsAirportEventProducer;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    private static DisruptionScoreService disruptionScoreService;
    private static SqsAirportEventProducer mockEventProducer;

    @BeforeAll
    static void setUp() throws Exception {
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

        var airportLookup = new AirportLookupService("data/airports/airports.csv");
        airportLookup.loadAirports();

        var smProps = new StateMachineProperties(150.0, 50.0, 5.0, 300);
        var stateMachine = new AircraftStateMachine(airportLookup, smProps);
        var callsignParser = new CallsignParser();
        var routeAverageEstimator = new RouteAverageEstimator();

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

        // Delay pipeline
        var delayComputer = new DelayComputer();
        var disruptionProps = new DisruptionScoreProperties(60, 1, 15, 30, 0.85);
        disruptionScoreService = new DisruptionScoreService(disruptionProps);
        mockEventProducer = mock(SqsAirportEventProducer.class);
        var cascadeDetector = new CascadeDetector(disruptionProps);
        var delayEventProcessor = new DelayEventProcessor(
                delayComputer, disruptionScoreService, mockEventProducer, cascadeDetector);

        handler = new StatefulFlightPositionHandler(
                repository, stateMachine, scheduleResolver, delayEventProcessor);
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

        // Verify delay event was published to SQS
        verify(mockEventProducer).send(any(DelayEvent.class));
    }

    @Test
    void shouldTransitionToDepartedWhenTakingOff() {
        String icao24 = "integ-test-2";
        long t = Instant.parse("2026-03-15T18:00:00Z").getEpochSecond();

        handler.handle(List.of(new FlightPosition(
                icao24, "DAL567", 41.9742, -87.9073, 0.0, 0.0, 0.0,
                true, t, t - 5, Instant.ofEpochSecond(t))));

        var track1 = repository.findByIcao24(icao24);
        assertThat(track1.get().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);

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

        handler.handle(List.of(new FlightPosition(
                icao24, "ZZZ999", 40.0, -90.0, 10000.0, 450.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));

        var track = repository.findByIcao24(icao24);
        assertThat(track).isPresent();
        assertThat(track.get().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
    }

    @Test
    void shouldUpdateDisruptionScoreAfterLanding() {
        String icao24 = "integ-test-4";
        long t = Instant.parse("2026-03-15T20:00:00Z").getEpochSecond();

        // Fly and land at ORD
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 40.0, -90.0, 10000.0, 450.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));
        t += 60;
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 42.1, -87.8, 3000.0, 250.0, 45.0,
                false, t, t - 5, Instant.ofEpochSecond(t))));
        t += 60;
        handler.handle(List.of(new FlightPosition(
                icao24, "UAL1234", 41.9742, -87.9073, 0.0, 0.0, 0.0,
                true, t, t - 5, Instant.ofEpochSecond(t))));

        // Verify disruption score was updated for ORD
        var score = disruptionScoreService.computeScore("ORD");
        assertThat(score.totalFlightsInWindow()).isGreaterThanOrEqualTo(1);
    }
}
```

### Step 2: Run tests to verify they pass

```bash
cd skytrack && ./mvnw test -pl . -Dtest="FlightPipelineIntegrationTest"
```

Expected: PASS

### Step 3: Commit

```bash
git add skytrack/src/test/java/skytrack/demo/service/FlightPipelineIntegrationTest.java
git commit -m "feat: update FlightPipelineIntegrationTest for delay detection pipeline"
```

---

## Task 10: Final Verification & Cleanup

### Step 1: Run full test suite

```bash
cd skytrack && ./mvnw clean test
```

All tests must pass. Common issues to watch for:

- **`SqsConsumerServiceTest`**: If it mocks `FlightPositionHandler`, it should still pass since the mock doesn't depend on constructor args.
- **`DemoApplicationTests` (Spring context test)**: May fail if the new `SqsAirportEventProducer` bean can't be created (requires SQS connection). Add test properties or `@MockBean` as needed.
- **Existing integration tests**: Check that Chunk 4 tests still pass with the updated handler constructor.

If `DemoApplicationTests` fails because of the SQS bean, add a test application properties or mock. This is the same issue Chunk 4 may have addressed — follow the existing pattern.

### Step 2: Verify docker-compose still works

```bash
docker compose up -d
sleep 5
aws --endpoint-url=http://localhost:4566 dynamodb list-tables --region us-east-1
aws --endpoint-url=http://localhost:4566 sqs list-queues --region us-east-1
docker compose down
```

Expected: Both `skytrack-aircraft` table and both FIFO queues (`skytrack-positions.fifo`, `skytrack-airport-events.fifo`) are present.

### Step 3: Commit any remaining fixes

```bash
git add -A
git commit -m "chore: Chunk 5 cleanup and verification"
```

---

## Summary

### New Files (9 source + 9 test = 18 total)

| File | Package | Type |
|------|---------|------|
| `model/DelayClassification.java` | model | Enum |
| `model/DelayEvent.java` | model | Record |
| `model/AirportDisruptionScore.java` | model | Record |
| `model/CascadeAlert.java` | model | Record |
| `config/DisruptionScoreProperties.java` | config | Properties |
| `service/DelayComputer.java` | service | Service |
| `service/DisruptionScoreService.java` | service | Service |
| `service/CascadeDetector.java` | service | Service |
| `service/DelayEventProcessor.java` | service | Service |
| `sqs/SqsAirportEventProducer.java` | sqs | Producer |

### Modified Files

| File | Change |
|------|--------|
| `service/StatefulFlightPositionHandler.java` | Add `DelayEventProcessor` dependency, replace log with `process()` |
| `config/SqsConfig.java` | Add `sqsAirportEventProducer` bean |
| `application.yml` | Add `skytrack.disruption` properties |

### Test Files

| File | Type |
|------|------|
| `model/DelayClassificationTest.java` | Unit |
| `model/DelayEventTest.java` | Unit |
| `model/AirportDisruptionScoreTest.java` | Unit |
| `config/DisruptionScorePropertiesTest.java` | Unit |
| `service/DelayComputerTest.java` | Unit |
| `service/DisruptionScoreServiceTest.java` | Unit |
| `service/CascadeDetectorTest.java` | Unit |
| `service/DelayEventProcessorTest.java` | Unit (Mockito) |
| `service/StatefulFlightPositionHandlerTest.java` | Unit (Mockito, updated) |
| `service/FlightPipelineIntegrationTest.java` | Integration (updated) |
| `sqs/SqsAirportEventProducerTest.java` | Integration (LocalStack) |

### Data Flow After Chunk 5

```
FlightPollingService (30s)
  → FlightDataSource (replay/live)
  → SqsPositionProducer → skytrack-positions.fifo
  → SqsPositionConsumer
  → StatefulFlightPositionHandler
      → AircraftStateMachine.process(track, position)
      → [on landing] ScheduleResolver.resolve(landingEvent) → ResolvedArrival
      → [on landing] DelayEventProcessor.process(resolvedArrival)
          → DelayComputer.compute() → DelayEvent (classified)
          → DisruptionScoreService.recordDelay() (sliding window update)
          → SqsAirportEventProducer.send() → skytrack-airport-events.fifo
          → CascadeDetector.checkCascade() → Optional<CascadeAlert>
      → AircraftTrackRepository.save(track) → DynamoDB
```

### Risks & Notes

1. **Sliding window uses event timestamps, not wall clock:** The window reference point is the latest event's timestamp. This ensures correct scoring during replay with `ReplayOpenSkyClient`. In production, event timestamps and wall clock will be nearly identical.
2. **BucketMetrics is not thread-safe internally:** The `record()` method on `BucketMetrics` modifies mutable int/long fields. This is safe because `computeIfAbsent` on `ConcurrentHashMap` + `TreeMap` ensures only one thread creates a bucket, but concurrent writes to the same bucket could lose updates. At the expected event rate (~1 landing per second), this race is extremely unlikely. For production hardening, `BucketMetrics` fields could use `AtomicInteger`/`AtomicLong`.
3. **SqsAirportEventProducer failures don't block the pipeline:** The `send()` method catches all exceptions and logs them. A failed publish does not prevent the disruption score from being updated or the track from being saved.
4. **Cascade detection is stateless:** The current `CascadeDetector` makes a per-event prediction using a fixed propagation factor. It does not track multi-leg cascade chains across aircraft. This is sufficient for demonstrating the concept; a production system would query AeroAPI for the aircraft's next scheduled departure.
5. **DisruptionScoreService is in-memory only:** Scores are lost on restart. A future chunk could persist scores to DynamoDB for durability and for the REST API to query.
