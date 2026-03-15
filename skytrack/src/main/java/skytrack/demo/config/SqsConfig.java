package skytrack.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import skytrack.demo.service.FlightPositionHandler;
import skytrack.demo.sqs.SqsPositionConsumer;
import skytrack.demo.sqs.SqsPositionProducer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;

import java.net.URI;

@Configuration
public class SqsConfig {

    @Bean
    public SqsClient sqsClient(SqsProperties properties) {
        var builder = SqsClient.builder()
                .region(Region.of(properties.region()));

        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }

        return builder.build();
    }

    @Bean
    public SqsPositionProducer sqsPositionProducer(SqsClient sqsClient, SqsProperties properties) {
        String queueUrl = resolveQueueUrl(sqsClient, properties.positionsQueueName());
        return new SqsPositionProducer(sqsClient, queueUrl);
    }

    @Bean
    public SqsPositionConsumer sqsPositionConsumer(SqsClient sqsClient, SqsProperties properties,
                                                    FlightPositionHandler handler) {
        String queueUrl = resolveQueueUrl(sqsClient, properties.positionsQueueName());
        return new SqsPositionConsumer(sqsClient, queueUrl, handler);
    }

    private String resolveQueueUrl(SqsClient sqsClient, String queueName) {
        return sqsClient.getQueueUrl(
                GetQueueUrlRequest.builder()
                        .queueName(queueName)
                        .build()
        ).queueUrl();
    }
}
