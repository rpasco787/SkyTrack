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
import java.util.concurrent.Executors;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SqsRoundTripIntegrationTest {

    @SuppressWarnings("resource")
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
        var producer = new SqsPositionProducer(sqsClient, queueUrl, Executors.newFixedThreadPool(4));
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
        var producer = new SqsPositionProducer(sqsClient, queueUrl, Executors.newFixedThreadPool(4));
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
        var producer = new SqsPositionProducer(sqsClient, queueUrl, Executors.newFixedThreadPool(4));
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
