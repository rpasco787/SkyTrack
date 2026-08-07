package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sqs")
public record SqsProperties(
        String endpoint,
        String region,
        String positionsQueueName,
        String airportEventsQueueName,
        String predictionQueueName,
        int consumerThreads,
        int producerThreads
) {
    public SqsProperties {
        if (region == null) region = "us-east-1";
        if (positionsQueueName == null) positionsQueueName = "skytrack-positions.fifo";
        if (airportEventsQueueName == null) airportEventsQueueName = "skytrack-airport-events.fifo";
        if (predictionQueueName == null) predictionQueueName = "skytrack-predictions.fifo";
        if (consumerThreads <= 0) consumerThreads = 1;
        if (producerThreads <= 0) producerThreads = 8;
    }
}
