package skytrack.demo.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import skytrack.demo.model.FlightPosition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiveOpenSkyClientTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    void shouldParseOpenSkyStateVectorArray() throws Exception {
        // OpenSky returns state vectors as positional arrays:
        // [0]=icao24, [1]=callsign, [2]=origin_country, [3]=time_position,
        // [4]=last_contact, [5]=longitude, [6]=latitude, [7]=baro_altitude,
        // [8]=on_ground, [9]=velocity, [10]=true_track(heading), ...
        String json = """
                {
                  "time": 1709312400,
                  "states": [
                    ["abc123", "UAL1234 ", "United States", 1709312400, 1709312400,
                     -87.9073, 41.9742, 10668.0, false, 230.5, 270.0,
                     null, null, null, null, false, 0]
                  ]
                }
                """;

        JsonNode root = mapper.readTree(json);
        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        assertThat(positions).hasSize(1);
        FlightPosition fp = positions.getFirst();
        assertThat(fp.icao24()).isEqualTo("abc123");
        assertThat(fp.callsign()).isEqualTo("UAL1234");
        assertThat(fp.latitude()).isEqualTo(41.9742);
        assertThat(fp.longitude()).isEqualTo(-87.9073);
        assertThat(fp.baroAltitude()).isEqualTo(10668.0);
        assertThat(fp.velocity()).isEqualTo(230.5);
        assertThat(fp.heading()).isEqualTo(270.0);
        assertThat(fp.onGround()).isFalse();
    }

    @Test
    void shouldFilterToUSFlightsOnly() throws Exception {
        String json = """
                {
                  "time": 1709312400,
                  "states": [
                    ["abc123", "UAL1234 ", "United States", 1709312400, 1709312400,
                     -87.9073, 41.9742, 10668.0, false, 230.5, 270.0,
                     null, null, null, null, false, 0],
                    ["def456", "BAW456  ", "United Kingdom", 1709312400, 1709312400,
                     -0.4614, 51.4700, 11000.0, false, 250.0, 90.0,
                     null, null, null, null, false, 0]
                  ]
                }
                """;

        JsonNode root = mapper.readTree(json);
        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        // Only the US flight should be included
        assertThat(positions).hasSize(1);
        assertThat(positions.getFirst().icao24()).isEqualTo("abc123");
    }

    @Test
    void shouldHandleNullStatesGracefully() throws Exception {
        String json = """
                {
                  "time": 1709312400,
                  "states": null
                }
                """;

        JsonNode root = mapper.readTree(json);
        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        assertThat(positions).isEmpty();
    }

    @Test
    void shouldSkipStateVectorsWithNullPosition() throws Exception {
        String json = """
                {
                  "time": 1709312400,
                  "states": [
                    ["abc123", "UAL1234 ", "United States", null, 1709312400,
                     null, null, 10668.0, false, 230.5, 270.0,
                     null, null, null, null, false, 0]
                  ]
                }
                """;

        JsonNode root = mapper.readTree(json);
        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        assertThat(positions).isEmpty();
    }
}
