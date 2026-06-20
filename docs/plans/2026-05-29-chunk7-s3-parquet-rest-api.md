# Chunk 7: S3 + Parquet Historical Storage & REST API Layer

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Persist every `DelayEvent` to S3 as partitioned Parquet files (LocalStack locally) on a scheduled flush, then expose six read-only REST endpoints (`/airports/{iata}/status`, `/airports/disruptions`, `/flights/{callsign}`, `/cascades/{iata}`, `/analytics/delays`, `/schedule/coverage`) that surface live disruption state, in-memory cascade alerts, schedule-resolution coverage stats, and historical Parquet data.

**Architecture:** A `HistoricalDelayWriter` buffers `DelayEvent`s in a thread-safe queue and a Spring `@Scheduled` flush serializes the batch to Parquet (via Carpet) and uploads it to S3 under a `delays/year=/month=/day=/hour=/` partition path. Reads go through a thin `AnalyticsService` that lists + downloads + decodes those Parquet objects. Three new lightweight stateful components — `ScheduleCoverageTracker` (resolution-method counters), `RecentCascadeStore` (bounded per-airport alert ring buffer), and a `findByCallsign` scan on `AircraftTrackRepository` — back the endpoints that need state the current pipeline doesn't retain. All five controllers are read-only and return record DTOs serialized by Spring Boot's Jackson. Everything hooks into the existing single fan-out point, `DelayEventProcessor.process()`.

**Tech Stack:** Spring Boot 4.0.2, Java 25, AWS SDK v2 (`s3`), Carpet (`com.jerolba:carpet-record`) for Parquet, Spring `@Scheduled`, Spring MVC `@RestController`, Lombok, JUnit 5 + AssertJ + Mockito + Testcontainers LocalStack + Spring `@WebMvcTest`/`MockMvc`.

**Depends on:** Chunks 1–6 (OpenSky clients, SQS pipeline, AeroAPI + WireMock, DynamoDB + state machine, delay detection + disruption scoring, weather integration).

---

## Library Decision: Carpet for Parquet

Per the decision deferred to this plan, the project uses **Carpet** (`com.jerolba:carpet-record`) rather than Apache Parquet Java or parquet-floor:

- **Why not Apache Parquet + Hadoop:** drags in `hadoop-common`/`hadoop-mapreduce-client`, ~50 MB of transitive deps, frequent CVE noise, and a real risk of class conflicts on Java 25 / Spring Boot 4. Would also bloat the eventual Docker image well past the roadmap's 200 MB target.
- **Why not parquet-floor:** lighter than full Hadoop but forces manual `MessageType` schema definition and row dehydrators — verbose for our records.
- **Why Carpet:** reflects directly over Java records (we already have `DelayEvent`), brings only the essential `parquet-column`/`parquet-hadoop` classes with Hadoop excluded, and gives a one-liner `CarpetWriter<T>` / `CarpetReader<T>` API. Best fit for a records-first codebase.

**Constraint to remember:** Parquet *writes* stream to any `OutputStream` (footer written last), but Parquet *reads* require random access — read from a `File`, not a bare `InputStream`. The plan therefore writes to a `ByteArrayOutputStream` (for upload) and, when reading back, downloads the S3 object to a temp file first.

**Version is verified in Task 1** by a fast-failing round-trip spike, so a wrong pin surfaces immediately rather than deep into the chunk.

---

# PART A — S3 + Parquet Historical Storage (Days 24–25)

## Task 1: Add Carpet + S3 Dependencies & Round-Trip Spike

De-risk the library choice first: add the deps and prove a record round-trips through Parquet on Java 25 before building anything real.

**Files:**
- Modify: `skytrack/pom.xml`
- Test: `skytrack/src/test/java/skytrack/demo/parquet/CarpetRoundTripSpikeTest.java`

### Step 1: Add dependencies

In `skytrack/pom.xml`, inside `<dependencies>`, add the S3 SDK (version managed by the existing AWS BOM) and Carpet:

```xml
		<!-- AWS SDK v2 - S3 (historical Parquet storage) -->
		<dependency>
			<groupId>software.amazon.awssdk</groupId>
			<artifactId>s3</artifactId>
		</dependency>

		<!-- Carpet - record-based Parquet, no Hadoop stack -->
		<dependency>
			<groupId>com.jerolba</groupId>
			<artifactId>carpet-record</artifactId>
			<version>0.4.0</version>
		</dependency>
```

> **If `0.4.0` fails to resolve:** check Maven Central for the latest `com.jerolba:carpet-record` release (`mvn dependency:get -Dartifact=com.jerolba:carpet-record:LATEST` or the context7 docs tool) and pin that. The spike test in this task will fail fast if the version or API is wrong.

### Step 2: Write the failing spike test

**`CarpetRoundTripSpikeTest.java`:**

```java
package skytrack.demo.parquet;

import com.jerolba.carpet.CarpetReader;
import com.jerolba.carpet.CarpetWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CarpetRoundTripSpikeTest {

    record Row(String id, long value, Long nullableValue, String label) {}

    @Test
    void shouldRoundTripRecordsThroughParquet(@TempDir Path tempDir) throws IOException {
        List<Row> rows = List.of(
                new Row("a", 1L, 10L, "first"),
                new Row("b", 2L, null, "second"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var writer = new CarpetWriter<>(out, Row.class)) {
            writer.write(rows);
        }
        assertThat(out.size()).isGreaterThan(0);

        // Parquet reads need random access -> write bytes to a temp file first.
        Path file = tempDir.resolve("spike.parquet");
        Files.write(file, out.toByteArray());

        List<Row> readBack = new CarpetReader<>(file.toFile(), Row.class).toList();

        assertThat(readBack).hasSize(2);
        assertThat(readBack.get(0).id()).isEqualTo("a");
        assertThat(readBack.get(0).nullableValue()).isEqualTo(10L);
        assertThat(readBack.get(1).nullableValue()).isNull();
    }
}
```

### Step 3: Run the spike to verify it passes

Run: `cd skytrack && mvn test -Dtest=CarpetRoundTripSpikeTest -q`
Expected: PASS. If it fails on dependency resolution or API mismatch, fix the Carpet version/API now before proceeding.

### Step 4: Commit

```bash
git add skytrack/pom.xml \
        skytrack/src/test/java/skytrack/demo/parquet/CarpetRoundTripSpikeTest.java
git commit -m "build: add S3 SDK and Carpet Parquet deps with round-trip spike"
```

---

## Task 2: DelayParquetRow Schema Record

