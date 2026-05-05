package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DelayEventTest {

    @Test
    void shouldConstructDelayEvent() {
        var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L,
                DelayClassification.MODERATE, "AEROAPI", Instant.now());
        assertThat(event.icao24()).isEqualTo("abc123");
        assertThat(event.delaySeconds()).isEqualTo(900L);
        assertThat(event.classification()).isEqualTo(DelayClassification.MODERATE);
        assertThat(event.resolutionMethod()).isEqualTo("AEROAPI");
    }

    @Test
    void shouldSupportNullDelayForUnresolved() {
        var event = new DelayEvent("abc123", "ZZZ999", null, null,
                "KORD", "ORD", 1709312400L, null, null,
                DelayClassification.UNKNOWN, "UNRESOLVED", Instant.now());
        assertThat(event.delaySeconds()).isNull();
        assertThat(event.scheduledArrivalTime()).isNull();
        assertThat(event.classification()).isEqualTo(DelayClassification.UNKNOWN);
    }
}
