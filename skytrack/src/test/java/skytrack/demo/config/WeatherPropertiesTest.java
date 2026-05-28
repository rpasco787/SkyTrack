package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherPropertiesTest {

    @Test
    void shouldApplyDefaults() {
        var props = new WeatherProperties(null, null, null, null, 0, 0, null);
        assertThat(props.mode()).isEqualTo("replay");
        assertThat(props.apiUrl()).isEqualTo("https://aviationweather.gov/api/data/metar");
        assertThat(props.replayDir()).isEqualTo("./data/recorded-weather/");
        assertThat(props.requestTimeoutMs()).isEqualTo(5000);
        assertThat(props.pollIntervalMinutes()).isEqualTo(15);
        assertThat(props.cacheTtlMinutes()).isEqualTo(30);
        assertThat(props.targetAirports()).isEmpty();
    }

    @Test
    void shouldRetainProvidedValues() {
        var props = new WeatherProperties(
                "live",
                "https://example.com/metar",
                "./data/test-weather/",
                10000,
                5,
                60,
                List.of("KORD", "KATL"));
        assertThat(props.mode()).isEqualTo("live");
        assertThat(props.targetAirports()).containsExactly("KORD", "KATL");
        assertThat(props.pollIntervalMinutes()).isEqualTo(5);
    }
}