A flat, Parquet-friendly projection of `DelayEvent`. Deliberately uses only `String`/`long`/`Long`/`Integer`/`Double` (enums → name, `Instant` → epoch millis) to sidestep Parquet logical-type questions.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/parquet/DelayParquetRow.java`
- Test: `skytrack/src/test/java/skytrack/demo/parquet/DelayParquetRowTest.java`

### Step 1: Write the failing test

**`DelayParquetRowTest.java`:**

```java
package skytrack.demo.parquet;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;
import skytrack.demo.model.FlightCategory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DelayParquetRowTest {

    @Test
    void shouldMapAllFieldsFromDelayEvent() {
        Instant created = Instant.parse("2026-05-29T14:30:00Z");
        var event = new DelayEvent(
                "abc123", "UAL456", "UA", "456",
                "KORD", "ORD",
                1748528400L, 1748527500L, 900L,
                DelayClassification.MINOR_DELAY, "AEROAPI", created,
                FlightCategory.MVFR, 4.0, 1500, 18);

        DelayParquetRow row = DelayParquetRow.from(event);

        assertThat(row.icao24()).isEqualTo("abc123");
        assertThat(row.arrivalAirportIata()).isEqualTo("ORD");
        assertThat(row.actualArrivalTime()).isEqualTo(1748528400L);
        assertThat(row.scheduledArrivalTime()).isEqualTo(1748527500L);
        assertThat(row.delaySeconds()).isEqualTo(900L);
        assertThat(row.classification()).isEqualTo("MINOR_DELAY");
        assertThat(row.resolutionMethod()).isEqualTo("AEROAPI");
        assertThat(row.createdAtEpochMillis()).isEqualTo(created.toEpochMilli());
        assertThat(row.flightCategory()).isEqualTo("MVFR");
        assertThat(row.visibilityStatuteMiles()).isEqualTo(4.0);
        assertThat(row.ceilingFeet()).isEqualTo(1500);
    }

    @Test
    void shouldTolerateNullableFields() {
        var event = new DelayEvent(
                "abc123", "UAL456", null, null,
                "KORD", "ORD",
                1748528400L, null, null,
                null, "UNRESOLVED", Instant.parse("2026-05-29T14:30:00Z"),
                null, null, null, null);

        DelayParquetRow row = DelayParquetRow.from(event);

        assertThat(row.scheduledArrivalTime()).isNull();
        assertThat(row.delaySeconds()).isNull();
        assertThat(row.classification()).isNull();
        assertThat(row.flightCategory()).isNull();
        assertThat(row.ceilingFeet()).isNull();
    }
}
```

> **Before writing the impl, confirm the `DelayClassification` enum constant used above exists.** Run `grep -o 'MINOR_DELAY\|MAJOR_DELAY\|ON_TIME\|[A-Z_]*DELAY' skytrack/src/main/java/skytrack/demo/model/DelayClassification.java | sort -u` and substitute a real constant name into the test if `MINOR_DELAY` is not present.

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest=DelayParquetRowTest -q`
Expected: FAIL — `DelayParquetRow` does not exist.

### Step 3: Implement

**`DelayParquetRow.java`:**

```java
package skytrack.demo.parquet;

import skytrack.demo.model.DelayEvent;

public record DelayParquetRow(
        String icao24,
        String callsign,
        String carrierCode,
        String flightNumber,
        String arrivalAirportIcao,
        String arrivalAirportIata,
        long actualArrivalTime,
        Long scheduledArrivalTime,
        Long delaySeconds,
        String classification,
        String resolutionMethod,
        long createdAtEpochMillis,
        String flightCategory,
        Double visibilityStatuteMiles,
        Integer ceilingFeet,
        Integer windSpeedKnots
) {
    public static DelayParquetRow from(DelayEvent e) {
        return new DelayParquetRow(
                e.icao24(),
                e.callsign(),
                e.carrierCode(),
                e.flightNumber(),
                e.arrivalAirportIcao(),
                e.arrivalAirportIata(),
                e.actualArrivalTime(),
                e.scheduledArrivalTime(),
                e.delaySeconds(),
                e.classification() != null ? e.classification().name() : null,
                e.resolutionMethod(),
                e.createdAt() != null ? e.createdAt().toEpochMilli() : 0L,
                e.flightCategory() != null ? e.flightCategory().name() : null,
                e.visibilityStatuteMiles(),
                e.ceilingFeet(),
                e.windSpeedKnots());
    }
}
```

### Step 4: Verify pass

Run: `cd skytrack && mvn test -Dtest=DelayParquetRowTest -q`
Expected: PASS.

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/parquet/DelayParquetRow.java \
        skytrack/src/test/java/skytrack/demo/parquet/DelayParquetRowTest.java
git commit -m "feat: add DelayParquetRow flat schema for Parquet storage"
```

---

## Task 3: ParquetSerializer

Serializes `List<DelayParquetRow>` to a Parquet `byte[]` (for upload) and decodes a Parquet `byte[]`/file back to rows (for reads). Encapsulates the "reads need a file" constraint.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/parquet/ParquetSerializer.java`
- Test: `skytrack/src/test/java/skytrack/demo/parquet/ParquetSerializerTest.java`

### Step 1: Write the failing test

**`ParquetSerializerTest.java`:**

```java
package skytrack.demo.parquet;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParquetSerializerTest {

    private final ParquetSerializer serializer = new ParquetSerializer();

    private static DelayParquetRow row(String icao, String iata, Long delay) {
        return new DelayParquetRow(icao, "UAL1", "UA", "1",
                "K" + iata, iata, 1748528400L, 1748527500L, delay,
                "MAJOR_DELAY", "AEROAPI", 1748528400000L, "IFR", 2.0, 800, 12);
    }

    @Test
    void shouldRoundTripRowsThroughParquetBytes() throws IOException {
        List<DelayParquetRow> rows = List.of(
                row("a1", "ORD", 900L),
                row("b2", "ATL", 1800L));

        byte[] bytes = serializer.serialize(rows);
        assertThat(bytes).isNotEmpty();

        List<DelayParquetRow> readBack = serializer.deserialize(bytes);
        assertThat(readBack).hasSize(2);
        assertThat(readBack.get(0).arrivalAirportIata()).isEqualTo("ORD");
        assertThat(readBack.get(1).delaySeconds()).isEqualTo(1800L);
    }

    @Test
    void shouldSerializeEmptyListToValidParquet() throws IOException {
        byte[] bytes = serializer.serialize(List.of());
        assertThat(bytes).isNotEmpty(); // Parquet still writes header + footer
        assertThat(serializer.deserialize(bytes)).isEmpty();
    }
}
```

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest=ParquetSerializerTest -q`
Expected: FAIL — class missing.

### Step 3: Implement

**`ParquetSerializer.java`:**

```java
package skytrack.demo.parquet;

