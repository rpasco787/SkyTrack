package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqsPropertiesTest {

    @Test
    void defaultsConsumerThreadsToOne() {
        var props = new SqsProperties(null, null, null, null, null, 0);
        assertThat(props.consumerThreads()).isEqualTo(1);
    }

    @Test
    void honoursConfiguredConsumerThreads() {
        var props = new SqsProperties(null, "us-east-1", "q.fifo", "e.fifo", null, 4);
        assertThat(props.consumerThreads()).isEqualTo(4);
    }

    @Test
    void clampsNegativeConsumerThreadsToOne() {
        var props = new SqsProperties(null, null, null, null, null, -5);
        assertThat(props.consumerThreads()).isEqualTo(1);
    }

    @Test
    void defaultsPredictionQueueName() {
        var props = new SqsProperties(null, null, null, null, null, 1);
        assertThat(props.predictionQueueName()).isEqualTo("skytrack-predictions.fifo");
    }
}
