package skytrack.demo.config;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.client.LiveOpenSkyClient;
import skytrack.demo.client.ReplayOpenSkyClient;

import static org.assertj.core.api.Assertions.assertThat;

class FlightDataSourceConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCreateReplayClientWhenModeIsReplay() {
        var props = new OpenSkyProperties("replay", null, null, null, "./data/recorded-opensky/", 1);
        var config = new FlightDataSourceConfig();

        FlightDataSource source = config.flightDataSource(props, mapper);

        assertThat(source).isInstanceOf(ReplayOpenSkyClient.class);
    }

    @Test
    void shouldCreateLiveClientWhenModeIsLive() {
        var props = new OpenSkyProperties("live", "https://opensky-network.org", null, null, null, 1);
        var config = new FlightDataSourceConfig();

        FlightDataSource source = config.flightDataSource(props, mapper);

        assertThat(source).isInstanceOf(LiveOpenSkyClient.class);
    }
}
