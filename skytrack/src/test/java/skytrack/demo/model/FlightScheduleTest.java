package skytrack.demo.model;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class FlightScheduleTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCreateFlightScheduleWithAllFields() {
        var schedule = new FlightSchedule(
                "UAL1234", "UA1234", "United Airlines", "ORD", "LAX",
                Instant.parse("2026-03-15T14:00:00Z"), Instant.parse("2026-03-15T16:30:00Z"),
                Instant.parse("2026-03-15T14:05:00Z"), Instant.parse("2026-03-15T16:45:00Z"),
                "B12", "A5", "B738");
        assertThat(schedule.callsign()).isEqualTo("UAL1234");
        assertThat(schedule.origin()).isEqualTo("ORD");
        assertThat(schedule.destination()).isEqualTo("LAX");
    }

    @Test
    void shouldSerializeToJsonAndBack() throws Exception {
        var original = new FlightSchedule(
                "DAL567", "DL567", "Delta Air Lines", "ATL", "JFK",
                Instant.parse("2026-03-15T10:00:00Z"), Instant.parse("2026-03-15T12:30:00Z"),
                null, null, "C14", null, "A321");
        String json = mapper.writeValueAsString(original);
        FlightSchedule deserialized = mapper.readValue(json, FlightSchedule.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void shouldHandleNullOptionalFields() {
        var schedule = new FlightSchedule(
                "AAL100", "AA100", "American Airlines", "DFW", "MIA",
                Instant.parse("2026-03-15T08:00:00Z"), Instant.parse("2026-03-15T11:00:00Z"),
                null, null, null, null, null);
        assertThat(schedule.actualDeparture()).isNull();
        assertThat(schedule.gateOrigin()).isNull();
        assertThat(schedule.aircraftType()).isNull();
    }
}
