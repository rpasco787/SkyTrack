# Chunk 2: LocalStack + SQS Pipeline — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give flight position data somewhere to flow — from the polling service through an SQS FIFO queue to a consumer, all running locally via LocalStack.

**Architecture:** The `FlightPollingService` (Chunk 1) currently fetches positions and logs them. We insert an SQS producer between polling and processing: polled positions are serialized to JSON and published in batches to a FIFO queue (`skytrack-positions.fifo`). A separate consumer polls the queue, deserializes messages, and delegates to a `FlightPositionHandler` interface (which just logs in this chunk, but becomes the state machine entry point in Chunk 3). A second FIFO queue (`skytrack-airport-events.fifo`) is created now but not used until Chunk 5. LocalStack runs in Docker via `docker-compose.yml`; the Spring Boot app runs locally against it.

**Tech Stack:** AWS SDK v2 (`SqsClient`, synchronous), LocalStack in Docker, Testcontainers for integration tests, Jackson 3.x (`tools.jackson`) for JSON serialization, Spring Boot 4.0.2, Java 25.

**Design Decisions:**
- **Raw AWS SDK v2** over Spring Cloud AWS — explicit control, no annotation magic, easier to debug against LocalStack.
- **`FlightPositionHandler` interface** — the consumer delegates to this instead of hardcoding behavior. In Chunk 2 it logs; in Chunk 3 it feeds the state machine. One interface now prevents a consumer rewrite later.
- **Testcontainers** for integration tests — spins up LocalStack in Docker automatically per test class, no manual `docker compose up` required.
- **LocalStack only in Docker** — Spring Boot runs on host for fast iteration. Full containerization comes in Chunk 6.
- **FIFO queues** — ordered per-aircraft (MessageGroupId = `icao24`), exactly-once delivery via content-based deduplication.

> **Commits:** This plan does NOT include git commit steps. The developer commits manually after each task at their own discretion.

---

## Project Context

- **Module root:** `skytrack/` (Maven project, `pom.xml` at `skytrack/pom.xml`)
- **Base package:** `skytrack.demo`
- **Source root:** `skytrack/src/main/java/skytrack/demo/`
- **Test root:** `skytrack/src/test/java/skytrack/demo/`
- **Resources:** `skytrack/src/main/resources/`
- **Existing key files:**
  - `skytrack/src/main/java/skytrack/demo/model/FlightPosition.java` — core data record
  - `skytrack/src/main/java/skytrack/demo/service/FlightPollingService.java` — scheduled poller
  - `skytrack/src/main/java/skytrack/demo/config/FlightDataSourceConfig.java` — data source bean factory
  - `skytrack/src/main/java/skytrack/demo/config/OpenSkyProperties.java` — config properties
  - `skytrack/src/main/resources/application.yml` — base config
  - `skytrack/src/main/resources/application-local.yml` — local profile config

---

## Task 1: Create `docker-compose.yml` with LocalStack and init script

LocalStack provides SQS and DynamoDB emulation in a single container. An init script auto-creates both FIFO queues on startup so you never have to create them manually.

**Why FIFO queues?** Standard SQS queues deliver messages at-least-once in undefined order. FIFO queues guarantee per-group ordering (we group by `icao24` so all position updates for a single aircraft arrive in order) and exactly-once processing. This matters because the state machine in Chunk 3 depends on seeing position updates in chronological order to detect transitions (e.g., altitude dropping = approaching, onGround flipping = landed).

**Why two queues now?** `skytrack-positions.fifo` is used immediately. `skytrack-airport-events.fifo` is consumed in Chunk 5 (delay events). Creating both now means the init script won't need revisiting.

**Files:**
- Create: `docker-compose.yml` (project root)
- Create: `localstack/init-aws.sh` (init script)

**Step 1: Create the LocalStack init script directory**

Run:
```bash
mkdir -p localstack
```

**Step 2: Write the init script**

Create `localstack/init-aws.sh`:

```bash
#!/bin/bash
echo "Creating SQS FIFO queues..."

awslocal sqs create-queue \
  --queue-name skytrack-positions.fifo \
  --attributes '{
    "FifoQueue": "true",
    "ContentBasedDeduplication": "true",
    "VisibilityTimeout": "30"
  }'

awslocal sqs create-queue \
  --queue-name skytrack-airport-events.fifo \
  --attributes '{
    "FifoQueue": "true",
    "ContentBasedDeduplication": "true",
    "VisibilityTimeout": "30"
  }'

echo "Queues created:"
awslocal sqs list-queues
```

**Why `ContentBasedDeduplication`?** FIFO queues require a deduplication mechanism. With this enabled, SQS generates a dedup ID from an SHA-256 hash of the message body. We also set an explicit `MessageDeduplicationId` (icao24 + timestamp) in the producer for belt-and-suspenders safety — but having content-based dedup as a fallback means a duplicate message with the same body won't be delivered twice even if we mess up the dedup ID.

**Why `VisibilityTimeout: 30`?** When a consumer receives a message, it becomes invisible to other consumers for this many seconds. 30 seconds gives the consumer time to process and delete. If processing fails and the message isn't deleted, it reappears after 30 seconds for retry.

**Step 3: Make the init script executable**

Run:
```bash
chmod +x localstack/init-aws.sh
```

**Step 4: Write the Docker Compose file**

