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
            new S3Properties("skytrack-history", null, "us-east-1", "delays", "predictions", 300);

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
