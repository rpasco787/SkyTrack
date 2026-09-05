package skytrack.demo.parquet;

import org.junit.jupiter.api.Tag;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
class HistoricalDelayWriterIntegrationTest {

    @SuppressWarnings("resource")
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
                1748528400L, 1748527500L, delay, DelayClassification.MAJOR,
                "AEROAPI", Instant.parse("2026-05-29T14:00:00Z"),
                FlightCategory.IFR, 2.0, 800, 12);
    }

    @Test
    void shouldWriteParquetToS3AndReadItBack() throws Exception {
        S3Client s3 = s3Client();
        s3.createBucket(CreateBucketRequest.builder().bucket("skytrack-history").build());

        var props = new S3Properties("skytrack-history", localstack.getEndpoint().toString(),
                localstack.getRegion(), "delays", "predictions", 300);
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
