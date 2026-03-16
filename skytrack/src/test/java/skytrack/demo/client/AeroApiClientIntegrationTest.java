package skytrack.demo.client;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import skytrack.demo.config.AeroApiProperties;
import skytrack.demo.model.FlightSchedule;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class AeroApiClientIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AeroApiClient buildClient(WireMockRuntimeInfo wm, int maxCalls) {
        var props = new AeroApiProperties(true, wm.getHttpBaseUrl() + "/aeroapi", "test-key", maxCalls, 5000);
        return new AeroApiClient(props, mapper);
    }

    @Test
    void shouldFetchAndDeserializeFlightWithAllFields(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/aeroapi/flights/UAL1234"))
                .willReturn(okJson("""
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
                        """)));

        Optional<FlightSchedule> result = buildClient(wm, 100).getFlightSchedule("UAL1234", "2026-03-15");

        assertThat(result).isPresent();
        assertThat(result.get().callsign()).isEqualTo("UAL1234");
        assertThat(result.get().origin()).isEqualTo("ORD");
        assertThat(result.get().destination()).isEqualTo("LAX");
        assertThat(result.get().gateOrigin()).isEqualTo("B12");
        assertThat(result.get().aircraftType()).isEqualTo("B738");
    }

    @Test
    void shouldSendApiKeyHeader(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/aeroapi/flights/UAL1234"))
                .willReturn(okJson("{\"flights\": []}")));

        buildClient(wm, 100).getFlightSchedule("UAL1234", "2026-03-15");

        verify(getRequestedFor(urlPathEqualTo("/aeroapi/flights/UAL1234"))
                .withHeader("x-apikey", equalTo("test-key")));
    }

    @Test
    void shouldReturnEmptyWhenFlightNotFound(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/aeroapi/flights/UNKNOWN999"))
                .willReturn(okJson("{\"flights\": []}")));

        Optional<FlightSchedule> result = buildClient(wm, 100).getFlightSchedule("UNKNOWN999", "2026-03-15");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyOnServerError(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/aeroapi/flights/UAL1234"))
                .willReturn(serverError()));

        Optional<FlightSchedule> result = buildClient(wm, 100).getFlightSchedule("UAL1234", "2026-03-15");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldFetchDailyFlights(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/aeroapi/flights"))
                .willReturn(okJson("""
                        {
                          "flights": [
                            {"ident": "UAL1234", "ident_iata": "UA1234", "operator": "UAL",
                             "origin": {"code": "KORD", "code_iata": "ORD"},
                             "destination": {"code": "KLAX", "code_iata": "LAX"},
                             "scheduled_out": "2026-03-15T14:00:00Z", "scheduled_in": "2026-03-15T16:30:00Z",
                             "actual_out": null, "actual_in": null,
                             "gate_origin": null, "gate_destination": null, "aircraft_type": "B738"},
                            {"ident": "DAL567", "ident_iata": "DL567", "operator": "DAL",
                             "origin": {"code": "KATL", "code_iata": "ATL"},
                             "destination": {"code": "KJFK", "code_iata": "JFK"},
                             "scheduled_out": "2026-03-15T10:00:00Z", "scheduled_in": "2026-03-15T13:00:00Z",
                             "actual_out": null, "actual_in": null,
                             "gate_origin": null, "gate_destination": null, "aircraft_type": "A321"}
                          ]
                        }
                        """)));

        List<FlightSchedule> schedules = buildClient(wm, 100).getDailyFlights("2026-03-15");

        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(0).callsign()).isEqualTo("UAL1234");
        assertThat(schedules.get(1).callsign()).isEqualTo("DAL567");
    }

    @Test
    void shouldEnforceRateLimitBudget(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathMatching("/aeroapi/flights/.*"))
                .willReturn(okJson("{\"flights\": []}")));

        var client = buildClient(wm, 2);
        client.getFlightSchedule("UAL1234", "2026-03-15");
        client.getFlightSchedule("DAL567", "2026-03-15");

        // Budget exhausted — third call must not reach WireMock
        Optional<FlightSchedule> blocked = client.getFlightSchedule("AAL100", "2026-03-15");

        assertThat(blocked).isEmpty();
        verify(exactly(2), getRequestedFor(urlPathMatching("/aeroapi/flights/.*")));
    }

    @Test
    void shouldReturnEmptyImmediatelyWhenDisabled(WireMockRuntimeInfo wm) {
        var props = new AeroApiProperties(false, wm.getHttpBaseUrl() + "/aeroapi", "test-key", 100, 5000);
        var client = new AeroApiClient(props, mapper);

        Optional<FlightSchedule> result = client.getFlightSchedule("UAL1234", "2026-03-15");
        List<FlightSchedule> daily = client.getDailyFlights("2026-03-15");

        assertThat(result).isEmpty();
        assertThat(daily).isEmpty();
        verify(exactly(0), getRequestedFor(anyUrl()));
    }
}