import com.jerolba.carpet.CarpetReader;
import com.jerolba.carpet.CarpetWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class ParquetSerializer {

    public byte[] serialize(List<DelayParquetRow> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var writer = new CarpetWriter<>(out, DelayParquetRow.class)) {
            writer.write(rows);
        }
        return out.toByteArray();
    }

    /** Parquet requires random access on read, so bytes are staged to a temp file. */
    public List<DelayParquetRow> deserialize(byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile("skytrack-parquet-", ".parquet");
        try {
            Files.write(tmp, bytes);
            return new CarpetReader<>(tmp.toFile(), DelayParquetRow.class).toList();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
```

### Step 4: Verify pass

Run: `cd skytrack && mvn test -Dtest=ParquetSerializerTest -q`
Expected: PASS, both tests green.

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/parquet/ParquetSerializer.java \
        skytrack/src/test/java/skytrack/demo/parquet/ParquetSerializerTest.java
git commit -m "feat: add ParquetSerializer for DelayParquetRow encode/decode"
```

---

## Task 4: S3Properties & S3Config

Config record + `S3Client` bean. Mirrors `DynamoDbConfig` / `SqsConfig` exactly: `endpointOverride` + static `test`/`test` creds when an endpoint is configured (LocalStack). **S3 on LocalStack additionally requires path-style access** (`forcePathStyle(true)`), otherwise the SDK builds virtual-host URLs like `bucket.localhost`.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/config/S3Properties.java`
- Create: `skytrack/src/main/java/skytrack/demo/config/S3Config.java`
- Test: `skytrack/src/test/java/skytrack/demo/config/S3PropertiesTest.java`

### Step 1: Write the failing test

**`S3PropertiesTest.java`:**

```java
package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class S3PropertiesTest {

    @Test
    void shouldApplyDefaults() {
        var props = new S3Properties(null, null, null, null, 0);
        assertThat(props.bucket()).isEqualTo("skytrack-history");
        assertThat(props.region()).isEqualTo("us-east-1");
        assertThat(props.prefix()).isEqualTo("delays");
        assertThat(props.flushIntervalSeconds()).isEqualTo(300);
    }

    @Test
    void shouldRetainProvidedValues() {
        var props = new S3Properties("my-bucket", "http://localhost:4566",
                "us-west-2", "events", 60);
        assertThat(props.bucket()).isEqualTo("my-bucket");
        assertThat(props.endpoint()).isEqualTo("http://localhost:4566");
        assertThat(props.region()).isEqualTo("us-west-2");
        assertThat(props.prefix()).isEqualTo("events");
        assertThat(props.flushIntervalSeconds()).isEqualTo(60);
    }
}
```

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest=S3PropertiesTest -q`
Expected: FAIL — class missing.

### Step 3: Implement

**`S3Properties.java`:**

```java
package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.s3")
public record S3Properties(
        String bucket,
        String endpoint,
        String region,
        String prefix,
        int flushIntervalSeconds
) {
    public S3Properties {
        if (bucket == null || bucket.isBlank()) bucket = "skytrack-history";
        if (region == null || region.isBlank()) region = "us-east-1";
        if (prefix == null || prefix.isBlank()) prefix = "delays";
        if (flushIntervalSeconds <= 0) flushIntervalSeconds = 300;
    }
}
```

**`S3Config.java`:**

```java
package skytrack.demo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3Config {

    @Bean
    public S3Client s3Client(S3Properties props) {
        var builder = S3Client.builder()
                .region(Region.of(props.region()));

        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.endpoint()))
                    .forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }

        return builder.build();
    }
}
```

### Step 4: Verify pass

Run: `cd skytrack && mvn test -Dtest=S3PropertiesTest -q`
Expected: PASS.

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/config/S3Properties.java \
        skytrack/src/main/java/skytrack/demo/config/S3Config.java \
        skytrack/src/test/java/skytrack/demo/config/S3PropertiesTest.java
git commit -m "feat: add S3Properties and S3Config with LocalStack path-style support"
```

---

## Task 5: HistoricalDelayWriter (Buffer + Scheduled Flush)

Buffers `DelayEvent`s and flushes them to S3 as one Parquet object per flush, keyed by a UTC partition path derived from the flush instant. Flush is non-fatal: failures are logged, and a failed batch is dropped (acceptable for a demo historical store; document the trade-off).

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/parquet/HistoricalDelayWriter.java`
- Test: `skytrack/src/test/java/skytrack/demo/parquet/HistoricalDelayWriterTest.java`

### Step 1: Write the failing test

**`HistoricalDelayWriterTest.java`:**

```java
package skytrack.demo.parquet;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import skytrack.demo.config.S3Properties;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;
import skytrack.demo.model.FlightCategory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HistoricalDelayWriterTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-29T14:05:00Z"), ZoneOffset.UTC);
    private final S3Properties props =
            new S3Properties("skytrack-history", "http://localhost:4566", "us-east-1", "delays", 300);

    private static DelayEvent event(String iata) {
        return new DelayEvent("abc", "UAL1", "UA", "1", "K" + iata, iata,
                1748528400L, 1748527500L, 900L, DelayClassification.MAJOR_DELAY,
                "AEROAPI", Instant.parse("2026-05-29T14:00:00Z"),
                FlightCategory.IFR, 2.0, 800, 12);
    }

    @Test
    void shouldFlushBufferedEventsToS3WithPartitionedKey() {
        S3Client s3 = mock(S3Client.class);
        var writer = new HistoricalDelayWriter(s3, new ParquetSerializer(), props, clock);

        writer.buffer(event("ORD"));
        writer.buffer(event("ATL"));
        writer.flush();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(reqCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest req = reqCaptor.getValue();
        assertThat(req.bucket()).isEqualTo("skytrack-history");
        assertThat(req.key())
                .startsWith("delays/year=2026/month=05/day=29/hour=14/")
                .endsWith(".parquet");
        assertThat(bodyCaptor.getValue().optionalContentLength().orElse(0L)).isGreaterThan(0L);
    }

    @Test
    void shouldNotCallS3WhenBufferEmpty() {
        S3Client s3 = mock(S3Client.class);
        var writer = new HistoricalDelayWriter(s3, new ParquetSerializer(), props, clock);

        writer.flush();

        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void shouldDrainBufferAfterFlush() {
        S3Client s3 = mock(S3Client.class);
        var writer = new HistoricalDelayWriter(s3, new ParquetSerializer(), props, clock);

        writer.buffer(event("ORD"));
        writer.flush();
        writer.flush(); // second flush has nothing to write

        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void shouldSwallowS3Exceptions() {
        S3Client s3 = mock(S3Client.class);
        org.mockito.Mockito.when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("boom"));
        var writer = new HistoricalDelayWriter(s3, new ParquetSerializer(), props, clock);

        writer.buffer(event("ORD"));
        writer.flush(); // must not throw
    }
}
```

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest=HistoricalDelayWriterTest -q`
Expected: FAIL — class missing.

### Step 3: Implement

**`HistoricalDelayWriter.java`:**

```java
package skytrack.demo.parquet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.config.S3Properties;
import skytrack.demo.model.DelayEvent;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class HistoricalDelayWriter {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDelayWriter.class);

    private final ConcurrentLinkedQueue<DelayEvent> buffer = new ConcurrentLinkedQueue<>();
    private final S3Client s3;
    private final ParquetSerializer serializer;
    private final S3Properties props;
    private final Clock clock;

    public HistoricalDelayWriter(S3Client s3, ParquetSerializer serializer,
                                 S3Properties props, Clock clock) {
        this.s3 = s3;
        this.serializer = serializer;
        this.props = props;
        this.clock = clock;
    }

    public void buffer(DelayEvent event) {
        buffer.add(event);
    }

    @Scheduled(fixedRateString = "#{${skytrack.s3.flush-interval-seconds:300} * 1000}",
               initialDelay = 10_000)
    public void flush() {
        List<DelayEvent> batch = drain();
        if (batch.isEmpty()) {
            return;
        }
        try {
            List<DelayParquetRow> rows = batch.stream().map(DelayParquetRow::from).toList();
            byte[] parquet = serializer.serialize(rows);
            String key = partitionKey();
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(key)
                            .contentType("application/octet-stream")
                            .build(),
                    RequestBody.fromBytes(parquet));
            log.info("Flushed {} delay events to s3://{}/{}", rows.size(), props.bucket(), key);
        } catch (Exception e) {
            log.error("Failed to flush {} delay events to S3: {}", batch.size(), e.getMessage());
        }
    }

    private List<DelayEvent> drain() {
        List<DelayEvent> batch = new ArrayList<>();
        DelayEvent e;
        while ((e = buffer.poll()) != null) {
            batch.add(e);
        }
        return batch;
    }

    private String partitionKey() {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return String.format("%s/year=%04d/month=%02d/day=%02d/hour=%02d/delays-%d.parquet",
                props.prefix(),
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(), now.getHour(),
                clock.instant().toEpochMilli());
    }
}
```

### Step 4: Verify pass

Run: `cd skytrack && mvn test -Dtest=HistoricalDelayWriterTest -q`
Expected: PASS, all 4 tests green.

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/parquet/HistoricalDelayWriter.java \
        skytrack/src/test/java/skytrack/demo/parquet/HistoricalDelayWriterTest.java
git commit -m "feat: add HistoricalDelayWriter with scheduled S3 Parquet flush"
```

---

## Task 6: Hook HistoricalDelayWriter into DelayEventProcessor

Buffer every computed `DelayEvent` for historical storage at the existing fan-out point.

**Files:**
- Modify: `skytrack/src/main/java/skytrack/demo/service/DelayEventProcessor.java`
- Modify: `skytrack/src/test/java/skytrack/demo/service/DelayEventProcessorTest.java`

### Step 1: Update the failing test

Open `DelayEventProcessorTest.java`. Add a `HistoricalDelayWriter` mock to the constructor wiring and assert it is called once per processed arrival.

> **Read the existing test first** to match its construction style (how it builds the processor and a `ResolvedArrival`). Add:

