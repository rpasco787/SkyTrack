package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.config.PredictionProperties;

import static org.assertj.core.api.Assertions.assertThat;

class TurnaroundEstimatorTest {

    @Test
    void returnsConfiguredDefaultTurnaroundSeconds() {
        var props = new PredictionProperties(true, "x", 45, 15);
        assertThat(new TurnaroundEstimator(props).minTurnaroundSeconds(null)).isEqualTo(45 * 60L);
    }
}
