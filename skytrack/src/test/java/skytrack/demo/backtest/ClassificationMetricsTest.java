package skytrack.demo.backtest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ClassificationMetricsTest {

    @Test
    void computesPrecisionRecallF1AtThreshold() {
        // threshold 900s. (predicted, actual):
        //  (1800,1200) TP  (1800,300) FP  (300,1200) FN  (300,120) TN
        var m = ClassificationMetrics.at(900, List.of(
                new ErrorMetrics.Pair(1800, 1200),
                new ErrorMetrics.Pair(1800, 300),
                new ErrorMetrics.Pair(300, 1200),
                new ErrorMetrics.Pair(300, 120)));
        assertThat(m.truePositives()).isEqualTo(1);
        assertThat(m.falsePositives()).isEqualTo(1);
        assertThat(m.falseNegatives()).isEqualTo(1);
        assertThat(m.precision()).isCloseTo(0.5, within(0.001));
        assertThat(m.recall()).isCloseTo(0.5, within(0.001));
        assertThat(m.f1()).isCloseTo(0.5, within(0.001));
    }
}