```java
// field
private HistoricalDelayWriter historicalDelayWriter;

// in setup, alongside the other mocks:
historicalDelayWriter = mock(HistoricalDelayWriter.class);

// update the processor construction to pass historicalDelayWriter as the new last arg

// new test:
@Test
void shouldBufferDelayEventForHistoricalStorage() {
    // arrange a ResolvedArrival + stub delayComputer.compute(...) to return a DelayEvent
    // (mirror the existing happy-path test's arrangement)
    processor.process(resolvedArrival);
    verify(historicalDelayWriter).buffer(any(DelayEvent.class));
}
```

Add imports as needed: `import skytrack.demo.parquet.HistoricalDelayWriter;` and `static org.mockito.ArgumentMatchers.any;`.

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest=DelayEventProcessorTest -q`
Expected: FAIL — constructor arity mismatch / `historicalDelayWriter` unused.

### Step 3: Implement

In `DelayEventProcessor.java`, add the dependency and the buffer call:

```java
    private final HistoricalDelayWriter historicalDelayWriter;

    public DelayEventProcessor(DelayComputer delayComputer,
                               DisruptionScoreService disruptionScoreService,
                               SqsAirportEventProducer eventProducer,
                               CascadeDetector cascadeDetector,
                               WeatherCache weatherCache,
                               HistoricalDelayWriter historicalDelayWriter) {
        this.delayComputer = delayComputer;
        this.disruptionScoreService = disruptionScoreService;
        this.eventProducer = eventProducer;
        this.cascadeDetector = cascadeDetector;
        this.weatherCache = weatherCache;
        this.historicalDelayWriter = historicalDelayWriter;
    }
```

Inside `process(...)`, after `eventProducer.send(delayEvent);`:

```java
        historicalDelayWriter.buffer(delayEvent);
```

Add the import: `import skytrack.demo.parquet.HistoricalDelayWriter;`

### Step 4: Verify pass

Run: `cd skytrack && mvn test -Dtest=DelayEventProcessorTest -q`
Expected: PASS.

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/service/DelayEventProcessor.java \
        skytrack/src/test/java/skytrack/demo/service/DelayEventProcessorTest.java
git commit -m "feat: buffer delay events to HistoricalDelayWriter in processor"
```

---

## Task 7: Wire S3 into docker-compose, init-aws.sh, and config

Enable S3 in LocalStack, create the history bucket on startup, and add the `skytrack.s3` config block.

**Files:**
- Modify: `docker-compose.yml`
- Modify: `localstack/init-aws.sh`
- Modify: `skytrack/src/main/resources/application.yml`
- Modify: `skytrack/src/main/resources/application-local.yml`

### Step 1: Enable S3 in LocalStack

In `docker-compose.yml`, change the localstack `SERVICES` line:

```yaml
      - SERVICES=sqs,dynamodb,s3
```

### Step 2: Create the bucket on startup

Append to `localstack/init-aws.sh`:

```bash
echo "Creating S3 history bucket..."

awslocal s3 mb s3://skytrack-history

echo "S3 buckets created:"
awslocal s3 ls
```

### Step 3: Add config

In `application.yml`, under the existing `skytrack:` block (sibling to `dynamodb:`), add:

```yaml
  s3:
    bucket: skytrack-history
    region: us-east-1
    prefix: delays
    flush-interval-seconds: 300
```

In `application-local.yml`, under the existing `skytrack:` block (sibling to `dynamodb:`), add:

```yaml
  s3:
    endpoint: http://localhost:4566
    flush-interval-seconds: 60
```

### Step 4: Verify the app context still loads

Run: `cd skytrack && mvn test -Dtest=DemoApplicationTests -q`
Expected: PASS (S3Client bean wires; no endpoint needed for the default test profile).

### Step 5: Commit

```bash
git add docker-compose.yml localstack/init-aws.sh \
        skytrack/src/main/resources/application.yml \
        skytrack/src/main/resources/application-local.yml
git commit -m "feat: enable LocalStack S3 and add skytrack.s3 config"
```

---

## Task 8: LocalStack Integration Test (Flush → S3 → Read Back)

End-to-end proof against a real (containerized) S3: flush a batch, confirm the object lands under the partition prefix, download and decode it.

**Files:**
- Test: `skytrack/src/test/java/skytrack/demo/parquet/HistoricalDelayWriterIntegrationTest.java`

> **Mirror the existing LocalStack Testcontainers setup.** Read `skytrack/src/test/java/skytrack/demo/sqs/SqsRoundTripIntegrationTest.java` first and copy its container declaration, `@Testcontainers` annotation, and how it enables services / builds the AWS client against the container endpoint. Add `S3` to the LocalStack `withServices(...)` list.

### Step 1: Write the test

```java
package skytrack.demo.parquet;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import skytrack.demo.config.S3Properties;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;
import skytrack.demo.model.FlightCategory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class HistoricalDelayWriterIntegrationTest {

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices(LocalStackContainer.Service.S3);

    private static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(localstack.getEndpoint())
                .forcePathStyle(true)
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();
    }

    private static DelayEvent event(String iata, long delay) {
        return new DelayEvent("abc", "UAL1", "UA", "1", "K" + iata, iata,
                1748528400L, 1748527500L, delay, DelayClassification.MAJOR_DELAY,
                "AEROAPI", Instant.parse("2026-05-29T14:00:00Z"),
                FlightCategory.IFR, 2.0, 800, 12);
    }

    @Test
    void shouldWriteParquetToS3AndReadItBack() throws Exception {
        S3Client s3 = s3Client();
        s3.createBucket(CreateBucketRequest.builder().bucket("skytrack-history").build());

        var props = new S3Properties("skytrack-history", localstack.getEndpoint().toString(),
                localstack.getRegion(), "delays", 300);
        var clock = Clock.fixed(Instant.parse("2026-05-29T14:05:00Z"), ZoneOffset.UTC);
        var serializer = new ParquetSerializer();
        var writer = new HistoricalDelayWriter(s3, serializer, props, clock);

        writer.buffer(event("ORD", 900L));
        writer.buffer(event("ATL", 1800L));
        writer.flush();

        var listed = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket("skytrack-history").prefix("delays/").build());
        assertThat(listed.contents()).hasSize(1);
        S3Object obj = listed.contents().get(0);
        assertThat(obj.key()).startsWith("delays/year=2026/month=05/day=29/hour=14/");

        ResponseBytes<?> bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("skytrack-history").key(obj.key()).build());
        List<DelayParquetRow> rows = serializer.deserialize(bytes.asByteArray());

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(DelayParquetRow::arrivalAirportIata)
                .containsExactlyInAnyOrder("ORD", "ATL");
    }
}
```

### Step 2: Run it

Run: `cd skytrack && mvn test -Dtest=HistoricalDelayWriterIntegrationTest -q`
Expected: PASS (Docker must be running). If `getRegion()`/`getAccessKey()` differ in your Testcontainers version, match the API used by `SqsRoundTripIntegrationTest`.

### Step 3: Commit

```bash
git add skytrack/src/test/java/skytrack/demo/parquet/HistoricalDelayWriterIntegrationTest.java
git commit -m "test: add LocalStack integration test for S3 Parquet round-trip"
```

---

# PART B — REST API Layer (Days 26–27)

## Task 9: ScheduleCoverageTracker + Hook

