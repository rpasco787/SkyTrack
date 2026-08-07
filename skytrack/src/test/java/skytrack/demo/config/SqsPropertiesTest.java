package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqsPropertiesTest {

    @Test
    void defaultsConsumerThreadsToOne() {
        var props = new SqsProperties(null, null, null, null, null, 0, 0);
        assertThat(props.consumerThreads()).isEqualTo(1);
    }

    @Test
    void honoursConfiguredConsumerThreads() {
        var props = new SqsProperties(null, "us-east-1", "q.fifo", "e.fifo", null, 4, 8);
        assertThat(props.consumerThreads()).isEqualTo(4);
    }

    @Test
    void clampsNegativeConsumerThreadsToOne() {
        var props = new SqsProperties(null, null, null, null, null, -5, -5);
        assertThat(props.consumerThreads()).isEqualTo(1);
    }

    @Test
    void defaultsProducerThreadsToEight() {
        var props = new SqsProperties(null, null, null, null, null, 0, 0);
        assertThat(props.producerThreads()).isEqualTo(8);
    }

    @Test
    void clampsNegativeProducerThreadsToEight() {
        var props = new SqsProperties(null, null, null, null, null, 0, -5);
        assertThat(props.producerThreads()).isEqualTo(8);
    }

    @Test
    void honoursConfiguredProducerThreads() {
        var props = new SqsProperties(null, null, null, null, null, 4, 16);
        assertThat(props.producerThreads()).isEqualTo(16);
    }

    @Test
    void defaultsPredictionQueueName() {
        var props = new SqsProperties(null, null, null, null, null, 1, 8);
        assertThat(props.predictionQueueName()).isEqualTo("skytrack-predictions.fifo");
    }
}
