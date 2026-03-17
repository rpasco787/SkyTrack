package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbPropertiesTest {

    @Test
    void shouldBindProperties() {
        var props = new DynamoDbProperties("skytrack-aircraft", "http://localhost:4566", "us-east-1");
        assertThat(props.tableName()).isEqualTo("skytrack-aircraft");
        assertThat(props.endpoint()).isEqualTo("http://localhost:4566");
        assertThat(props.region()).isEqualTo("us-east-1");
    }

    @Test
    void shouldAllowNullEndpointForProd() {
        var props = new DynamoDbProperties("skytrack-aircraft", null, "us-east-1");
        assertThat(props.endpoint()).isNull();
    }
}
