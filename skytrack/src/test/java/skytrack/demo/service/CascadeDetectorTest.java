package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.model.CascadeAlert;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CascadeDetectorTest {

    private CascadeDetector detector;

    @BeforeEach
    void setUp() {
        var props = new DisruptionScoreProperties(60, 1, 15, 30, 0.85, 0.15, 8);
        detector = new CascadeDetector(props);
    }

    private DelayEvent delayEvent(long delaySeconds) {
        return new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L,
                1709312400L - delaySeconds, delaySeconds,
                DelayClassification.fromDelaySeconds(delaySeconds),
                "AEROAPI", Instant.now(),
                null, null, null, null);
    }

    @Test
    void shouldEmitCascadeAlertForMajorDelay() {
        // 60 min delay → predicted downstream = 51 min (3600 * 0.85 = 3060s)
        Optional<CascadeAlert> alert = detector.checkCascade(delayEvent(3600));

        assertThat(alert).isPresent();
        assertThat(alert.get().currentDelaySeconds()).isEqualTo(3600);
        assertThat(alert.get().predictedDownstreamDelaySeconds()).isEqualTo(3060);
        assertThat(alert.get().propagationFactor()).isEqualTo(0.85);
    }

    @Test
    void shouldNotEmitForDelayBelowCascadeThreshold() {
        // 20 min delay (below 30 min cascade threshold)
        Optional<CascadeAlert> alert = detector.checkCascade(delayEvent(1200));
        assertThat(alert).isEmpty();
    }

    @Test
    void shouldNotEmitForNullDelay() {
        var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, null, null,
                DelayClassification.UNKNOWN, "UNRESOLVED", Instant.now(),
                null, null, null, null);
        assertThat(detector.checkCascade(event)).isEmpty();
    }

    @Test
    void shouldEmitAtExactCascadeThreshold() {
        // 30 min delay → predicted = 25.5 min (1800 * 0.85 = 1530s = 25.5 min > 15 min)
        Optional<CascadeAlert> alert = detector.checkCascade(delayEvent(1800));
        assertThat(alert).isPresent();
        assertThat(alert.get().predictedDownstreamDelaySeconds()).isEqualTo(1530);
    }

    @Test
    void shouldNotEmitJustBelowCascadeThreshold() {
        // 29 min = 1740s (below 30 min)
        assertThat(detector.checkCascade(delayEvent(1740))).isEmpty();
    }

    @Test
    void shouldIncludeSourceFlightDetails() {
        var alert = detector.checkCascade(delayEvent(3600)).orElseThrow();
        assertThat(alert.sourceCallsign()).isEqualTo("UAL1234");
        assertThat(alert.arrivalAirportIata()).isEqualTo("ORD");
        assertThat(alert.createdAt()).isNotNull();
    }
}
