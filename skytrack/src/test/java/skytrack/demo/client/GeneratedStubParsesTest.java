package skytrack.demo.client;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.FlightSchedule;
import skytrack.demo.tooling.SeedRow;
import skytrack.demo.tooling.StubJson;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedStubParsesTest {

    @Test
    void generatedBodyRoundTripsThroughAeroApiParser() throws Exception {
        // arrival at epoch 1_773_078_820, 30-minute delay -> scheduled_in 1800s earlier
        SeedRow row = new SeedRow("AAL103", "AA103", "AAL",
                1_773_078_820L, "KJFK", "JFK");
        long scheduledInEpoch = row.arrivalEpoch() - 1800;

        String body = StubJson.flightsBody(new ObjectMapper(), row, scheduledInEpoch);
        FlightSchedule fs = AeroApiClient.parseFlightFromJson(new ObjectMapper(), body);

        assertThat(fs.callsign()).isEqualTo("AAL103");
        assertThat(fs.destination()).isEqualTo("JFK");
        assertThat(fs.scheduledArrival()).isEqualTo(Instant.ofEpochSecond(scheduledInEpoch));
    }
}
