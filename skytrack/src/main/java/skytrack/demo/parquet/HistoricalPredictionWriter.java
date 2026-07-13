package skytrack.demo.parquet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.config.S3Properties;
import skytrack.demo.model.PredictedDelayEvent;
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
public class HistoricalPredictionWriter {

    private static final Logger log = LoggerFactory.getLogger(HistoricalPredictionWriter.class);

    private final ConcurrentLinkedQueue<PredictedDelayEvent> buffer = new ConcurrentLinkedQueue<>();
    private final S3Client s3;
    private final ParquetSerializer serializer;
    private final S3Properties props;
    private final Clock clock;

    public HistoricalPredictionWriter(S3Client s3, ParquetSerializer serializer,
                                      S3Properties props, Clock clock) {
        this.s3 = s3;
        this.serializer = serializer;
        this.props = props;
        this.clock = clock;
    }

    public void buffer(PredictedDelayEvent event) {
        buffer.add(event);
    }

    @Scheduled(fixedRateString = "#{${skytrack.s3.flush-interval-seconds:300} * 1000}",
               initialDelay = 10_000)
    public void flush() {
        List<PredictedDelayEvent> batch = drain();
        if (batch.isEmpty()) {
            return;
        }
        try {
            List<PredictionParquetRow> rows = batch.stream().map(PredictionParquetRow::from).toList();
            byte[] parquet = serializer.serializePredictions(rows);
            String key = partitionKey();
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(key)
                            .contentType("application/octet-stream")
                            .build(),
                    RequestBody.fromBytes(parquet));
            log.info("Flushed {} predicted delay events to s3://{}/{}", rows.size(), props.bucket(), key);
        } catch (Exception e) {
            log.error("Failed to flush {} predicted delay events to S3: {}", batch.size(), e.getMessage());
        }
    }

    private List<PredictedDelayEvent> drain() {
        List<PredictedDelayEvent> batch = new ArrayList<>();
        PredictedDelayEvent e;
        while ((e = buffer.poll()) != null) {
            batch.add(e);
        }
        return batch;
    }

    private String partitionKey() {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return String.format("predictions/year=%04d/month=%02d/day=%02d/hour=%02d/predictions-%d.parquet",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(), now.getHour(),
                clock.instant().toEpochMilli());
    }
}