Counts resolutions by method so `/schedule/coverage` can report cache efficiency. Thread-safe counters keyed by `resolutionMethod` (`AEROAPI` / `ROUTE_AVERAGE` / `UNRESOLVED`).

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/ScheduleCoverageTracker.java`
- Create: `skytrack/src/main/java/skytrack/demo/model/ScheduleCoverage.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/ScheduleCoverageTrackerTest.java`
- Modify: `skytrack/src/main/java/skytrack/demo/service/DelayEventProcessor.java`
- Modify: `skytrack/src/test/java/skytrack/demo/service/DelayEventProcessorTest.java`

### Step 1: Write the failing test

**`ScheduleCoverageTrackerTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.ScheduleCoverage;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleCoverageTrackerTest {

    @Test
    void shouldCountByMethodAndComputeVerifiedRate() {
        var tracker = new ScheduleCoverageTracker();
        tracker.record("AEROAPI");
        tracker.record("AEROAPI");
        tracker.record("AEROAPI");
        tracker.record("ROUTE_AVERAGE");
        tracker.record("UNRESOLVED");

        ScheduleCoverage coverage = tracker.snapshot();
        assertThat(coverage.total()).isEqualTo(5);
        assertThat(coverage.verified()).isEqualTo(3);
        assertThat(coverage.estimated()).isEqualTo(1);
        assertThat(coverage.unresolved()).isEqualTo(1);
        assertThat(coverage.verifiedRate()).isEqualTo(0.6);
    }

    @Test
    void shouldReportZeroRateWhenEmpty() {
        ScheduleCoverage coverage = new ScheduleCoverageTracker().snapshot();
        assertThat(coverage.total()).isZero();
        assertThat(coverage.verifiedRate()).isZero();
    }

    @Test
    void shouldTreatNullMethodAsUnresolved() {
        var tracker = new ScheduleCoverageTracker();
        tracker.record(null);
        assertThat(tracker.snapshot().unresolved()).isEqualTo(1);
    }
}
```

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest=ScheduleCoverageTrackerTest -q`
Expected: FAIL — classes missing.

### Step 3: Implement

**`ScheduleCoverage.java`:**

```java
package skytrack.demo.model;

public record ScheduleCoverage(
        long total,
        long verified,
        long estimated,
        long unresolved,
        double verifiedRate
) {}
```

**`ScheduleCoverageTracker.java`:**

```java
package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.ScheduleCoverage;

import java.util.concurrent.atomic.LongAdder;

@Service
public class ScheduleCoverageTracker {

    private final LongAdder verified = new LongAdder();
    private final LongAdder estimated = new LongAdder();
    private final LongAdder unresolved = new LongAdder();

    public void record(String resolutionMethod) {
        if ("AEROAPI".equals(resolutionMethod)) {
            verified.increment();
        } else if ("ROUTE_AVERAGE".equals(resolutionMethod)) {
            estimated.increment();
        } else {
            unresolved.increment();
        }
    }

    public ScheduleCoverage snapshot() {
        long v = verified.sum();
        long e = estimated.sum();
        long u = unresolved.sum();
        long total = v + e + u;
        double rate = total > 0 ? (double) v / total : 0.0;
        return new ScheduleCoverage(total, v, e, u, rate);
    }
}
```

### Step 4: Hook into DelayEventProcessor (TDD the wiring)

In `DelayEventProcessorTest.java`, add a `ScheduleCoverageTracker` mock to the constructor and assert `record(...)` is called with the event's resolution method. Then in `DelayEventProcessor.java` add the dependency and, inside `process(...)` after computing `delayEvent`:

```java
        coverageTracker.record(delayEvent.resolutionMethod());
```

(Add the field, constructor param, and assignment, mirroring Task 6.)

### Step 5: Verify pass

Run: `cd skytrack && mvn test -Dtest='ScheduleCoverageTrackerTest,DelayEventProcessorTest' -q`
Expected: PASS.

### Step 6: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/model/ScheduleCoverage.java \
        skytrack/src/main/java/skytrack/demo/service/ScheduleCoverageTracker.java \
        skytrack/src/test/java/skytrack/demo/service/ScheduleCoverageTrackerTest.java \
        skytrack/src/main/java/skytrack/demo/service/DelayEventProcessor.java \
        skytrack/src/test/java/skytrack/demo/service/DelayEventProcessorTest.java
git commit -m "feat: add ScheduleCoverageTracker and wire into processor"
```

---

## Task 10: RecentCascadeStore + Hook

Retains the most recent cascade alerts per arrival airport (bounded ring buffer) so `/cascades/{iata}` has something to serve. `CascadeDetector` currently discards alerts after logging.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/RecentCascadeStore.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/RecentCascadeStoreTest.java`
- Modify: `skytrack/src/main/java/skytrack/demo/service/DelayEventProcessor.java`
- Modify: `skytrack/src/test/java/skytrack/demo/service/DelayEventProcessorTest.java`

### Step 1: Write the failing test

