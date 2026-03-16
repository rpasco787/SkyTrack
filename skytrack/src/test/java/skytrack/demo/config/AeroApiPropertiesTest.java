package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AeroApiPropertiesTest {
    @Test
    void shouldApplyDefaults() {
        var props = new AeroApiProperties(null, null, null, 0, 0);
        assertThat(props.enabled()).isFalse();
        assertThat(props.baseUrl()).isEqualTo("https://aeroapi.flightaware.com/aeroapi");
        assertThat(props.maxMonthlyCalls()).isEqualTo(10_000);
        assertThat(props.requestTimeoutMs()).isEqualTo(5_000);
    }

    @Test
    void shouldPreserveExplicitValues() {
        var props = new AeroApiProperties(true, "http://localhost:9090/aeroapi", "test-key", 500, 3000);
        assertThat(props.enabled()).isTrue();
        assertThat(props.baseUrl()).isEqualTo("http://localhost:9090/aeroapi");
        assertThat(props.apiKey()).isEqualTo("test-key");
    }
}
