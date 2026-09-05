package skytrack.demo.sqs;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.config.SqsProperties;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsQueueDepthMonitorTest {

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/skytrack-positions.fifo";

    @Mock private SqsClient sqsClient;
    private SimpleMeterRegistry registry;
    private SqsQueueDepthMonitor monitor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        var props = new SqsProperties(null, null, "skytrack-positions.fifo", null, null, 4, 8);
        monitor = new SqsQueueDepthMonitor(sqsClient, props, registry);
    }

    private double gauge() {
        return registry.get(SqsQueueDepthMonitor.METRIC).gauge().value();
    }

    @Test
    void gaugeIsRegisteredAtConstructionWithNoValueYet() {
        assertThat(gauge()).isNaN();
        verifyNoInteractions(sqsClient);   // nothing resolved until the first tick
    }

    @Test
    void samplePublishesApproximateNumberOfMessagesForThePositionsQueue() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build());
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "42"))
                        .build());

        monitor.sample();

        assertThat(gauge()).isEqualTo(42.0);
        var captor = org.mockito.ArgumentCaptor.forClass(GetQueueAttributesRequest.class);
        verify(sqsClient).getQueueAttributes(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(captor.getValue().attributeNames()).containsExactly(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
    }

    @Test
    void resolvesTheQueueUrlOnceAndReusesIt() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build());
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "1"))
                        .build());

        monitor.sample();
        monitor.sample();

        verify(sqsClient, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
        verify(sqsClient, times(2)).getQueueAttributes(any(GetQueueAttributesRequest.class));
    }

    @Test
    void failureClearsTheGaugeInsteadOfLeavingAStaleValue() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build());
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "42"))
                        .build())
                .thenThrow(SqsException.builder().message("unreachable").build());

        monitor.sample();
        monitor.sample();

        assertThat(gauge()).as("a stale 42 would look like a healthy backlog").isNaN();
    }
}