**`RecentCascadeStoreTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.CascadeAlert;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecentCascadeStoreTest {

    private static CascadeAlert alert(String iata, String callsign) {
        return new CascadeAlert(callsign, iata, 2400L, 2040L, 0.85, Instant.now());
    }

    @Test
    void shouldStoreAndReturnAlertsByAirport() {
        var store = new RecentCascadeStore();
        store.add(alert("ORD", "UAL1"));
        store.add(alert("ORD", "UAL2"));
        store.add(alert("ATL", "DAL9"));

        assertThat(store.getRecent("ORD")).hasSize(2);
        assertThat(store.getRecent("ATL")).hasSize(1);
    }

    @Test
    void shouldReturnEmptyForUnknownAirport() {
        assertThat(new RecentCascadeStore().getRecent("ZZZ")).isEmpty();
    }

    @Test
    void shouldCapEntriesPerAirport() {
        var store = new RecentCascadeStore();
        for (int i = 0; i < 60; i++) {
            store.add(alert("ORD", "UAL" + i));
        }
        // ring buffer capped at 50
        List<CascadeAlert> recent = store.getRecent("ORD");
        assertThat(recent).hasSize(50);
        // newest retained: most recent insert present, oldest evicted
        assertThat(recent).extracting(CascadeAlert::sourceCallsign).contains("UAL59");
        assertThat(recent).extracting(CascadeAlert::sourceCallsign).doesNotContain("UAL0");
    }

    @Test
    void shouldIgnoreNullAirport() {
        var store = new RecentCascadeStore();
        store.add(new CascadeAlert("UAL1", null, 2400L, 2040L, 0.85, Instant.now()));
        assertThat(store.getRecent(null)).isEmpty();
    }
}
```

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest=RecentCascadeStoreTest -q`
Expected: FAIL — class missing.

### Step 3: Implement

**`RecentCascadeStore.java`:**

```java
package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.CascadeAlert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RecentCascadeStore {

    private static final int MAX_PER_AIRPORT = 50;

    private final Map<String, Deque<CascadeAlert>> byAirport = new ConcurrentHashMap<>();

    public void add(CascadeAlert alert) {
        if (alert == null || alert.arrivalAirportIata() == null) {
            return;
        }
        Deque<CascadeAlert> deque = byAirport.computeIfAbsent(
                alert.arrivalAirportIata(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addFirst(alert);
            while (deque.size() > MAX_PER_AIRPORT) {
                deque.removeLast();
            }
        }
    }

    public List<CascadeAlert> getRecent(String airportIata) {
        if (airportIata == null) {
            return List.of();
        }
        Deque<CascadeAlert> deque = byAirport.get(airportIata);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }
}
```

### Step 4: Hook into DelayEventProcessor

In `DelayEventProcessor.process(...)`, the cascade check already produces an `Optional<CascadeAlert>`. Add the store dependency (constructor + field, mirroring prior tasks) and store the alert inside the existing `ifPresent` block:

```java
        cascadeDetector.checkCascade(delayEvent).ifPresent(alert -> {
            recentCascadeStore.add(alert);
            log.info("Cascade risk: {} at {} predicted downstream delay={}min",
                    alert.sourceCallsign(), alert.arrivalAirportIata(),
                    alert.predictedDownstreamDelaySeconds() / 60);
        });
```

Update `DelayEventProcessorTest.java`: add a `RecentCascadeStore` mock to the constructor. (No new behavioral assertion required, but the existing cascade test can `verify(recentCascadeStore).add(...)` when an alert fires.)

### Step 5: Verify pass

Run: `cd skytrack && mvn test -Dtest='RecentCascadeStoreTest,DelayEventProcessorTest' -q`
Expected: PASS.

### Step 6: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/service/RecentCascadeStore.java \
        skytrack/src/test/java/skytrack/demo/service/RecentCascadeStoreTest.java \
        skytrack/src/main/java/skytrack/demo/service/DelayEventProcessor.java \
        skytrack/src/test/java/skytrack/demo/service/DelayEventProcessorTest.java
git commit -m "feat: add RecentCascadeStore and retain alerts in processor"
```

---

## Task 11: AircraftTrackRepository.findByCallsign

`/flights/{callsign}` needs lookup by callsign, but the table is keyed by `icao24`. Add a scan with a filter expression. Acceptable for a demo serving layer; the production note is "add a callsign GSI."

**Files:**
- Modify: `skytrack/src/main/java/skytrack/demo/repository/AircraftTrackRepository.java`
- Modify: `skytrack/src/test/java/skytrack/demo/repository/AircraftTrackRepositoryTest.java`

### Step 1: Write the failing test

> **Read `AircraftTrackRepositoryTest.java` first** to match how it provisions a table (LocalStack Testcontainers vs. in-memory). Add a test that saves two tracks with different callsigns and asserts `findByCallsign` returns the matching one:

```java
@Test
void shouldFindTrackByCallsign() {
    AircraftTrack t1 = AircraftTrack.initial("aaa111");
    t1.setCallsign("UAL100");
    repository.save(t1);

    AircraftTrack t2 = AircraftTrack.initial("bbb222");
    t2.setCallsign("DAL200");
    repository.save(t2);

    Optional<AircraftTrack> found = repository.findByCallsign("DAL200");
    assertThat(found).isPresent();
    assertThat(found.get().getIcao24()).isEqualTo("bbb222");
}

@Test
void shouldReturnEmptyWhenCallsignNotFound() {
    assertThat(repository.findByCallsign("ZZZ999")).isEmpty();
}
```

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest=AircraftTrackRepositoryTest -q`
Expected: FAIL — `findByCallsign` does not exist.

### Step 3: Implement

Add to `AircraftTrackRepository.java`:

```java
    public Optional<AircraftTrack> findByCallsign(String callsign) {
        Expression filter = Expression.builder()
                .expression("callsign = :cs")
                .putExpressionValue(":cs", AttributeValue.builder().s(callsign).build())
                .build();

        return table.scan(ScanEnhancedRequest.builder()
                        .filterExpression(filter)
                        .build())
                .items().stream()
                .findFirst();
    }
```

Add imports:

```java
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
```

### Step 4: Verify pass

Run: `cd skytrack && mvn test -Dtest=AircraftTrackRepositoryTest -q`
Expected: PASS.

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/repository/AircraftTrackRepository.java \
        skytrack/src/test/java/skytrack/demo/repository/AircraftTrackRepositoryTest.java
git commit -m "feat: add findByCallsign scan to AircraftTrackRepository"
```

---

## Task 12: AnalyticsService (Read Parquet from S3)

Lists Parquet objects under a date partition, downloads each, decodes via `ParquetSerializer`, and filters by airport. Local stand-in for Athena (the prod path). Missing data → empty list.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/service/AnalyticsService.java`
- Test: `skytrack/src/test/java/skytrack/demo/service/AnalyticsServiceTest.java`

### Step 1: Write the failing test (mocked S3Client)

**`AnalyticsServiceTest.java`:**

```java
package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.config.S3Properties;
import skytrack.demo.parquet.DelayParquetRow;
import skytrack.demo.parquet.ParquetSerializer;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {

    private final S3Properties props =
            new S3Properties("skytrack-history", null, "us-east-1", "delays", 300);

    private static DelayParquetRow row(String iata, long delay) {
        return new DelayParquetRow("abc", "UAL1", "UA", "1", "K" + iata, iata,
                1748528400L, 1748527500L, delay, "MAJOR_DELAY", "AEROAPI",
                1748528400000L, "IFR", 2.0, 800, 12);
    }

    @Test
    void shouldReadAndFilterRowsByAirport() throws Exception {
        var serializer = new ParquetSerializer();
        byte[] parquet = serializer.serialize(List.of(row("ORD", 900L), row("ATL", 1800L)));

        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("delays/year=2026/month=05/day=29/hour=14/x.parquet").build())
                        .build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), parquet));

        var service = new AnalyticsService(s3, serializer, props);
        List<DelayParquetRow> result = service.queryDelays("ORD", "2026-05-29");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).arrivalAirportIata()).isEqualTo("ORD");
    }

    @Test
    void shouldReturnEmptyWhenNoObjects() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().build());

        var service = new AnalyticsService(s3, new ParquetSerializer(), props);
        assertThat(service.queryDelays("ORD", "2026-05-29")).isEmpty();
    }
}
```

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest=AnalyticsServiceTest -q`
Expected: FAIL — class missing.

### Step 3: Implement

**`AnalyticsService.java`:**

```java
package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import skytrack.demo.config.S3Properties;
import skytrack.demo.parquet.DelayParquetRow;
import skytrack.demo.parquet.ParquetSerializer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final S3Client s3;
    private final ParquetSerializer serializer;
    private final S3Properties props;

    public AnalyticsService(S3Client s3, ParquetSerializer serializer, S3Properties props) {
        this.s3 = s3;
        this.serializer = serializer;
        this.props = props;
    }

    /** Reads all delay rows for a date (yyyy-MM-dd), optionally filtered by arrival IATA. */
    public List<DelayParquetRow> queryDelays(String airportIata, String date) {
        String prefix = datePrefix(date);
        List<DelayParquetRow> all = new ArrayList<>();
        try {
            var listed = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(props.bucket())
                    .prefix(prefix)
                    .build());
            for (var obj : listed.contents()) {
                if (!obj.key().endsWith(".parquet")) continue;
                byte[] bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(props.bucket()).key(obj.key()).build()).asByteArray();
                all.addAll(serializer.deserialize(bytes));
            }
        } catch (Exception e) {
            log.error("Analytics query failed for date={} airport={}: {}", date, airportIata, e.getMessage());
            return List.of();
        }
        if (airportIata == null || airportIata.isBlank()) {
            return all;
        }
        return all.stream()
                .filter(r -> airportIata.equals(r.arrivalAirportIata()))
                .toList();
    }

    private String datePrefix(String date) {
        LocalDate d = LocalDate.parse(date);
        return String.format("%s/year=%04d/month=%02d/day=%02d/",
                props.prefix(), d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }
}
```

### Step 4: Verify pass

Run: `cd skytrack && mvn test -Dtest=AnalyticsServiceTest -q`
Expected: PASS.

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/service/AnalyticsService.java \
        skytrack/src/test/java/skytrack/demo/service/AnalyticsServiceTest.java
git commit -m "feat: add AnalyticsService to read historical delays from S3 Parquet"
```

---

## Task 13: AirportController (status + disruptions)

Two endpoints. `/airports/{iata}/status` composes disruption score + current weather + recent cascades into a response DTO. `/airports/disruptions` returns the top-N list with optional `limit`/`minScore` filters.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/controller/AirportController.java`
- Create: `skytrack/src/main/java/skytrack/demo/model/AirportStatusResponse.java`
- Test: `skytrack/src/test/java/skytrack/demo/controller/AirportControllerTest.java`

### Step 1: Write the failing test (`@WebMvcTest` + MockMvc)

**`AirportControllerTest.java`:**

