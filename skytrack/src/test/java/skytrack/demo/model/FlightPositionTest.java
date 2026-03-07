package skytrack.demo.model;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FlightPositionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCreateFlightPositionWithAllFields() {
        var fp = new FlightPosition(
                "abc123", "UAL1234", 41.9742, -87.9073,
                10668.0, 230.5, 270.0, false,
                1709312400L, 1709312400L, Instant.now()
        );

        assertThat(fp.icao24()).isEqualTo("abc123");
        assertThat(fp.callsign()).isEqualTo("UAL1234");
        assertThat(fp.latitude()).isEqualTo(41.9742);
        assertThat(fp.longitude()).isEqualTo(-87.9073);
        assertThat(fp.onGround()).isFalse();
    }

    @Test
    void shouldSerializeToJsonAndBack() throws Exception {
        var original = new FlightPosition(
                "abc123", "UAL1234", 41.9742, -87.9073,
                10668.0, 230.5, 270.0, false,
                1709312400L, 1709312400L, Instant.now()
        );

        String json = mapper.writeValueAsString(original);
        FlightPosition deserialized = mapper.readValue(json, FlightPosition.class);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void shouldTrimCallsignWhitespace() {
        var fp = new FlightPosition(
                "abc123", "  UAL1234  ", 41.9742, -87.9073,
                10668.0, 230.5, 270.0, false,
                1709312400L, 1709312400L, Instant.now()
        );

        assertThat(fp.callsign()).isEqualTo("UAL1234");
    }
}
