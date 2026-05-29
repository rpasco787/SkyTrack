package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.model.WeatherObservation;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DelayComputerTest {

    private final DelayComputer computer = new DelayComputer();

    @Test
    void shouldComputeModerateDelay() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709310600L, 1800L, "AEROAPI");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.MODERATE);
        assertThat(event.delaySeconds()).isEqualTo(1800L);
        assertThat(event.resolutionMethod()).isEqualTo("AEROAPI");
    }

    @Test
    void shouldComputeOnTimeArrival() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709312400L, 0L, "AEROAPI");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.ON_TIME);
    }

    @Test
    void shouldComputeEarlyArrival() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709313000L, -600L, "AEROAPI");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.ON_TIME);
    }

    @Test
    void shouldHandleUnresolvedArrival() {
        var arrival = new ResolvedArrival("abc123", "ZZZ999", null, null,
                "KORD", "ORD", 1709312400L, null, null, "UNRESOLVED");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.UNKNOWN);
        assertThat(event.delaySeconds()).isNull();
    }

    @Test
    void shouldPreserveAllFieldsFromResolvedArrival() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L, "AEROAPI");

        var event = computer.compute(arrival);

        assertThat(event.icao24()).isEqualTo("abc123");
        assertThat(event.callsign()).isEqualTo("UAL1234");
        assertThat(event.carrierCode()).isEqualTo("UA");
        assertThat(event.flightNumber()).isEqualTo("1234");
        assertThat(event.arrivalAirportIcao()).isEqualTo("KORD");
        assertThat(event.arrivalAirportIata()).isEqualTo("ORD");
        assertThat(event.actualArrivalTime()).isEqualTo(1709312400L);
        assertThat(event.scheduledArrivalTime()).isEqualTo(1709311500L);
        assertThat(event.createdAt()).isNotNull();
    }

    @Test
    void shouldComputeSevereDelay() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709305200L, 7200L, "AEROAPI");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.MAJOR);
    }

    @Test
    void shouldComputeRouteAverageDelay() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, null, 1800L, "ROUTE_AVERAGE");

        var event = computer.compute(arrival);

        assertThat(event.classification()).isEqualTo(DelayClassification.MODERATE);
        assertThat(event.resolutionMethod()).isEqualTo("ROUTE_AVERAGE");
        assertThat(event.scheduledArrivalTime()).isNull();
    }

    @Test
    void shouldEnrichWithWeatherWhenProvided() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L, "API_CACHE");
        var weather = new WeatherObservation("KORD", "ORD",
                Instant.parse("2026-05-05T15:00:00Z"),
                2.0, 800, 18, 25, FlightCategory.IFR, "raw");

        var event = computer.compute(arrival, Optional.of(weather));

        assertThat(event.flightCategory()).isEqualTo(FlightCategory.IFR);
        assertThat(event.visibilityStatuteMiles()).isEqualTo(2.0);
        assertThat(event.ceilingFeet()).isEqualTo(800);
        assertThat(event.windSpeedKnots()).isEqualTo(18);
    }

    @Test
    void shouldLeaveWeatherFieldsNullWhenAbsent() {
        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L, "API_CACHE");

        var event = computer.compute(arrival, Optional.empty());

        assertThat(event.flightCategory()).isNull();
        assertThat(event.visibilityStatuteMiles()).isNull();
        assertThat(event.ceilingFeet()).isNull();
        assertThat(event.windSpeedKnots()).isNull();
    }
}
