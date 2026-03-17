package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.FlightSchedule;

import java.time.Instant;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RouteAverageEstimatorTest {

    private final RouteAverageEstimator estimator = new RouteAverageEstimator();

    @Test
    void shouldReturnEmptyWhenNoDataRecorded() {
        OptionalDouble result = estimator.estimateDelaySeconds("UAL", "LAX");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWithInsufficientObservations() {
        // Record only 2 observations (below minimum of 3)
        recordSchedule("UAL", "LAX", 600);
        recordSchedule("UAL", "LAX", 900);

        assertThat(estimator.estimateDelaySeconds("UAL", "LAX")).isEmpty();
    }

    @Test
    void shouldReturnAverageWithSufficientObservations() {
        recordSchedule("UAL", "LAX", 600);
        recordSchedule("UAL", "LAX", 900);
        recordSchedule("UAL", "LAX", 300);

        OptionalDouble result = estimator.estimateDelaySeconds("UAL", "LAX");
        assertThat(result).isPresent();
        assertThat(result.getAsDouble()).isCloseTo(600.0, within(0.01));
    }

    @Test
    void shouldKeySeparatelyByCarrierAndAirport() {
        recordSchedule("UAL", "LAX", 600);
        recordSchedule("UAL", "LAX", 600);
        recordSchedule("UAL", "LAX", 600);

        recordSchedule("DAL", "LAX", 1200);
        recordSchedule("DAL", "LAX", 1200);
        recordSchedule("DAL", "LAX", 1200);

        assertThat(estimator.estimateDelaySeconds("UAL", "LAX").getAsDouble())
                .isCloseTo(600.0, within(0.01));
        assertThat(estimator.estimateDelaySeconds("DAL", "LAX").getAsDouble())
                .isCloseTo(1200.0, within(0.01));
    }

    @Test
    void shouldSkipSchedulesWithMissingTimestamps() {
        var schedule = new FlightSchedule("UAL1234", "UA1234", "UAL", "ORD", "LAX",
                null, null, null, null, null, null, "B738");
        estimator.record(schedule);

        assertThat(estimator.estimateDelaySeconds("UAL", "LAX")).isEmpty();
    }

    private void recordSchedule(String airline, String destination, long delaySeconds) {
        Instant scheduledArrival = Instant.parse("2026-03-15T16:00:00Z");
        Instant actualArrival = scheduledArrival.plusSeconds(delaySeconds);
        var schedule = new FlightSchedule("call", "fn", airline, "ORD", destination,
                Instant.parse("2026-03-15T14:00:00Z"), scheduledArrival,
                null, actualArrival, null, null, "B738");
        estimator.record(schedule);
    }
}
