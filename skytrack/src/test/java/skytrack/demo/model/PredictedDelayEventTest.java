package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PredictedDelayEventTest {

    @Test
    void shouldConstructWithAllFields() {
        var now = Instant.now();
        var event = new PredictedDelayEvent(
                "UAL1234", "N12345", "ORD", "UA", "5678",
                1773088200L, 1773090000L, 2700L, 900L,
                DelayClassification.MINOR, 720L, "BTS_REPLAY", now);
        assertThat(event.inboundCallsign()).isEqualTo("UAL1234");
        assertThat(event.tailNumber()).isEqualTo("N12345");
        assertThat(event.predictedDelaySeconds()).isEqualTo(900L);
        assertThat(event.actualDelaySeconds()).isEqualTo(720L);
        assertThat(event.confidence()).isEqualTo("BTS_REPLAY");
        assertThat(event.createdAt()).isEqualTo(now);
    }

    @Test
    void shouldAllowNullActualDelayForUnknown() {
        var event = new PredictedDelayEvent(
                "UAL1234", "N12345", "ORD", "UA", "5678",
                1773088200L, 1773090000L, 2700L, 0L,
                DelayClassification.ON_TIME, null, "BTS_REPLAY", Instant.now());
        assertThat(event.actualDelaySeconds()).isNull();
        assertThat(event.predictedClassification()).isEqualTo(DelayClassification.ON_TIME);
    }
}
