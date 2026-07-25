package skytrack.demo.backtest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ErrorMetricsTest {

    @Test
    void computesMaeRmseAndSignedBias() {
        // errors: +600, -300, +900  (predicted minus actual)
        var m = ErrorMetrics.of(List.of(
                new ErrorMetrics.Pair(1800, 1200),
                new ErrorMetrics.Pair(600, 900),
                new ErrorMetrics.Pair(1800, 900)));
        assertThat(m.count()).isEqualTo(3);
        assertThat(m.maeSeconds()).isCloseTo(600.0, within(0.01));
        assertThat(m.biasSeconds()).isCloseTo(400.0, within(0.01));   // over-predicting
        assertThat(m.rmseSeconds()).isCloseTo(648.07, within(0.01));
    }

    @Test
    void emptySampleIsAllZeroNotNaN() {
        assertThat(ErrorMetrics.of(List.of()).maeSeconds()).isZero();
    }
}