```java
package skytrack.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import skytrack.demo.model.AirportDisruptionScore;
import skytrack.demo.service.DisruptionScoreService;
import skytrack.demo.service.RecentCascadeStore;
import skytrack.demo.service.WeatherCache;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AirportController.class)
class AirportControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean DisruptionScoreService disruptionScoreService;
    @MockitoBean WeatherCache weatherCache;
    @MockitoBean RecentCascadeStore recentCascadeStore;

    @Test
    void shouldReturnAirportStatus() throws Exception {
        when(disruptionScoreService.computeScore("ORD")).thenReturn(
                new AirportDisruptionScore("ORD", 72.5, 8, 20, 35.0, 0.2, Instant.now()));
        when(weatherCache.get("KORD")).thenReturn(Optional.empty());
        when(recentCascadeStore.getRecent("ORD")).thenReturn(List.of());

        mockMvc.perform(get("/airports/ORD/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score.airportIata").value("ORD"))
                .andExpect(jsonPath("$.score.score").value(72.5));
    }

    @Test
    void shouldReturnTopDisruptions() throws Exception {
        when(disruptionScoreService.getTopDisruptedAirports(anyInt())).thenReturn(List.of(
                new AirportDisruptionScore("ORD", 80.0, 10, 25, 40.0, 0.3, Instant.now()),
                new AirportDisruptionScore("ATL", 55.0, 6, 18, 25.0, 0.1, Instant.now())));

        mockMvc.perform(get("/airports/disruptions").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].airportIata").value("ORD"))
                .andExpect(jsonPath("$[1].airportIata").value("ATL"));
    }

    @Test
    void shouldFilterByMinScore() throws Exception {
        when(disruptionScoreService.getTopDisruptedAirports(anyInt())).thenReturn(List.of(
                new AirportDisruptionScore("ORD", 80.0, 10, 25, 40.0, 0.3, Instant.now()),
                new AirportDisruptionScore("ATL", 55.0, 6, 18, 25.0, 0.1, Instant.now())));

        mockMvc.perform(get("/airports/disruptions").param("minScore", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].airportIata").value("ORD"));
    }
}
```

> **`@MockitoBean` is the Spring Boot 3.4+/4 replacement for `@MockBean`.** If it does not resolve, confirm the import `org.springframework.test.context.bean.override.mockito.MockitoBean`.

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest=AirportControllerTest -q`
Expected: FAIL — controller + DTO missing.

### Step 3: Implement

**`AirportStatusResponse.java`:**

```java
package skytrack.demo.model;

import java.util.List;

public record AirportStatusResponse(
        AirportDisruptionScore score,
        WeatherObservation weather,
        List<CascadeAlert> cascades
) {}
```

**`AirportController.java`:**

```java
package skytrack.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.AirportDisruptionScore;
import skytrack.demo.model.AirportStatusResponse;
import skytrack.demo.service.DisruptionScoreService;
import skytrack.demo.service.RecentCascadeStore;
import skytrack.demo.service.WeatherCache;

import java.util.List;

@RestController
public class AirportController {

    private final DisruptionScoreService disruptionScoreService;
    private final WeatherCache weatherCache;
    private final RecentCascadeStore recentCascadeStore;

    public AirportController(DisruptionScoreService disruptionScoreService,
                             WeatherCache weatherCache,
                             RecentCascadeStore recentCascadeStore) {
        this.disruptionScoreService = disruptionScoreService;
        this.weatherCache = weatherCache;
        this.recentCascadeStore = recentCascadeStore;
    }

    @GetMapping("/airports/{iata}/status")
    public AirportStatusResponse status(@PathVariable String iata) {
        AirportDisruptionScore score = disruptionScoreService.computeScore(iata);
        // Weather cache is keyed by ICAO (e.g. KORD); map common US IATA -> K-prefixed ICAO.
        var weather = weatherCache.get("K" + iata).orElse(null);
        return new AirportStatusResponse(score, weather, recentCascadeStore.getRecent(iata));
    }

    @GetMapping("/airports/disruptions")
    public List<AirportDisruptionScore> disruptions(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") double minScore) {
        return disruptionScoreService.getTopDisruptedAirports(limit).stream()
                .filter(s -> s.score() >= minScore)
                .toList();
    }
}
```

> **Note the IATA→ICAO mapping caveat.** `WeatherCache` is keyed by ICAO (`KORD`), endpoints take IATA (`ORD`). The `"K" + iata` shortcut works for contiguous-US airports only. Leave a `// TODO` and treat a proper mapping as out of scope for this chunk (it belongs with the airport reference data already loaded in `AirportLookupService`).

### Step 4: Verify pass

Run: `cd skytrack && mvn test -Dtest=AirportControllerTest -q`
Expected: PASS, all 3 tests green.

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/controller/AirportController.java \
        skytrack/src/main/java/skytrack/demo/model/AirportStatusResponse.java \
        skytrack/src/test/java/skytrack/demo/controller/AirportControllerTest.java
git commit -m "feat: add AirportController status and disruptions endpoints"
```

---

## Task 14: FlightController + CascadeController

`/flights/{callsign}` returns the current track (404 if unknown); `/cascades/{iata}` returns recent cascade alerts.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/controller/FlightController.java`
- Create: `skytrack/src/main/java/skytrack/demo/controller/CascadeController.java`
- Test: `skytrack/src/test/java/skytrack/demo/controller/FlightControllerTest.java`
- Test: `skytrack/src/test/java/skytrack/demo/controller/CascadeControllerTest.java`

### Step 1: Write the failing tests

**`FlightControllerTest.java`:**

```java
package skytrack.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import skytrack.demo.model.AircraftTrack;
import skytrack.demo.repository.AircraftTrackRepository;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlightController.class)
class FlightControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AircraftTrackRepository repository;

    @Test
    void shouldReturnTrackForKnownCallsign() throws Exception {
        AircraftTrack track = AircraftTrack.initial("abc123");
        track.setCallsign("UAL100");
        track.setLatitude(41.9);
        track.setLongitude(-87.9);
        when(repository.findByCallsign("UAL100")).thenReturn(Optional.of(track));

        mockMvc.perform(get("/flights/UAL100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icao24").value("abc123"))
                .andExpect(jsonPath("$.callsign").value("UAL100"));
    }

    @Test
    void shouldReturn404ForUnknownCallsign() throws Exception {
        when(repository.findByCallsign("ZZZ999")).thenReturn(Optional.empty());
        mockMvc.perform(get("/flights/ZZZ999")).andExpect(status().isNotFound());
    }
}
```

**`CascadeControllerTest.java`:**

```java
package skytrack.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import skytrack.demo.model.CascadeAlert;
import skytrack.demo.service.RecentCascadeStore;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CascadeController.class)
class CascadeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean RecentCascadeStore recentCascadeStore;

    @Test
    void shouldReturnRecentCascades() throws Exception {
        when(recentCascadeStore.getRecent("ORD")).thenReturn(List.of(
                new CascadeAlert("UAL1", "ORD", 2400L, 2040L, 0.85, Instant.now())));

        mockMvc.perform(get("/cascades/ORD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceCallsign").value("UAL1"))
                .andExpect(jsonPath("$[0].arrivalAirportIata").value("ORD"));
    }
}
```

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest='FlightControllerTest,CascadeControllerTest' -q`
Expected: FAIL — controllers missing.

### Step 3: Implement

**`FlightController.java`:**

```java
package skytrack.demo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.AircraftTrack;
import skytrack.demo.repository.AircraftTrackRepository;

@RestController
public class FlightController {

    private final AircraftTrackRepository repository;

