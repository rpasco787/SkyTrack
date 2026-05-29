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
}
