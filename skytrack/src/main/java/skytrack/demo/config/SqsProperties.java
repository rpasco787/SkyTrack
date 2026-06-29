package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sqs")
public record SqsProperties(
        String endpoint,
        String region,
        String positionsQueueName,
        String airportEventsQueueName,
        int consumerThreads
) {
    public SqsProperties {
        if (region == null) region = "us-east-1";
        if (positionsQueueName == null) positionsQueueName = "skytrack-positions.fifo";
        if (airportEventsQueueName == null) airportEventsQueueName = "skytrack-airport-events.fifo";
        if (consumerThreads <= 0) consumerThreads = 1;
    }
}
