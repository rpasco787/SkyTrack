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
}
