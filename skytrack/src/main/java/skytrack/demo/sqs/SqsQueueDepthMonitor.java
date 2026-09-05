package skytrack.demo.sqs;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import skytrack.demo.config.SqsProperties;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Publishes the positions queue's {@code ApproximateNumberOfMessages} as
 * {@code skytrack_sqs_consumer_lag_messages}, sampled every 10 s.
 *
 * <p>The queue URL is resolved on the first tick rather than in the constructor: {@code SqsConfig}
 * already resolves URLs while wiring beans (which is why every {@code @SpringBootTest} here has to
 * mock those beans), and a metrics sidecar must never be a reason the app fails to start.</p>
 *
 * <p>On failure the gauge is set to {@code NaN} — a gap in Grafana — rather than left at its last
 * value, which would read as a healthy, unchanging backlog.</p>
 */
@Component
public class SqsQueueDepthMonitor {

    private static final Logger log = LoggerFactory.getLogger(SqsQueueDepthMonitor.class);

    /** Exported verbatim: Prometheus name {@code skytrack_sqs_consumer_lag_messages}. */
    public static final String METRIC = "skytrack.sqs.consumer.lag.messages";

    private final SqsClient sqsClient;
    private final String queueName;
    private volatile String queueUrl;
    private volatile double depth = Double.NaN;

    public SqsQueueDepthMonitor(SqsClient sqsClient, SqsProperties properties, MeterRegistry registry) {
        this.sqsClient = sqsClient;
        this.queueName = properties.positionsQueueName();
        // Gauges hold a weak reference to the state object; `this` is held strongly by the context.
        Gauge.builder(METRIC, this, m -> m.depth)
                .description("ApproximateNumberOfMessages on the positions queue (consumer backlog)")
                .register(registry);
    }

    @Scheduled(fixedRate = 10_000)
    public void sample() {
        try {
            if (queueUrl == null) {
                queueUrl = sqsClient.getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(queueName).build()).queueUrl();
            }
            var response = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                    .build());
            depth = Double.parseDouble(
                    response.attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        } catch (Exception e) {
            depth = Double.NaN;
            log.warn("Could not sample depth of {}: {}", queueName, e.getMessage());
        }
    }
}
