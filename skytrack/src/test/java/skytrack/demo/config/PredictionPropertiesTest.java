package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PredictionPropertiesTest {

    @Test
    void shouldBindProperties() {
        var props = new PredictionProperties(true, "path/to/bts.csv", 45, 15);
        assertThat(props.enabled()).isTrue();
        assertThat(props.btsCsvPath()).isEqualTo("path/to/bts.csv");
        assertThat(props.minTurnaroundMinutes()).isEqualTo(45);
        assertThat(props.delayThresholdMinutes()).isEqualTo(15);
    }

    @Test
    void shouldApplyDefaults() {
        var props = new PredictionProperties(false, null, 0, 0);
        assertThat(props.btsCsvPath()).isEqualTo("data/bts/ontime-2026-03-09.csv");
        assertThat(props.minTurnaroundMinutes()).isEqualTo(45);
        assertThat(props.delayThresholdMinutes()).isEqualTo(15);
    }
}
