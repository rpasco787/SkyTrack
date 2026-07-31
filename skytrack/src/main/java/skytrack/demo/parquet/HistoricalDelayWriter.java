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

/**
 * Buffers delay events and flushes them to S3 as parquet.
 *
 * <p>Failed puts are retried on the next flush, which makes S3 writes <strong>at-least-once</strong>
 * rather than at-most-once: if a put throws after the object actually landed (response timeout,
 * connection reset, SDK retries exhausted with one attempt having succeeded), the batch is re-sent
 * under a new key, since {@link #partitionKey()} embeds the flush-time epoch millis. Duplicates are
 * therefore possible and downstream consumers must dedupe on
 * {@code icao24 + actualArrivalTime + createdAtEpochMillis}. This is a deliberate trade: duplicate
 * rows are recoverable downstream, dropped rows are not.
 */
@Service
public class HistoricalDelayWriter {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDelayWriter.class);

    /** Bounded so an extended S3 outage degrades to data loss rather than an OOM. */
    static final int MAX_RETAINED_EVENTS = 50_000;

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
        List<DelayParquetRow> rows;
        byte[] parquet;
        try {
            rows = batch.stream().map(DelayParquetRow::from).toList();
            parquet = serializer.serialize(rows);
        } catch (Exception e) {
            // Deterministic: the same batch would fail identically on every future flush, so
            // retaining it would wedge the buffer forever. Drop it and keep the writer alive.
            log.error("Dropping {} delay events — parquet serialization failed: {}",
                    batch.size(), e.getMessage());
            return;
        }
        try {
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
            requeue(batch);
            log.error("Failed to flush {} delay events to S3, retained for retry: {}",
                    batch.size(), e.getMessage());
        }
    }

    /**
     * Push the failed batch back for the next flush. drain() empties the buffer before the put, so
     * without this a transient S3 error silently discards the window.
     *
     * <p>Invariant: the buffer stays in chronological order across repeated failures. The batch was
     * drained before the put, so it is strictly older than anything that arrived while the put was
     * in flight; those arrivals are drained and re-appended <em>behind</em> the batch. Re-appending
     * the batch to the tail instead would leave the newest event at the head, and the next failure
     * would drain it back as the head of the batch and drop precisely the event worth keeping —
     * freezing a stale backlog while discarding every fresh arrival.
     *
     * <p>Because both lists are drained first, {@code overflow} is exact rather than a stale size
     * snapshot, and it is exactly the number of events dropped: trimming runs oldest-first across
     * the combined set, taking from the batch before the newer arrivals.
     */
    private void requeue(List<DelayEvent> batch) {
        List<DelayEvent> arrivedDuringFlush = drain();

        int overflow = batch.size() + arrivedDuringFlush.size() - MAX_RETAINED_EVENTS;
        if (overflow > 0) {
            int fromBatch = Math.min(overflow, batch.size());
            batch = batch.subList(fromBatch, batch.size());
            int remaining = overflow - fromBatch;
            if (remaining > 0) {
                arrivedDuringFlush = arrivedDuringFlush.subList(remaining, arrivedDuringFlush.size());
            }
            log.warn("Retry backlog exceeded {} events — dropped {} oldest",
                    MAX_RETAINED_EVENTS, overflow);
        }

        buffer.addAll(batch);
        buffer.addAll(arrivedDuringFlush);
    }

    int retainedCount() {
        return buffer.size();
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
