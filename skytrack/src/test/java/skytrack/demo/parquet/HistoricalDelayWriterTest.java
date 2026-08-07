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
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalDelayWriterTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-29T14:05:00Z"), ZoneOffset.UTC);
    private final S3Properties props =
            new S3Properties("skytrack-history", "http://localhost:4566", "us-east-1", "delays", "predictions", 300);

    private static DelayEvent event(String iata) {
        return new DelayEvent("abc", "UAL1", "UA", "1", "K" + iata, iata,
                1748528400L, 1748527500L, 900L, DelayClassification.MAJOR,
                "AEROAPI", Instant.parse("2026-05-29T14:00:00Z"),
                FlightCategory.IFR, 2.0, 800, 12);
    }

    /** Identity-bearing event, so a test can prove <em>which</em> events survived a drop. */
    private static DelayEvent numbered(int i) {
        return new DelayEvent("abc", "UAL" + i, "UA", String.valueOf(i), "KORD", "ORD",
                1748528400L, 1748527500L, 900L, DelayClassification.MAJOR,
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

    @Test
    void retainsTheBatchForRetryWhenS3PutFails() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("s3 down"))
                .thenReturn(PutObjectResponse.builder().build());
        ParquetSerializer serializer = spy(new ParquetSerializer());
        var writer = new HistoricalDelayWriter(s3, serializer, props, clock);

        writer.buffer(numbered(7));
        writer.flush(); // fails — batch must survive

        writer.flush(); // succeeds — must re-send the same event

        verify(s3, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        ArgumentCaptor<List<DelayParquetRow>> rows = ArgumentCaptor.captor();
        verify(serializer, times(2)).serialize(rows.capture());
        assertThat(rows.getAllValues().get(1))
                .as("the retry must re-send the very event the failed put dropped")
                .extracting(DelayParquetRow::callsign)
                .containsExactly("UAL7");
        assertThat(writer.retainedCount())
                .as("a successful retry must clear the backlog")
                .isZero();
    }

    @Test
    void dropsTheBatchWhenSerializationFailsRatherThanRetryingForever() throws Exception {
        S3Client s3 = mock(S3Client.class);
        ParquetSerializer serializer = mock(ParquetSerializer.class);
        when(serializer.serialize(any())).thenThrow(new IOException("unserializable row"));
        var writer = new HistoricalDelayWriter(s3, serializer, props, clock);

        writer.buffer(event("ORD"));
        writer.flush();

        assertThat(writer.retainedCount())
                .as("a deterministic serialization failure can never succeed on retry")
                .isZero();

        writer.flush();
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void dropsOldestWhenTheRetryBacklogExceedsItsCap() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("s3 down"))
                .thenReturn(PutObjectResponse.builder().build());
        ParquetSerializer serializer = spy(new ParquetSerializer());
        var writer = new HistoricalDelayWriter(s3, serializer, props, clock);

        int cap = HistoricalDelayWriter.MAX_RETAINED_EVENTS;
        for (int i = 0; i < cap + 10; i++) {
            writer.buffer(numbered(i));
        }
        writer.flush();

        assertThat(writer.retainedCount())
                .as("must retain exactly as much as the cap allows — no more, no less")
                .isEqualTo(cap);

        writer.flush(); // succeeds — reveals which events survived
        ArgumentCaptor<List<DelayParquetRow>> rows = ArgumentCaptor.captor();
        verify(serializer, times(2)).serialize(rows.capture());
        List<DelayParquetRow> retained = rows.getAllValues().get(1);
        assertThat(retained).hasSize(cap);
        assertThat(retained.get(0).callsign())
                .as("the 10 oldest events are the ones that must have been dropped")
                .isEqualTo("UAL10");
        assertThat(retained.get(retained.size() - 1).callsign())
                .as("the newest event must always survive")
                .isEqualTo("UAL" + (cap + 9));
    }

    @Test
    void keepsTheNewestWhenEventsArriveDuringAFailedPut() throws Exception {
        S3Client s3 = mock(S3Client.class);
        ParquetSerializer serializer = spy(new ParquetSerializer());
        var writer = new HistoricalDelayWriter(s3, serializer, props, clock);

        int cap = HistoricalDelayWriter.MAX_RETAINED_EVENTS;
        // The pipeline keeps buffering while the (slow) put is in flight; drain() has already
        // emptied the buffer, so this newer event lands ahead of the batch being retried.
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    writer.buffer(numbered(999_999));
                    throw new RuntimeException("s3 down");
                })
                .thenReturn(PutObjectResponse.builder().build());

        for (int i = 0; i < cap; i++) {
            writer.buffer(numbered(i));
        }
        writer.flush(); // fails, and one newer event arrives mid-put

        assertThat(writer.retainedCount()).isEqualTo(cap);

        writer.flush();
        ArgumentCaptor<List<DelayParquetRow>> rows = ArgumentCaptor.captor();
        verify(serializer, times(2)).serialize(rows.capture());
        assertThat(rows.getAllValues().get(1))
                .as("overflow must evict the oldest event, never the one that just arrived")
                .extracting(DelayParquetRow::callsign)
                .contains("UAL999999")
                .doesNotContain("UAL0");
    }

    @Test
    void keepsEveryArrivalAcrossConsecutiveFailedPuts() throws Exception {
        S3Client s3 = mock(S3Client.class);
        ParquetSerializer serializer = spy(new ParquetSerializer());
        var writer = new HistoricalDelayWriter(s3, serializer, props, clock);

        int cap = HistoricalDelayWriter.MAX_RETAINED_EVENTS;
        // A sustained outage: two failed puts, each with an event arriving mid-put. The retained
        // batch must go back ahead of those arrivals, or cycle 2 finds the newest event at the
        // head of the batch and trims exactly the event it should be keeping.
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> {
                    writer.buffer(numbered(1_000_000));
                    throw new RuntimeException("s3 down");
                })
                .thenAnswer(inv -> {
                    writer.buffer(numbered(1_000_001));
                    throw new RuntimeException("s3 down");
                })
                .thenReturn(PutObjectResponse.builder().build());

        for (int i = 0; i < cap; i++) {
            writer.buffer(numbered(i));
        }
        writer.flush(); // fail 1
        writer.flush(); // fail 2
        writer.flush(); // succeeds

        ArgumentCaptor<List<DelayParquetRow>> rows = ArgumentCaptor.captor();
        verify(serializer, times(3)).serialize(rows.capture());
        List<DelayParquetRow> retained = rows.getAllValues().get(2);

        assertThat(retained).hasSize(cap);
        assertThat(retained)
                .as("every event that arrived mid-outage must survive; only the oldest are dropped")
                .extracting(DelayParquetRow::callsign)
                .contains("UAL1000000", "UAL1000001")
                .doesNotContain("UAL0", "UAL1");
        assertThat(retained.get(0).callsign()).isEqualTo("UAL2");
        assertThat(retained)
                .as("the retained set must stay chronological, so parquet column stats stay tight")
                .extracting(DelayParquetRow::callsign)
                .endsWith("UAL1000000", "UAL1000001");
    }

    @Test
    void shouldDropOldestOnceTheIngestBufferIsFull() throws Exception {
        // MAX_RETAINED_EVENTS was previously enforced only inside requeue(), i.e. only on the
        // S3-failure path. A pipeline that never fails a put had no cap on ingest at all.
        S3Client s3 = mock(S3Client.class);
        ParquetSerializer serializer = spy(new ParquetSerializer());
        var writer = new HistoricalDelayWriter(s3, serializer, props, clock);

        int cap = HistoricalDelayWriter.MAX_RETAINED_EVENTS;
        for (int i = 0; i < cap + 100; i++) {
            writer.buffer(numbered(i));
        }

        assertThat(writer.retainedCount()).isEqualTo(cap);

        writer.flush();   // reveals which events survived
        ArgumentCaptor<List<DelayParquetRow>> rows = ArgumentCaptor.captor();
        verify(serializer).serialize(rows.capture());
        List<DelayParquetRow> retained = rows.getValue();
        assertThat(retained).hasSize(cap);
        assertThat(retained.get(0).callsign())
                .as("the 100 oldest must be the ones dropped, matching requeue()'s policy")
                .isEqualTo("UAL100");
        assertThat(retained.get(retained.size() - 1).callsign())
                .as("the newest event must always survive")
                .isEqualTo("UAL" + (cap + 99));
    }

    @Test
    void shouldReportRetainedCountWithoutScanningTheQueue() {
        // retainedCount() is backed by a counter, not ConcurrentLinkedQueue.size(), which is O(n)
        // and would be walked once per buffered event on the hot path.
        S3Client s3 = mock(S3Client.class);
        var writer = new HistoricalDelayWriter(s3, new ParquetSerializer(), props, clock);

        writer.buffer(numbered(1));
        writer.buffer(numbered(2));

        assertThat(writer.retainedCount()).isEqualTo(2);
    }

}
