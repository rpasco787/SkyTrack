package skytrack.demo.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelayPredictorTest {

    private final DelayPredictor predictor = new DelayPredictor();

    @Test
    void predictsDelayWhenTurnaroundSqueezed() {
        // land 20:30Z (1773088200), sched dep 21:00Z (1773090000), turnaround 45m=2700s
        // earliest ready = 1773088200+2700 = 1773090900 ; sched dep = 1773090000 → 900s late
        long predicted = predictor.predictDelaySeconds(1773088200L, 1773090000L, 2700L);
        assertThat(predicted).isEqualTo(900L);
    }

    @Test
    void predictsZeroWhenSlackTurnaround() {
        assertThat(predictor.predictDelaySeconds(1773088200L, 1773099999L, 2700L)).isZero();
    }

    @Test
    void predictsZeroAtExactBoundary() {
        assertThat(predictor.predictDelaySeconds(1773088200L, 1773088200L + 2700L, 2700L)).isZero();
    }

    // --- additive composition: habitual baseline + turnaround pressure ------------------------

    @Test
    void addsTheBaselinePriorToTheTurnaroundPressure() {
        // 900s of pressure as above, plus a carrier/station that habitually leaves 300s late.
        assertThat(predictor.predictDelaySeconds(1773088200L, 1773090000L, 2700L, 300L))
                .isEqualTo(1200L);
    }

    @Test
    void emitsThePriorAloneWhenSlackAbsorbsTheTurnaround() {
        // This is the case the feasibility-only predictor could never express: plenty of slack,
        // but the flight still habitually departs late.
        assertThat(predictor.predictDelaySeconds(1773088200L, 1773099999L, 2700L, 300L))
                .isEqualTo(300L);
    }

    @Test
    void allowsANegativePredictionWhenThePriorIsNegativeAndThereIsNoPressure() {
        // Most flights push back early, so priors are commonly negative. The target is signed
        // DEP_DELAY, so clamping here would bias every slack rotation upward.
        assertThat(predictor.predictDelaySeconds(1773088200L, 1773099999L, 2700L, -180L))
                .isEqualTo(-180L);
    }

    @Test
    void aNegativePriorPartlyOffsetsTurnaroundPressure() {
        assertThat(predictor.predictDelaySeconds(1773088200L, 1773090000L, 2700L, -180L))
                .isEqualTo(720L);
    }

    @Test
    void pressureItselfNeverGoesNegative() {
        // Slack must not be credited as negative pressure — only the prior may push below zero.
        assertThat(predictor.predictDelaySeconds(1773088200L, 1773099999L, 2700L, 0L)).isZero();
    }
}
