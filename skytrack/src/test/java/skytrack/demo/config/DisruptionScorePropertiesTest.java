package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisruptionScorePropertiesTest {

    @Test
    void shouldBindProperties() {
        var props = new DisruptionScoreProperties(60, 1, 15, 30, 0.85, 0.15, 8);
        assertThat(props.windowMinutes()).isEqualTo(60);
        assertThat(props.bucketSizeMinutes()).isEqualTo(1);
        assertThat(props.delayThresholdMinutes()).isEqualTo(15);
        assertThat(props.cascadeThresholdMinutes()).isEqualTo(30);
        assertThat(props.cascadePropagationFactor()).isEqualTo(0.85);
    }

    @Test
    void shouldApplyDefaults() {
        // Gates tuned 2026-07-30 against the corrected cascade recall denominator: 30/15 -> 5/10.
        // Lowering the cascade gate costs no precision (0.974 -> 0.972) and lifts recall
        // 0.366 -> 0.455, because the gate refused to start walks rather than filtering bad ones.
        var props = new DisruptionScoreProperties(0, 0, 0, 0, 0, 0.0, 0);
        assertThat(props.windowMinutes()).isEqualTo(60);
        assertThat(props.bucketSizeMinutes()).isEqualTo(1);
        assertThat(props.delayThresholdMinutes()).isEqualTo(10);
        assertThat(props.cascadeThresholdMinutes()).isEqualTo(5);
        assertThat(props.cascadePropagationFactor()).isEqualTo(0.85);
    }

    @Test
    void appliesCascadeChainDefaults() {
        var p = new DisruptionScoreProperties(0, 0, 0, 0, 0.0, 0.0, 0);
        assertThat(p.enRouteRecoveryFactor()).isEqualTo(0.15);
        assertThat(p.cascadeMaxHops()).isEqualTo(8);
    }

    @Test
    void clampsRecoveryFactorToValidRange() {
        assertThat(new DisruptionScoreProperties(60, 1, 15, 30, 0.85, -0.5, 8)
                .enRouteRecoveryFactor()).isEqualTo(0.15);
        assertThat(new DisruptionScoreProperties(60, 1, 15, 30, 0.85, 1.5, 8)
                .enRouteRecoveryFactor()).isEqualTo(0.15);
    }
}
