package skytrack.demo.sqs;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SqsAirportEventProducerTest {

    @SuppressWarnings("resource")
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices(LocalStackContainer.Service.SQS);

    private static SqsClient sqsClient;
    private static String queueUrl;
    private static SqsAirportEventProducer producer;

    @BeforeAll
    static void setUp() {
        sqsClient = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();

        queueUrl = sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName("skytrack-airport-events.fifo")
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                .build()).queueUrl();

        producer = new SqsAirportEventProducer(sqsClient, queueUrl, queueUrl);
    }

    @AfterAll
    static void tearDown() {
        if (sqsClient != null) sqsClient.close();
    }

    @Test
    void shouldPublishDelayEventToQueue() {
        var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L,
                DelayClassification.MODERATE, "AEROAPI", Instant.now(),
                null, null, null, null);

        producer.send(event);

        var messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(5)
                .build()).messages();

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).body()).contains("UAL1234");
        assertThat(messages.get(0).body()).contains("ORD");
        assertThat(messages.get(0).body()).contains("MODERATE");
    }

    @Test
    void shouldUseAirportCodeAsMessageGroupId() {
        var event = new DelayEvent("def456", "DAL567", "DL", "567",
                "KLAX", "LAX", 1709312500L, 1709311600L, 900L,
                DelayClassification.MODERATE, "AEROAPI", Instant.now(),
                null, null, null, null);

        producer.send(event);

        var messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(5)
                .build()).messages();

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).body()).contains("LAX");
    }
}
