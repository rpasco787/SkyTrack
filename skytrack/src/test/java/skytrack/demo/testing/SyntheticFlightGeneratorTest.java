package skytrack.demo.testing;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.FlightPosition;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SyntheticFlightGeneratorTest {
    @Test
    void shouldGenerateCascadeWithCorrectLegCount() {
        List<FlightPosition> positions = SyntheticFlightGenerator.generateCascadeScenario(3, 45, 0.85);
        long distinctCallsigns = positions.stream().map(FlightPosition::callsign).distinct().count();
        assertThat(distinctCallsigns).isEqualTo(3);
        // Sorted by time
        for (int i = 1; i < positions.size(); i++) {
            assertThat(positions.get(i).lastContact())
                    .isGreaterThanOrEqualTo(positions.get(i - 1).lastContact());
        }
    }

    @Test
    void shouldGenerateHoldingPattern() {
        List<FlightPosition> positions = SyntheticFlightGenerator.generateHoldingPattern("UAL999", "ORD");
        assertThat(positions).isNotEmpty().allMatch(fp -> "UAL999".equals(fp.callsign()));
        long distinctHeadings = positions.stream().filter(fp -> !fp.onGround())
                .map(FlightPosition::heading).distinct().count();
        assertThat(distinctHeadings).isGreaterThan(2);
    }

    @Test
    void shouldGenerateBurstArrivals() {
        List<FlightPosition> positions = SyntheticFlightGenerator.generateBurstArrivals("ORD", 5, 30);
        long distinctCallsigns = positions.stream().map(FlightPosition::callsign).distinct().count();
        assertThat(distinctCallsigns).isEqualTo(5);
    }

    @Test
    void shouldProduceValidCoordinates() {
        List<FlightPosition> positions = SyntheticFlightGenerator.generateHoldingPattern("DAL100", "ATL");
        for (FlightPosition fp : positions) {
            assertThat(fp.latitude()).isBetween(-90.0, 90.0);
            assertThat(fp.longitude()).isBetween(-180.0, 180.0);
        }
    }
}
