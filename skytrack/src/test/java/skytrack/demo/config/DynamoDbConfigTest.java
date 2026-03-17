package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbConfigTest {

    @Test
    void shouldCreateDynamoDbClientWithEndpointOverride() {
        var props = new DynamoDbProperties("skytrack-aircraft", "http://localhost:4566", "us-east-1");
        var config = new DynamoDbConfig();
        DynamoDbClient client = config.dynamoDbClient(props);
        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    void shouldCreateEnhancedClient() {
        var props = new DynamoDbProperties("skytrack-aircraft", "http://localhost:4566", "us-east-1");
        var config = new DynamoDbConfig();
        DynamoDbClient client = config.dynamoDbClient(props);
        DynamoDbEnhancedClient enhanced = config.dynamoDbEnhancedClient(client);
        assertThat(enhanced).isNotNull();
        client.close();
    }
}
