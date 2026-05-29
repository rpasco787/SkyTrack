package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DelayEventTest {

    @Test
    void shouldConstructDelayEvent() {
        var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L,
                DelayClassification.MODERATE, "AEROAPI", Instant.now(),
                null, null, null, null);
        assertThat(event.icao24()).isEqualTo("abc123");
        assertThat(event.delaySeconds()).isEqualTo(900L);
        assertThat(event.classification()).isEqualTo(DelayClassification.MODERATE);
        assertThat(event.resolutionMethod()).isEqualTo("AEROAPI");
    }

    @Test
    void shouldSupportNullDelayForUnresolved() {
        var event = new DelayEvent("abc123", "ZZZ999", null, null,
                "KORD", "ORD", 1709312400L, null, null,
                DelayClassification.UNKNOWN, "UNRESOLVED", Instant.now(),
                null, null, null, null);
        assertThat(event.delaySeconds()).isNull();
        assertThat(event.scheduledArrivalTime()).isNull();
        assertThat(event.classification()).isEqualTo(DelayClassification.UNKNOWN);
    }

    @Test
    void shouldCarryWeatherFields() {
        var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L,
                DelayClassification.MODERATE, "API_CACHE", Instant.now(),
                FlightCategory.IFR, 2.0, 800, 18);
        assertThat(event.flightCategory()).isEqualTo(FlightCategory.IFR);
        assertThat(event.visibilityStatuteMiles()).isEqualTo(2.0);
        assertThat(event.ceilingFeet()).isEqualTo(800);
        assertThat(event.windSpeedKnots()).isEqualTo(18);
    }

    @Test
    void shouldAllowNullWeatherFields() {
        var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L,
                DelayClassification.MODERATE, "API_CACHE", Instant.now(),
                null, null, null, null);
        assertThat(event.flightCategory()).isNull();
    }
}