Create `docker-compose.yml` (project root):

```yaml
services:
  localstack:
    image: localstack/localstack:latest
    ports:
      - "4566:4566"
    environment:
      - SERVICES=sqs,dynamodb
      - DEFAULT_REGION=us-east-1
    volumes:
      - "./localstack/init-aws.sh:/etc/localstack/init/ready.d/init-aws.sh"
      - "/var/run/docker.sock:/var/run/docker.sock"
```

**Why `/etc/localstack/init/ready.d/`?** LocalStack executes scripts in this directory after all services are ready. This guarantees SQS is available before the script runs. Using `ready.d` (not `boot.d`) avoids race conditions.

**Why `SERVICES=sqs,dynamodb`?** Limits LocalStack to only the services we need, reducing memory usage. DynamoDB is added now because Chunk 3 needs it — no docker-compose change required later.

**Step 5: Verify LocalStack starts and queues are created**

Run:
```bash
docker compose up -d && sleep 5 && aws --endpoint-url=http://localhost:4566 sqs list-queues --region us-east-1
```
Expected: Output includes both queue URLs:
```json
{
  "QueueUrls": [
    "http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/skytrack-positions.fifo",
    "http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/skytrack-airport-events.fifo"
  ]
}
```

**Step 6: Clean up**

Run:
```bash
docker compose down
```

---

## Task 2: Add AWS SDK v2 and Testcontainers dependencies

AWS SDK v2 uses a BOM (Bill of Materials) for version management — you declare the BOM once and then add individual service modules without specifying versions. This prevents version conflicts between SDK modules.

**Files:**
- Modify: `skytrack/pom.xml`

**Step 1: Add the AWS SDK BOM and SQS dependency, plus Testcontainers**

Add the following to `skytrack/pom.xml`.

In the `<properties>` section, add version properties:

```xml
<properties>
    <java.version>25</java.version>
    <aws-sdk.version>2.31.9</aws-sdk.version>
    <testcontainers.version>1.21.0</testcontainers.version>
</properties>
```

