package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisruptionScorePropertiesTest {

    @Test
    void shouldBindProperties() {
        var props = new DisruptionScoreProperties(60, 1, 15, 30, 0.85);
        assertThat(props.windowMinutes()).isEqualTo(60);
        assertThat(props.bucketSizeMinutes()).isEqualTo(1);
        assertThat(props.delayThresholdMinutes()).isEqualTo(15);
        assertThat(props.cascadeThresholdMinutes()).isEqualTo(30);
        assertThat(props.cascadePropagationFactor()).isEqualTo(0.85);
    }

    @Test
    void shouldApplyDefaults() {
        var props = new DisruptionScoreProperties(0, 0, 0, 0, 0);
        assertThat(props.windowMinutes()).isEqualTo(60);
        assertThat(props.bucketSizeMinutes()).isEqualTo(1);
        assertThat(props.delayThresholdMinutes()).isEqualTo(15);
        assertThat(props.cascadeThresholdMinutes()).isEqualTo(30);
        assertThat(props.cascadePropagationFactor()).isEqualTo(0.85);
    }
}
