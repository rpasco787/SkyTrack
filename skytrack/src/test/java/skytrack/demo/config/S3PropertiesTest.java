package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class S3PropertiesTest {

    @Test
    void shouldApplyDefaults() {
        var props = new S3Properties(null, null, null, null, 0);
        assertThat(props.bucket()).isEqualTo("skytrack-history");
        assertThat(props.region()).isEqualTo("us-east-1");
        assertThat(props.prefix()).isEqualTo("delays");
        assertThat(props.flushIntervalSeconds()).isEqualTo(300);
    }

    @Test
    void shouldRetainProvidedValues() {
        var props = new S3Properties("my-bucket", "http://localhost:4566",
                "us-west-2", "events", 60);
        assertThat(props.bucket()).isEqualTo("my-bucket");
        assertThat(props.endpoint()).isEqualTo("http://localhost:4566");
        assertThat(props.region()).isEqualTo("us-west-2");
        assertThat(props.prefix()).isEqualTo("events");
        assertThat(props.flushIntervalSeconds()).isEqualTo(60);
    }
}