Add a `<dependencyManagement>` section (before `<dependencies>`):

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>bom</artifactId>
            <version>${aws-sdk.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-bom</artifactId>
            <version>${testcontainers.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Add these to the `<dependencies>` section:

```xml
<!-- AWS SDK v2 - SQS -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>sqs</artifactId>
</dependency>

<!-- Testcontainers - LocalStack -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>localstack</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

**Why a BOM?** Without a BOM, you'd have to manually keep `sqs`, `sts`, `dynamodb`, etc. all on the same version. The BOM centralizes this. When we add DynamoDB in Chunk 3, we just add the artifact — no version to specify.

**Step 2: Verify dependencies resolve**

Run:
```bash
cd skytrack && mvn dependency:resolve -q && echo "Dependencies resolved successfully"
```
Expected: `Dependencies resolved successfully` (no errors).

**Step 3: Verify SQS client is on classpath**

Run:
```bash
cd skytrack && mvn dependency:tree | grep -E "(sqs|localstack|testcontainers)"
```
Expected: Lines showing `software.amazon.awssdk:sqs`, `org.testcontainers:localstack`, `org.testcontainers:junit-jupiter`.

---

## Task 3: Create SQS configuration and client factory

We need a Spring-managed `SqsClient` bean that points to LocalStack in the `local` profile and to real AWS in `prod`. A config properties record holds the SQS-specific settings (endpoint, queue names, region).

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/config/SqsProperties.java`
- Create: `skytrack/src/main/java/skytrack/demo/config/SqsConfig.java`
- Modify: `skytrack/src/main/resources/application.yml`
- Modify: `skytrack/src/main/resources/application-local.yml`
- Modify: `skytrack/src/main/resources/application-prod.yml`
- Test: `skytrack/src/test/java/skytrack/demo/config/SqsConfigTest.java`

**Step 1: Write the failing test**

```java
package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

import static org.assertj.core.api.Assertions.assertThat;

class SqsConfigTest {

    @Test
    void shouldCreateSqsClientWithLocalStackEndpoint() {
        var props = new SqsProperties(
                "http://localhost:4566",
                "us-east-1",
                "skytrack-positions.fifo",
                "skytrack-airport-events.fifo"
        );
        var config = new SqsConfig();

        SqsClient client = config.sqsClient(props);

        assertThat(client).isNotNull();
        client.close();
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.config.SqsConfigTest" -Dsurefire.failIfNoTests=false
```
Expected: Compilation failure — `SqsProperties` and `SqsConfig` do not exist.

**Step 3: Write `SqsProperties`**

```java
package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sqs")
public record SqsProperties(
        String endpoint,
        String region,
        String positionsQueueName,
        String airportEventsQueueName
) {
    public SqsProperties {
        if (region == null) region = "us-east-1";
        if (positionsQueueName == null) positionsQueueName = "skytrack-positions.fifo";
        if (airportEventsQueueName == null) airportEventsQueueName = "skytrack-airport-events.fifo";
    }
}
```

**Why a separate properties record?** Keeps SQS config isolated from OpenSky config. Each `@ConfigurationProperties` record maps to a distinct YAML prefix (`sqs.*` vs `opensky.*`), making it obvious where each setting lives.

**Step 4: Write `SqsConfig`**

```java
package skytrack.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Configuration
public class SqsConfig {

    @Bean
    public SqsClient sqsClient(SqsProperties properties) {
        var builder = SqsClient.builder()
                .region(Region.of(properties.region()));

        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }

        return builder.build();
    }
}
```

**Why `StaticCredentialsProvider` with dummy creds?** LocalStack doesn't validate credentials, but the AWS SDK still requires them. We use dummy values (`"test"/"test"`) only when an endpoint override is set (i.e., local dev). In production (no endpoint override), the SDK uses the default credential chain (IAM role, env vars, etc.).

**Step 5: Update YAML config files**

Add to `skytrack/src/main/resources/application.yml` (after the existing content):

```yaml
sqs:
  positions-queue-name: skytrack-positions.fifo
  airport-events-queue-name: skytrack-airport-events.fifo
```

Add to `skytrack/src/main/resources/application-local.yml` (after the existing content):

```yaml
sqs:
  endpoint: http://localhost:4566
  region: us-east-1
```

Add to `skytrack/src/main/resources/application-prod.yml` (after the existing content):

```yaml
sqs:
  region: ${AWS_REGION:us-east-1}
```

**Step 6: Run test to verify it passes**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.config.SqsConfigTest"
```
Expected: 1 test PASSES.

---

## Task 4: Define the `FlightPositionHandler` interface

This is the extensibility point between the SQS consumer and downstream processing. In Chunk 2, the handler logs. In Chunk 3, the handler feeds the aircraft state machine. The consumer never changes.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/FlightPositionHandler.java`
- Create: `skytrack/src/main/java/skytrack/demo/service/LoggingFlightPositionHandler.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/LoggingFlightPositionHandlerTest.java`

**Step 1: Write the failing test**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.FlightPosition;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingFlightPositionHandlerTest {

    @Test
    void shouldHandlePositionsWithoutThrowing() {
        var handler = new LoggingFlightPositionHandler();
        var positions = List.of(
                new FlightPosition("abc123", "UAL1234", 41.97, -87.91,
                        10668.0, 230.5, 270.0, false,
                        1709312400L, 1709312400L, Instant.now())
        );

        assertThatCode(() -> handler.handle(positions))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldHandleEmptyList() {
        var handler = new LoggingFlightPositionHandler();

        assertThatCode(() -> handler.handle(List.of()))
                .doesNotThrowAnyException();
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.service.LoggingFlightPositionHandlerTest" -Dsurefire.failIfNoTests=false
```
Expected: Compilation failure — `FlightPositionHandler` and `LoggingFlightPositionHandler` do not exist.

**Step 3: Write the interface**

```java
package skytrack.demo.service;

import skytrack.demo.model.FlightPosition;

import java.util.List;

public interface FlightPositionHandler {

    void handle(List<FlightPosition> positions);
}
```

**Why `List<FlightPosition>` instead of single?** The consumer receives messages in batches (up to 10). Passing the batch preserves that grouping for downstream processing — the state machine in Chunk 3 can process a batch atomically rather than one-at-a-time.

**Step 4: Write the logging implementation**

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import skytrack.demo.model.FlightPosition;

import java.util.List;

@Component
public class LoggingFlightPositionHandler implements FlightPositionHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingFlightPositionHandler.class);

    @Override
    public void handle(List<FlightPosition> positions) {
        log.info("Received {} flight positions", positions.size());
        for (FlightPosition fp : positions) {
            log.debug("  {} ({}) at [{}, {}] alt={}m onGround={}",
                    fp.callsign(), fp.icao24(),
                    fp.latitude(), fp.longitude(),
                    fp.baroAltitude(), fp.onGround());
        }
    }
}
```

**Step 5: Run test to verify it passes**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.service.LoggingFlightPositionHandlerTest"
```
Expected: 2 tests PASS.

---

## Task 5: Build the SQS producer

The producer serializes `FlightPosition` records to JSON and publishes them in batches of up to 10 to the FIFO queue. It uses `MessageGroupId = icao24` for per-aircraft ordering and `MessageDeduplicationId = icao24 + "-" + timePosition` for exactly-once delivery.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/sqs/SqsPositionProducer.java`
- Test: `skytrack/src/test/java/skytrack/demo/sqs/SqsPositionProducerTest.java`

**Step 1: Write the failing test (unit test with mocked SqsClient)**

```java
package skytrack.demo.sqs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.model.FlightPosition;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsPositionProducerTest {

    @Mock
    private SqsClient sqsClient;

    private SqsPositionProducer producer;

    @BeforeEach
    void setUp() {
        producer = new SqsPositionProducer(sqsClient, "http://localhost:4566/000000000000/skytrack-positions.fifo");
    }

    @Test
    void shouldSendSingleBatchForUpTo10Positions() {
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenReturn(SendMessageBatchResponse.builder().build());

        var positions = List.of(
                makePosition("abc123", "UAL1234", 1709312400L),
                makePosition("def456", "DAL567", 1709312400L)
        );

        producer.send(positions);

        var captor = ArgumentCaptor.forClass(SendMessageBatchRequest.class);
        verify(sqsClient, times(1)).sendMessageBatch(captor.capture());

        SendMessageBatchRequest request = captor.getValue();
        assertThat(request.entries()).hasSize(2);
        assertThat(request.entries().get(0).messageGroupId()).isEqualTo("abc123");
        assertThat(request.entries().get(1).messageGroupId()).isEqualTo("def456");
    }

    @Test
    void shouldSplitIntoMultipleBatchesWhenOver10() {
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenReturn(SendMessageBatchResponse.builder().build());

        List<FlightPosition> positions = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            positions.add(makePosition("icao" + i, "CALL" + i, 1709312400L + i));
        }

        producer.send(positions);

        verify(sqsClient, times(2)).sendMessageBatch(any(SendMessageBatchRequest.class));
    }

    @Test
    void shouldSetDeduplicationId() {
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenReturn(SendMessageBatchResponse.builder().build());

        var positions = List.of(makePosition("abc123", "UAL1234", 1709312400L));

        producer.send(positions);

        var captor = ArgumentCaptor.forClass(SendMessageBatchRequest.class);
        verify(sqsClient).sendMessageBatch(captor.capture());

        String dedupId = captor.getValue().entries().get(0).messageDeduplicationId();
        assertThat(dedupId).isEqualTo("abc123-1709312400");
    }

    @Test
    void shouldNotCallSqsForEmptyList() {
        producer.send(List.of());

        verifyNoInteractions(sqsClient);
    }

    private FlightPosition makePosition(String icao, String callsign, long timePosition) {
        return new FlightPosition(icao, callsign, 41.97, -87.91,
                10668.0, 230.5, 270.0, false,
                timePosition, timePosition, Instant.now());
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.sqs.SqsPositionProducerTest" -Dsurefire.failIfNoTests=false
```
Expected: Compilation failure — `SqsPositionProducer` does not exist.

**Step 3: Write the implementation**

```java
package skytrack.demo.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skytrack.demo.model.FlightPosition;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class SqsPositionProducer {

    private static final Logger log = LoggerFactory.getLogger(SqsPositionProducer.class);
    private static final int MAX_BATCH_SIZE = 10;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SqsClient sqsClient;
    private final String queueUrl;

    public SqsPositionProducer(SqsClient sqsClient, String queueUrl) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    public void send(List<FlightPosition> positions) {
        if (positions.isEmpty()) {
            return;
        }

        for (int i = 0; i < positions.size(); i += MAX_BATCH_SIZE) {
            List<FlightPosition> batch = positions.subList(i, Math.min(i + MAX_BATCH_SIZE, positions.size()));
            sendBatch(batch);
        }
    }

    private void sendBatch(List<FlightPosition> batch) {
        try {
            List<SendMessageBatchRequestEntry> entries = new ArrayList<>();

            for (int i = 0; i < batch.size(); i++) {
                FlightPosition fp = batch.get(i);
                String body = mapper.writeValueAsString(fp);

                entries.add(SendMessageBatchRequestEntry.builder()
                        .id(String.valueOf(i))
                        .messageBody(body)
                        .messageGroupId(fp.icao24())
                        .messageDeduplicationId(fp.icao24() + "-" + fp.timePosition())
                        .build());
            }

            sqsClient.sendMessageBatch(SendMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(entries)
                    .build());

            log.debug("Sent batch of {} positions to SQS", batch.size());
        } catch (Exception e) {
            log.error("Failed to send batch of {} positions to SQS", batch.size(), e);
        }
    }
}
```

**Why `MessageGroupId = icao24`?** FIFO queues deliver messages in order within a message group. By grouping on `icao24`, all position updates for a single aircraft arrive in order. Different aircraft can be processed in parallel — SQS FIFO supports up to 20,000 messages/second with high-throughput mode when using distinct group IDs.

**Why `MessageDeduplicationId = icao24 + "-" + timePosition`?** If the producer retries (e.g., network blip), FIFO queues detect the duplicate within a 5-minute window and suppress it. Using `icao24 + timePosition` means two distinct position reports from different aircraft at the same time don't collide, and the same aircraft can't have two position reports with the same timestamp.

**Why batch entry IDs are just `"0"`, `"1"`, etc.?** The `id` field is only used to correlate success/failure responses within a single batch call. It has no SQS-level meaning. Sequential integers are the simplest unique IDs within a batch.

**Step 4: Run test to verify it passes**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.sqs.SqsPositionProducerTest"
```
Expected: 4 tests PASS.

---

## Task 6: Build the SQS consumer

The consumer polls `skytrack-positions.fifo` with long polling, deserializes JSON messages back into `FlightPosition` records, delegates to the `FlightPositionHandler`, then deletes the messages in a batch.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/sqs/SqsPositionConsumer.java`
- Test: `skytrack/src/test/java/skytrack/demo/sqs/SqsPositionConsumerTest.java`

**Step 1: Write the failing test**

```java
package skytrack.demo.sqs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.service.FlightPositionHandler;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsPositionConsumerTest {

    @Mock
    private SqsClient sqsClient;

    @Mock
    private FlightPositionHandler handler;

    private SqsPositionConsumer consumer;

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/skytrack-positions.fifo";

    @BeforeEach
    void setUp() {
        consumer = new SqsPositionConsumer(sqsClient, QUEUE_URL, handler);
    }

    @Test
    void shouldDeserializeAndDelegateToHandler() {
        String messageBody = """
                {"icao24":"abc123","callsign":"UAL1234","latitude":41.97,"longitude":-87.91,
                 "baroAltitude":10668.0,"velocity":230.5,"heading":270.0,"onGround":false,
                 "lastContact":1709312400,"timePosition":1709312400,"parsedAt":"2026-03-01T00:00:00Z"}
                """;

        var message = Message.builder()
                .messageId("msg-1")
                .receiptHandle("receipt-1")
                .body(messageBody)
                .build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());
        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder().build());

        consumer.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FlightPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(handler).handle(captor.capture());

        List<FlightPosition> positions = captor.getValue();
        assertThat(positions).hasSize(1);
        assertThat(positions.getFirst().icao24()).isEqualTo("abc123");
        assertThat(positions.getFirst().callsign()).isEqualTo("UAL1234");
    }

    @Test
    void shouldDeleteMessagesAfterSuccessfulHandling() {
        String messageBody = """
                {"icao24":"abc123","callsign":"UAL1234","latitude":41.97,"longitude":-87.91,
                 "baroAltitude":10668.0,"velocity":230.5,"heading":270.0,"onGround":false,
                 "lastContact":1709312400,"timePosition":1709312400,"parsedAt":"2026-03-01T00:00:00Z"}
                """;

        var message = Message.builder()
                .messageId("msg-1")
                .receiptHandle("receipt-1")
                .body(messageBody)
                .build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());
        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder().build());

        consumer.poll();

        var captor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
        verify(sqsClient).deleteMessageBatch(captor.capture());

        assertThat(captor.getValue().entries()).hasSize(1);
        assertThat(captor.getValue().entries().get(0).receiptHandle()).isEqualTo("receipt-1");
    }

    @Test
    void shouldNotDeleteMessagesWhenHandlerThrows() {
        String messageBody = """
                {"icao24":"abc123","callsign":"UAL1234","latitude":41.97,"longitude":-87.91,
                 "baroAltitude":10668.0,"velocity":230.5,"heading":270.0,"onGround":false,
                 "lastContact":1709312400,"timePosition":1709312400,"parsedAt":"2026-03-01T00:00:00Z"}
                """;

        var message = Message.builder()
                .messageId("msg-1")
                .receiptHandle("receipt-1")
                .body(messageBody)
                .build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());
        doThrow(new RuntimeException("handler failed")).when(handler).handle(any());

        consumer.poll();

        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    void shouldDoNothingWhenNoMessagesReceived() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        consumer.poll();

        verifyNoInteractions(handler);
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.sqs.SqsPositionConsumerTest" -Dsurefire.failIfNoTests=false
```
Expected: Compilation failure — `SqsPositionConsumer` does not exist.

**Step 3: Write the implementation**

```java
package skytrack.demo.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.service.FlightPositionHandler;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class SqsPositionConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsPositionConsumer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final FlightPositionHandler handler;

    public SqsPositionConsumer(SqsClient sqsClient, String queueUrl, FlightPositionHandler handler) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
        this.handler = handler;
    }

    public void poll() {
        try {
            ReceiveMessageResponse response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .build());

            List<Message> messages = response.messages();
            if (messages.isEmpty()) {
                return;
            }

            log.debug("Received {} messages from SQS", messages.size());

            List<FlightPosition> positions = new ArrayList<>();
            for (Message message : messages) {
                try {
                    FlightPosition fp = mapper.readValue(message.body(), FlightPosition.class);
                    positions.add(fp);
                } catch (Exception e) {
                    log.error("Failed to deserialize message {}: {}", message.messageId(), e.getMessage());
                }
            }

            handler.handle(positions);

            deleteMessages(messages);
        } catch (SqsException e) {
            log.error("Failed to receive messages from SQS", e);
        } catch (Exception e) {
            log.error("Failed to process messages — will NOT delete from queue (messages will reappear after visibility timeout)", e);
        }
    }

    private void deleteMessages(List<Message> messages) {
        List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            entries.add(DeleteMessageBatchRequestEntry.builder()
                    .id(String.valueOf(i))
                    .receiptHandle(messages.get(i).receiptHandle())
                    .build());
        }

        sqsClient.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                .queueUrl(queueUrl)
                .entries(entries)
                .build());

        log.debug("Deleted {} messages from SQS", messages.size());
    }
}
```

**Why long polling (`waitTimeSeconds: 20`)?** Without long polling, SQS returns immediately even if no messages are available — wasting API calls and CPU. With long polling, the `receiveMessage` call blocks for up to 20 seconds (the maximum), returning as soon as a message arrives or the timeout expires. This reduces API costs and latency.

**Why delete only after handler success?** If the handler throws (e.g., state machine error in Chunk 3), we don't delete the messages. After the visibility timeout (30 seconds, set on the queue), SQS makes them available again for retry. This gives us at-least-once processing with natural retry semantics — no custom retry logic needed.

**Why deserialize-then-batch instead of handle-one-at-a-time?** Batching the deserialized positions before calling the handler means the state machine (Chunk 3) receives a coherent snapshot. If 5 of the 10 messages are for the same aircraft, the state machine can process them in order within a single `handle()` call.

**Step 4: Run test to verify it passes**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.sqs.SqsPositionConsumerTest"
```
Expected: 4 tests PASS.

---

## Task 7: Wire producer and consumer into Spring and the polling service

Now we connect the pieces:
1. Register `SqsPositionProducer` and `SqsPositionConsumer` as Spring beans.
2. Modify `FlightPollingService` to send polled positions to SQS via the producer.
3. Add a scheduled consumer polling loop.

**Files:**
- Modify: `skytrack/src/main/java/skytrack/demo/config/SqsConfig.java`
- Modify: `skytrack/src/main/java/skytrack/demo/service/FlightPollingService.java`
- Create: `skytrack/src/main/java/skytrack/demo/service/SqsConsumerService.java`
- Modify: `skytrack/src/test/java/skytrack/demo/service/FlightPollingServiceTest.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/SqsConsumerServiceTest.java`

**Step 1: Add producer and consumer beans to `SqsConfig`**

Add these methods to the existing `SqsConfig` class:

```java
@Bean
public SqsPositionProducer sqsPositionProducer(SqsClient sqsClient, SqsProperties properties) {
    String queueUrl = resolveQueueUrl(sqsClient, properties.positionsQueueName());
    return new SqsPositionProducer(sqsClient, queueUrl);
}

@Bean
public SqsPositionConsumer sqsPositionConsumer(SqsClient sqsClient, SqsProperties properties,
                                                FlightPositionHandler handler) {
    String queueUrl = resolveQueueUrl(sqsClient, properties.positionsQueueName());
    return new SqsPositionConsumer(sqsClient, queueUrl, handler);
}

private String resolveQueueUrl(SqsClient sqsClient, String queueName) {
    return sqsClient.getQueueUrl(
            software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest.builder()
                    .queueName(queueName)
                    .build()
    ).queueUrl();
}
```

Add required imports to `SqsConfig`:

```java
import skytrack.demo.service.FlightPositionHandler;
import skytrack.demo.sqs.SqsPositionConsumer;
import skytrack.demo.sqs.SqsPositionProducer;
```

**Why `resolveQueueUrl` at startup?** SQS operations use queue URLs, not names. The URL depends on the AWS account ID and region (e.g., `http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/skytrack-positions.fifo`). Resolving once at startup avoids repeated lookups and ensures the queue actually exists — if it doesn't, the app fails fast with a clear error.

**Step 2: Modify `FlightPollingService` to use the producer**

Replace the content of `FlightPollingService.java`:

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.sqs.SqsPositionProducer;

import java.util.List;

@Service
public class FlightPollingService {

    private static final Logger log = LoggerFactory.getLogger(FlightPollingService.class);

    private final FlightDataSource flightDataSource;
    private final SqsPositionProducer sqsPositionProducer;

    public FlightPollingService(FlightDataSource flightDataSource, SqsPositionProducer sqsPositionProducer) {
        this.flightDataSource = flightDataSource;
        this.sqsPositionProducer = sqsPositionProducer;
    }

    @Scheduled(fixedRate = 30_000)
    public void pollFlightData() {
        try {
            List<FlightPosition> positions = flightDataSource.fetchPositions();
            log.info("Polled {} aircraft positions", positions.size());

            if (!positions.isEmpty()) {
                sqsPositionProducer.send(positions);
                log.info("Published {} positions to SQS", positions.size());
            }
        } catch (Exception e) {
            log.error("Flight data polling failed", e);
        }
    }
}
```

**Step 3: Write the updated `FlightPollingServiceTest`**

Replace the content of `FlightPollingServiceTest.java`:

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.sqs.SqsPositionProducer;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightPollingServiceTest {

    @Mock
    private FlightDataSource flightDataSource;

    @Mock
    private SqsPositionProducer sqsPositionProducer;

    @InjectMocks
    private FlightPollingService pollingService;

    @Test
    void shouldFetchPositionsAndPublishToSqs() {
        var positions = List.of(
                new FlightPosition("abc123", "UAL1234", 41.97, -87.91,
                        10668.0, 230.5, 270.0, false,
                        1709312400L, 1709312400L, Instant.now())
        );
        when(flightDataSource.fetchPositions()).thenReturn(positions);

        pollingService.pollFlightData();

        verify(flightDataSource).fetchPositions();
        verify(sqsPositionProducer).send(positions);
    }

    @Test
    void shouldNotPublishWhenNoPositions() {
        when(flightDataSource.fetchPositions()).thenReturn(List.of());

        pollingService.pollFlightData();

        verify(flightDataSource).fetchPositions();
        verifyNoInteractions(sqsPositionProducer);
    }

    @Test
    void shouldHandleDataSourceException() {
        when(flightDataSource.fetchPositions()).thenThrow(new RuntimeException("Connection failed"));

        pollingService.pollFlightData();

        verify(flightDataSource).fetchPositions();
        verifyNoInteractions(sqsPositionProducer);
    }
}
```

**Step 4: Write the `SqsConsumerService`**

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.sqs.SqsPositionConsumer;

@Service
public class SqsConsumerService {

    private static final Logger log = LoggerFactory.getLogger(SqsConsumerService.class);

    private final SqsPositionConsumer consumer;

    public SqsConsumerService(SqsPositionConsumer consumer) {
        this.consumer = consumer;
    }

    @Scheduled(fixedDelay = 1000)
    public void consumePositions() {
        consumer.poll();
    }
}
```

**Why `fixedDelay = 1000` instead of `fixedRate`?** `fixedDelay` waits 1 second *after* the previous `poll()` completes. Since `poll()` itself blocks for up to 20 seconds (long polling), the effective interval is 21 seconds when the queue is empty, or ~1 second when messages are flowing. `fixedRate` would try to maintain exact intervals, which doesn't make sense for a blocking operation.

**Step 5: Write the `SqsConsumerServiceTest`**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.sqs.SqsPositionConsumer;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SqsConsumerServiceTest {

    @Mock
    private SqsPositionConsumer consumer;

    @InjectMocks
    private SqsConsumerService service;

    @Test
    void shouldDelegateToConsumer() {
        service.consumePositions();

        verify(consumer).poll();
    }
}
```

**Step 6: Run all tests to verify**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.service.FlightPollingServiceTest,skytrack.demo.service.SqsConsumerServiceTest"
```
Expected: All tests PASS (3 + 1 = 4 tests).

---

## Task 8: Integration test with Testcontainers

This test spins up a real LocalStack container, creates the FIFO queue, sends positions through the producer, and reads them back with the consumer. This validates the full round-trip: serialization → SQS FIFO → deserialization.

**Files:**
- Create: `skytrack/src/test/java/skytrack/demo/sqs/SqsRoundTripIntegrationTest.java`

**Step 1: Write the integration test**

```java
package skytrack.demo.sqs;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.service.FlightPositionHandler;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SqsRoundTripIntegrationTest {

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices(LocalStackContainer.Service.SQS);

    private static SqsClient sqsClient;
    private static String queueUrl;

    @BeforeAll
    static void setUp() {
        sqsClient = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();

        // Create FIFO queue
        sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName("skytrack-positions.fifo")
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                .build());

        queueUrl = sqsClient.getQueueUrl(GetQueueUrlRequest.builder()
                .queueName("skytrack-positions.fifo")
                .build()).queueUrl();
    }

    @AfterAll
    static void tearDown() {
        if (sqsClient != null) {
            sqsClient.close();
        }
    }

    @Test
    void shouldRoundTripFlightPositionsThroughSqs() {
        // Arrange: create producer and a capturing handler
        var producer = new SqsPositionProducer(sqsClient, queueUrl);
        List<FlightPosition> received = new ArrayList<>();
        FlightPositionHandler capturingHandler = received::addAll;
        var consumer = new SqsPositionConsumer(sqsClient, queueUrl, capturingHandler);

        var original = List.of(
                new FlightPosition("abc123", "UAL1234", 41.9742, -87.9073,
                        10668.0, 230.5, 270.0, false,
                        1709312400L, 1709312400L, Instant.parse("2026-03-01T00:00:00Z")),
                new FlightPosition("def456", "DAL567", 40.6413, -73.7781,
                        0.0, 0.0, 0.0, true,
                        1709312400L, 1709312400L, Instant.parse("2026-03-01T00:00:00Z"))
        );

        // Act: produce and consume
        producer.send(original);
        consumer.poll();

        // Assert: all positions made the round trip
        assertThat(received).hasSize(2);
        assertThat(received.stream().map(FlightPosition::icao24).toList())
                .containsExactlyInAnyOrder("abc123", "def456");
        assertThat(received.stream().map(FlightPosition::callsign).toList())
                .containsExactlyInAnyOrder("UAL1234", "DAL567");

        // Verify detailed field preservation
        FlightPosition ual = received.stream()
                .filter(fp -> "UAL1234".equals(fp.callsign()))
                .findFirst().orElseThrow();
        assertThat(ual.latitude()).isEqualTo(41.9742);
        assertThat(ual.longitude()).isEqualTo(-87.9073);
        assertThat(ual.baroAltitude()).isEqualTo(10668.0);
        assertThat(ual.onGround()).isFalse();
    }

    @Test
    void shouldHandleLargeBatchSplitting() {
        var producer = new SqsPositionProducer(sqsClient, queueUrl);
        List<FlightPosition> received = new ArrayList<>();
        FlightPositionHandler capturingHandler = received::addAll;
        var consumer = new SqsPositionConsumer(sqsClient, queueUrl, capturingHandler);

        // Create 15 positions — forces 2 batches (10 + 5)
        List<FlightPosition> positions = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            positions.add(new FlightPosition(
                    "icao" + i, "CALL" + i, 41.0 + i * 0.01, -87.0 - i * 0.01,
                    10000.0 + i * 100, 200.0, 270.0, false,
                    1709312500L + i, 1709312500L + i, Instant.parse("2026-03-01T00:00:00Z")));
        }

        producer.send(positions);

        // SQS returns max 10 per receive, so we may need multiple polls
        for (int attempt = 0; attempt < 5 && received.size() < 15; attempt++) {
            consumer.poll();
        }

        assertThat(received).hasSize(15);
    }

    @Test
    void shouldPreserveMessageOrderWithinGroup() {
        var producer = new SqsPositionProducer(sqsClient, queueUrl);
        List<FlightPosition> received = new ArrayList<>();
        FlightPositionHandler capturingHandler = received::addAll;
        var consumer = new SqsPositionConsumer(sqsClient, queueUrl, capturingHandler);

        // Send 3 positions for the same aircraft in order
        var positions = List.of(
                new FlightPosition("same-aircraft", "UAL100", 41.0, -87.0,
                        10000.0, 200.0, 270.0, false,
                        1709312400L, 1709312400L, Instant.parse("2026-03-01T00:00:00Z")),
                new FlightPosition("same-aircraft", "UAL100", 41.5, -87.5,
                        9000.0, 210.0, 265.0, false,
                        1709312430L, 1709312430L, Instant.parse("2026-03-01T00:00:30Z")),
                new FlightPosition("same-aircraft", "UAL100", 42.0, -88.0,
                        8000.0, 220.0, 260.0, false,
                        1709312460L, 1709312460L, Instant.parse("2026-03-01T00:01:00Z"))
        );

        producer.send(positions);
        consumer.poll();

        // FIFO guarantees order within the same MessageGroupId
        assertThat(received).hasSize(3);
        assertThat(received.get(0).timePosition()).isEqualTo(1709312400L);
        assertThat(received.get(1).timePosition()).isEqualTo(1709312430L);
        assertThat(received.get(2).timePosition()).isEqualTo(1709312460L);
    }
}
```

**Step 2: Run the integration test**

Run:
```bash
cd skytrack && mvn test -pl . -Dtest="skytrack.demo.sqs.SqsRoundTripIntegrationTest"
```
Expected: 3 tests PASS (requires Docker to be running).

> **Note:** This test requires Docker to be running. If Docker is not available, the test will fail with a container startup error. This is expected and is not a code issue.

---

## Task 9: Update `.gitignore` and run full test suite

**Files:**
- Modify: `.gitignore` (project root)

**Step 1: Add Docker and LocalStack entries to `.gitignore`**

Append to `.gitignore`:

```
# LocalStack
localstack/volume/
.localstack/
```

**Step 2: Run the full test suite**

Run:
```bash
cd skytrack && mvn clean test
```
Expected: All tests pass — both existing Chunk 1 tests and new Chunk 2 tests.

> **Note:** The Spring context load test (`DemoApplicationTests`) will now require an SQS connection at startup because `SqsConfig` beans try to resolve queue URLs. You may need to add `@MockBean` for `SqsClient` in `DemoApplicationTests`, or add a `@ConditionalOnProperty` annotation to `SqsConfig` to skip SQS wiring when no endpoint is configured. If `DemoApplicationTests` fails, add the following condition to `SqsConfig`:
>
> In `SqsConfig.java`, add `@ConditionalOnProperty(name = "sqs.endpoint")` to the class:
> ```java
> @Configuration
> @ConditionalOnProperty(name = "sqs.endpoint")
> public class SqsConfig {
> ```
> This means SQS beans are only created when `sqs.endpoint` is explicitly set (i.e., in `local` or `prod` profiles), not during plain context load tests.

**Step 3: Manual smoke test (end-to-end round trip)**

Start LocalStack:
```bash
docker compose up -d && sleep 5
```

Run the Spring Boot app with the `local` profile:
```bash
cd skytrack && mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Expected log output:
```
Polled N aircraft positions
Published N positions to SQS
Received N flight positions
```

The data flows: recorded files → `ReplayOpenSkyClient` → `FlightPollingService` → `SqsPositionProducer` → SQS FIFO → `SqsPositionConsumer` → `LoggingFlightPositionHandler` (logs).

Stop the app and clean up:
```bash
docker compose down
```

---

## Summary

| Task | What | Files Created/Modified | Tests |
|------|------|------------------------|-------|
| 1 | Docker Compose + LocalStack init | 2 created | — (manual verify) |
| 2 | AWS SDK v2 + Testcontainers deps | 1 modified (pom.xml) | — (verify resolve) |
| 3 | `SqsProperties` + `SqsConfig` | 2 created, 3 modified (YAML) | 1 |
| 4 | `FlightPositionHandler` + `LoggingFlightPositionHandler` | 2 created | 2 |
| 5 | `SqsPositionProducer` | 1 created | 4 |
| 6 | `SqsPositionConsumer` | 1 created | 4 |
| 7 | Wire into Spring + `SqsConsumerService` | 2 modified, 1 created | 4 (3 updated + 1 new) |
| 8 | Integration test (Testcontainers) | 1 created | 3 |
| 9 | Gitignore + full verification | 1 modified | — (full suite run) |
| **Total** | | **~13 files** | **18 tests** |

### Architecture after Chunk 2

```
                     ┌──────────────────┐
                     │  FlightDataSource │  (Chunk 1: Replay or Live)
                     └────────┬─────────┘
                              │ fetchPositions()
                     ┌────────▼─────────┐
                     │FlightPollingService│  @Scheduled(30s)
                     └────────┬─────────┘
                              │ send()
                     ┌────────▼─────────┐
                     │SqsPositionProducer│  Batch serialize → SQS FIFO
                     └────────┬─────────┘
                              │
                    ┌─────────▼──────────┐
                    │ skytrack-positions  │  SQS FIFO (LocalStack)
                    │      .fifo         │  GroupId=icao24
                    └─────────┬──────────┘
                              │
                     ┌────────▼─────────┐
                     │SqsPositionConsumer│  Long poll → deserialize
                     └────────┬─────────┘
                              │ handle()
                  ┌───────────▼────────────┐
                  │ FlightPositionHandler   │  Interface
                  │ (LoggingHandler now,    │
                  │  StateMachine in Chunk 3)│
                  └────────────────────────┘
```
