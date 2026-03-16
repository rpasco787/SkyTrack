package skytrack.demo.config;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import skytrack.demo.client.AeroApiClient;
import skytrack.demo.client.FlightScheduleApiClient;
import static org.assertj.core.api.Assertions.assertThat;

class AeroApiConfigTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCreateAeroApiClientBean() {
        var props = new AeroApiProperties(true, "http://localhost:9090/aeroapi", "test-key", 100, 5000);
        var config = new AeroApiConfig();
        FlightScheduleApiClient client = config.flightScheduleApiClient(props, mapper);
        assertThat(client).isInstanceOf(AeroApiClient.class);
    }
}
