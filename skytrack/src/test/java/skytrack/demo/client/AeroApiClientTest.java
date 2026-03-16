package skytrack.demo.client;

import org.junit.jupiter.api.Test;
import skytrack.demo.config.AeroApiProperties;
import skytrack.demo.model.FlightSchedule;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class AeroApiClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldParseAeroApiFlightResponse() throws Exception {
        String json = """
                {
                  "flights": [{
                    "ident": "UAL1234", "ident_iata": "UA1234", "operator": "UAL",
                    "origin": {"code": "KORD", "code_iata": "ORD"},
                    "destination": {"code": "KLAX", "code_iata": "LAX"},
                    "scheduled_out": "2026-03-15T14:00:00Z", "scheduled_in": "2026-03-15T16:30:00Z",
                    "actual_out": "2026-03-15T14:10:00Z", "actual_in": "2026-03-15T16:45:00Z",
                    "gate_origin": "B12", "gate_destination": "A5", "aircraft_type": "B738"
                  }]
                }
                """;
        FlightSchedule result = AeroApiClient.parseFlightFromJson(mapper, json);
        assertThat(result).isNotNull();
        assertThat(result.callsign()).isEqualTo("UAL1234");
        assertThat(result.origin()).isEqualTo("ORD");
        assertThat(result.destination()).isEqualTo("LAX");
        assertThat(result.gateOrigin()).isEqualTo("B12");
    }

    @Test
    void shouldReturnNullWhenNoFlightsInResponse() throws Exception {
        FlightSchedule result = AeroApiClient.parseFlightFromJson(mapper, """
                { "flights": [] }
                """);
        assertThat(result).isNull();
    }

    @Test
    void shouldTrackCallCountForRateLimit() {
        var props = new AeroApiProperties(true, "http://localhost:9090/aeroapi", "key", 100, 5000);
        var client = new AeroApiClient(props, mapper);
        assertThat(client.getRemainingCalls()).isEqualTo(100);
    }
}
