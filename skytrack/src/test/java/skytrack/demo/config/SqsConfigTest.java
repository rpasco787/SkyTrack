package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

import static org.assertj.core.api.Assertions.assertThat;

class SqsConfigTest {

    @Test
    void shouldCreateSqsClientWithLocalStackEndpoint() {
        var props = new SqsProperties(
                "http://localhost:4566",
                "us-east-1",
                "skytrack-positions.fifo",
                "skytrack-airport-events.fifo",
                1
        );
        var config = new SqsConfig();

        SqsClient client = config.sqsClient(props);

        assertThat(client).isNotNull();
        client.close();
    }
}
