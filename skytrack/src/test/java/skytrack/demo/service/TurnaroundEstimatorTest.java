package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.config.PredictionProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TurnaroundEstimatorTest {

    private static final PredictionProperties PROPS = new PredictionProperties(true, "x", 45, 15, 360);

    private static final Map<String, Long> TURNAROUNDS = Map.of(
            "UA|ORD", 1800L,
            "UA", 2400L);

    @Test
    void returnsConfiguredDefaultTurnaroundSeconds() {
        assertThat(new TurnaroundEstimator(PROPS, Map.of()).minTurnaroundSeconds(null)).isEqualTo(45 * 60L);
    }

    @Test
    void prefersTheCarrierAtAirportFigureOverTheCarrierWideOne() {
        // A carrier turns aircraft faster at its own hub than it does system-wide.
        assertThat(new TurnaroundEstimator(PROPS, TURNAROUNDS).minTurnaroundSeconds("UA", "ORD"))
                .isEqualTo(1800L);
    }

    @Test
    void backsOffToTheCarrierWideFigureAtAnUnfittedAirport() {
        assertThat(new TurnaroundEstimator(PROPS, TURNAROUNDS).minTurnaroundSeconds("UA", "SFO"))
                .isEqualTo(2400L);
    }

    @Test
    void backsOffToTheCarrierWideFigureWhenTheAirportIsUnknown() {
        assertThat(new TurnaroundEstimator(PROPS, TURNAROUNDS).minTurnaroundSeconds("UA", null))
                .isEqualTo(2400L);
    }

    @Test
    void fallsBackToTheConfiguredDefaultForAnUnfittedCarrier() {
        assertThat(new TurnaroundEstimator(PROPS, TURNAROUNDS).minTurnaroundSeconds("ZZ", "ORD"))
                .isEqualTo(45 * 60L);
    }

    // --- expected (p50) turnaround, kept separate from the p15 floor -------------------------

    private static final Map<String, Long> FLOORS = Map.of("UA|ORD", 1800L, "UA", 2100L);
    private static final Map<String, Long> EXPECTED = Map.of("UA|ORD", 3000L, "UA", 3300L);

    @Test
    void expectedTurnaroundIsIndependentOfTheFloor() {
        // Slack uses the floor, prediction uses the expected figure; one estimator serves both
        // without either reading the other's table.
        var estimator = new TurnaroundEstimator(PROPS, FLOORS, EXPECTED);

        assertThat(estimator.minTurnaroundSeconds("UA", "ORD")).isEqualTo(1800L);
        assertThat(estimator.expectedTurnaroundSeconds("UA", "ORD")).isEqualTo(3000L);
    }

    @Test
    void expectedTurnaroundBacksOffToTheCarrierWideFigure() {
        assertThat(new TurnaroundEstimator(PROPS, FLOORS, EXPECTED)
                .expectedTurnaroundSeconds("UA", "SFO")).isEqualTo(3300L);
    }

    @Test
    void expectedTurnaroundFallsBackToTheConfiguredDefaultForAnUnfittedCarrier() {
        assertThat(new TurnaroundEstimator(PROPS, FLOORS, EXPECTED)
                .expectedTurnaroundSeconds("ZZ", "ORD")).isEqualTo(45 * 60L);
    }

    @Test
    void singleMapConstructorServesBothLookupsFromThatMap() {
        // Degenerate wiring for callers with only one fitted table.
        var estimator = new TurnaroundEstimator(PROPS, TURNAROUNDS);

        assertThat(estimator.minTurnaroundSeconds("UA", "ORD")).isEqualTo(1800L);
        assertThat(estimator.expectedTurnaroundSeconds("UA", "ORD")).isEqualTo(1800L);
    }

    @Test
    void carrierOnlyLookupIgnoresAirportKeyedEntries() {
        // The single-argument form must not accidentally match a "UA|ORD" style key.
        assertThat(new TurnaroundEstimator(PROPS, TURNAROUNDS).minTurnaroundSeconds("UA"))
                .isEqualTo(2400L);
    }
}
