package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PredictionPropertiesTest {

    @Test
    void shouldBindProperties() {
        var props = new PredictionProperties(true, "path/to/bts.csv", 45, 15, 360);
        assertThat(props.enabled()).isTrue();
        assertThat(props.btsCsvPath()).isEqualTo("path/to/bts.csv");
        assertThat(props.minTurnaroundMinutes()).isEqualTo(45);
        assertThat(props.delayThresholdMinutes()).isEqualTo(15);
    }

    @Test
    void shouldApplyDefaults() {
        var props = new PredictionProperties(false, null, 0, 0, 360);
        assertThat(props.btsCsvPath()).isEqualTo("data/bts/ontime-2026-03-09.csv");
        assertThat(props.minTurnaroundMinutes()).isEqualTo(45);
        assertThat(props.delayThresholdMinutes()).isEqualTo(0);
    }

    @Test
    void maxRotationLookaheadDefaultsToSixHours() {
        assertThat(new PredictionProperties(false, null, 0, 0, 0).maxRotationLookaheadMinutes())
                .isEqualTo(360);
    }

    @Test
    void zeroDelayThresholdIsHonouredForBacktesting() {
        var props = new PredictionProperties(true, "x.csv", 45, 0, 360);
        assertThat(props.delayThresholdMinutes()).isZero();
    }

    @Test
    void negativeDelayThresholdStillDefaultsTo15() {
        assertThat(new PredictionProperties(true, "x.csv", 45, -1, 360).delayThresholdMinutes())
                .isEqualTo(15);
    }
}
