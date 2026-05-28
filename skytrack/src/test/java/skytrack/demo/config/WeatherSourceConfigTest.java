package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import skytrack.demo.client.LiveAviationWeatherClient;
import skytrack.demo.client.ReplayAviationWeatherClient;
import skytrack.demo.client.WeatherSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeatherSourceConfigTest {

    private final WeatherSourceConfig config = new WeatherSourceConfig();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldReturnReplayClientForReplayMode() {
        var props = new WeatherProperties("replay", null, "./data/recorded-weather/",
                5000, 15, 30, List.of("KORD"));
        WeatherSource source = config.weatherSource(props, mapper);
        assertThat(source).isInstanceOf(ReplayAviationWeatherClient.class);
    }

    @Test
    void shouldReturnLiveClientForLiveMode() {
        var props = new WeatherProperties("live", "https://example.com/metar",
                null, 5000, 15, 30, List.of("KORD"));
        WeatherSource source = config.weatherSource(props, mapper);
        assertThat(source).isInstanceOf(LiveAviationWeatherClient.class);
    }

    @Test
    void shouldRejectUnknownMode() {
        var props = new WeatherProperties("garbage", null, null,
                5000, 15, 30, List.of());
        assertThatThrownBy(() -> config.weatherSource(props, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weather.mode");
    }
}
