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
        assertThat(ErrorMetrics.of(List.of()).p50AbsErrorSeconds()).isZero();
        assertThat(ErrorMetrics.of(List.of()).p90AbsErrorSeconds()).isZero();
    }

    @Test
    void reportsMedianAndTailOfAbsoluteError() {
        // Absolute errors 0,100,...,900 over 10 samples. p50 index = 5 -> 500s,
        // p90 index = 9 -> 900s.
        var pairs = new java.util.ArrayList<ErrorMetrics.Pair>();
        for (int i = 0; i < 10; i++) pairs.add(new ErrorMetrics.Pair(100L * i, 0L));

        var m = ErrorMetrics.of(pairs);

        assertThat(m.p50AbsErrorSeconds()).isEqualTo(500L);
        assertThat(m.p90AbsErrorSeconds()).isEqualTo(900L);
    }

    @Test
    void percentilesUseAbsoluteErrorSoSignDoesNotCancel() {
        // Signed errors -800 and +800: bias is 0, but both samples are 800s wrong.
        var m = ErrorMetrics.of(List.of(
                new ErrorMetrics.Pair(0, 800),
                new ErrorMetrics.Pair(800, 0)));

        assertThat(m.biasSeconds()).isCloseTo(0.0, within(0.01));
        assertThat(m.p50AbsErrorSeconds()).isEqualTo(800L);
    }

    @Test
    void tailPercentileExposesWhatTheMeanHides() {
        // 19 near-perfect predictions and one catastrophe. MAE is dragged to 500s by the
        // outlier while the median error stays at 0 — the gap is the whole point of p50/p90.
        var pairs = new java.util.ArrayList<ErrorMetrics.Pair>();
        for (int i = 0; i < 19; i++) pairs.add(new ErrorMetrics.Pair(0L, 0L));
        pairs.add(new ErrorMetrics.Pair(10_000L, 0L));

        var m = ErrorMetrics.of(pairs);

        assertThat(m.maeSeconds()).isCloseTo(500.0, within(0.01));
        assertThat(m.p50AbsErrorSeconds()).isZero();
        assertThat(m.p90AbsErrorSeconds()).isZero();
    }
}