    public FlightController(AircraftTrackRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/flights/{callsign}")
    public ResponseEntity<AircraftTrack> flight(@PathVariable String callsign) {
        return repository.findByCallsign(callsign)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

**`CascadeController.java`:**

```java
package skytrack.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.CascadeAlert;
import skytrack.demo.service.RecentCascadeStore;

import java.util.List;

@RestController
public class CascadeController {

    private final RecentCascadeStore recentCascadeStore;

    public CascadeController(RecentCascadeStore recentCascadeStore) {
        this.recentCascadeStore = recentCascadeStore;
    }

    @GetMapping("/cascades/{iata}")
    public List<CascadeAlert> cascades(@PathVariable String iata) {
        return recentCascadeStore.getRecent(iata);
    }
}
```

### Step 4: Verify pass

Run: `cd skytrack && mvn test -Dtest='FlightControllerTest,CascadeControllerTest' -q`
Expected: PASS.

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/controller/FlightController.java \
        skytrack/src/main/java/skytrack/demo/controller/CascadeController.java \
        skytrack/src/test/java/skytrack/demo/controller/FlightControllerTest.java \
        skytrack/src/test/java/skytrack/demo/controller/CascadeControllerTest.java
git commit -m "feat: add FlightController and CascadeController endpoints"
```

---

## Task 15: ScheduleController + AnalyticsController

`/schedule/coverage` returns resolution stats; `/analytics/delays` returns historical Parquet rows.

**Files:**
- Create: `skytrack/src/main/java/skytrack/demo/controller/ScheduleController.java`
- Create: `skytrack/src/main/java/skytrack/demo/controller/AnalyticsController.java`
- Test: `skytrack/src/test/java/skytrack/demo/controller/ScheduleControllerTest.java`
- Test: `skytrack/src/test/java/skytrack/demo/controller/AnalyticsControllerTest.java`

### Step 1: Write the failing tests

**`ScheduleControllerTest.java`:**

```java
package skytrack.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import skytrack.demo.model.ScheduleCoverage;
import skytrack.demo.service.ScheduleCoverageTracker;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScheduleController.class)
class ScheduleControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ScheduleCoverageTracker tracker;

    @Test
    void shouldReturnCoverageSnapshot() throws Exception {
        when(tracker.snapshot()).thenReturn(new ScheduleCoverage(100, 92, 5, 3, 0.92));

        mockMvc.perform(get("/schedule/coverage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(100))
                .andExpect(jsonPath("$.verified").value(92))
                .andExpect(jsonPath("$.verifiedRate").value(0.92));
    }
}
```

**`AnalyticsControllerTest.java`:**

```java
package skytrack.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import skytrack.demo.parquet.DelayParquetRow;
import skytrack.demo.service.AnalyticsService;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalyticsController.class)
class AnalyticsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AnalyticsService analyticsService;

    @Test
    void shouldReturnHistoricalDelays() throws Exception {
        when(analyticsService.queryDelays(eq("ORD"), eq("2026-05-29"))).thenReturn(List.of(
                new DelayParquetRow("abc", "UAL1", "UA", "1", "KORD", "ORD",
                        1748528400L, 1748527500L, 900L, "MAJOR_DELAY", "AEROAPI",
                        1748528400000L, "IFR", 2.0, 800, 12)));

        mockMvc.perform(get("/analytics/delays").param("airport", "ORD").param("date", "2026-05-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].arrivalAirportIata").value("ORD"))
                .andExpect(jsonPath("$[0].delaySeconds").value(900));
    }
}
```

### Step 2: Verify failure

Run: `cd skytrack && mvn test -Dtest='ScheduleControllerTest,AnalyticsControllerTest' -q`
Expected: FAIL — controllers missing.

### Step 3: Implement

**`ScheduleController.java`:**

```java
package skytrack.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.ScheduleCoverage;
import skytrack.demo.service.ScheduleCoverageTracker;

@RestController
public class ScheduleController {

    private final ScheduleCoverageTracker tracker;

    public ScheduleController(ScheduleCoverageTracker tracker) {
        this.tracker = tracker;
    }

    @GetMapping("/schedule/coverage")
    public ScheduleCoverage coverage() {
        return tracker.snapshot();
    }
}
```

**`AnalyticsController.java`:**

```java
package skytrack.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.parquet.DelayParquetRow;
import skytrack.demo.service.AnalyticsService;

import java.util.List;

@RestController
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/analytics/delays")
    public List<DelayParquetRow> delays(
            @RequestParam(required = false) String airport,
            @RequestParam String date) {
        return analyticsService.queryDelays(airport, date);
    }
}
```

### Step 4: Verify pass

Run: `cd skytrack && mvn test -Dtest='ScheduleControllerTest,AnalyticsControllerTest' -q`
Expected: PASS.

### Step 5: Commit

```bash
git add skytrack/src/main/java/skytrack/demo/controller/ScheduleController.java \
        skytrack/src/main/java/skytrack/demo/controller/AnalyticsController.java \
        skytrack/src/test/java/skytrack/demo/controller/ScheduleControllerTest.java \
        skytrack/src/test/java/skytrack/demo/controller/AnalyticsControllerTest.java
git commit -m "feat: add ScheduleController coverage and AnalyticsController delays endpoints"
```

---

## Task 16: Full Build, Manual Smoke Test & Wrap-Up

Verify the whole suite is green and the endpoints work against a live local stack.

### Step 1: Full test suite

Run: `cd skytrack && mvn clean test -q`
Expected: BUILD SUCCESS, all tests pass (Docker running for the Testcontainers integration tests).

### Step 2: Manual smoke test against LocalStack

```bash
# From repo root
docker compose up -d
cd skytrack && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

In another shell, after the app has consumed some replayed data and at least one flush interval (60s local) has elapsed:

```bash
curl -s "http://localhost:8080/airports/disruptions?limit=10" | jq
curl -s "http://localhost:8080/airports/ORD/status" | jq
curl -s "http://localhost:8080/schedule/coverage" | jq
curl -s "http://localhost:8080/analytics/delays?airport=ORD&date=$(date -u +%Y-%m-%d)" | jq
# Confirm Parquet objects landed in LocalStack S3:
awslocal s3 ls s3://skytrack-history/delays/ --recursive   # or: aws --endpoint-url=http://localhost:4566 s3 ls ...
```

Expected: endpoints return JSON; `/schedule/coverage` shows non-zero totals after landings; S3 lists `.parquet` objects under the partition path.

> **This is a manual verification step, not an automated test.** If replayed data yields no landings in the run window, use the 10x replay speed (set `opensky.replay-speed-multiplier: 10` in `application-local.yml`) to accelerate, or note in the checkpoint that live validation is deferred to the AWS chunk.

### Step 3: Final commit (if any docs/config tweaks were made during smoke testing)

```bash
git add -A
git commit -m "chore: chunk 7 smoke-test adjustments"
```

---

## Chunk 7 Deliverables

| Deliverable | Tasks | Priority |
|---|---|---|
| Carpet + S3 deps, validated round-trip | 1 | Critical |
| DelayParquetRow schema + ParquetSerializer | 2–3 | Critical |
| S3Config + HistoricalDelayWriter (scheduled flush) | 4–5 | Critical |
| Processor hook + LocalStack S3 wiring | 6–7 | Critical |
| LocalStack S3 integration test | 8 | High |
| ScheduleCoverageTracker + RecentCascadeStore | 9–10 | Critical |
| findByCallsign + AnalyticsService | 11–12 | High |
| 6 REST endpoints across 5 controllers | 13–15 | Critical |
| Full build + manual smoke test | 16 | Critical |

> ✅ **Checkpoint:** `mvn clean test` is green. With `docker compose up` and the app on the `local` profile, replayed flights flow through the pipeline → delay events are scored, buffered, and flushed to LocalStack S3 as partitioned Parquet → all six endpoints return meaningful JSON, and `/analytics/delays` reads back what the writer stored. AWS cost: still $0.

## Out of Scope (deferred to later chunks)

- **Production profile, Dockerfile, AWS `setup.sh`** (roadmap Day 28) — next chunk.
- **Proper IATA↔ICAO mapping** for the weather lookup in `/airports/{iata}/status` (currently the `"K" + iata` shortcut).
- **Callsign GSI** on the DynamoDB table (replacing the demo `findByCallsign` scan).
- **Athena** for `/analytics/delays` in prod (local reads Parquet directly from S3).
- **WebSocket push, response caching, OpenAPI spec** (roadmap Week 5).
