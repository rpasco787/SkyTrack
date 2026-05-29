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
