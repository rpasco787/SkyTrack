package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.PredictedDelayEvent;
import skytrack.demo.model.PredictionAccuracySummary;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PredictionAccuracyServiceTest {

    private final PredictionAccuracyService service = new PredictionAccuracyService();

    private static PredictedDelayEvent event(long predicted, Long actual) {
        return new PredictedDelayEvent(
                "UAL1234", "N12345", "ORD", "UA", "5678",
                1773088200L, 1773090000L, 2700L,
                predicted,
                DelayClassification.fromDelaySeconds(predicted),
                actual, "BTS_REPLAY", Instant.now());
    }

    @Test
    void computesMeanAbsoluteError() {
        // predicted=900s, actual=600s → error=300s
        // predicted=1200s, actual=1800s → error=600s
        // MAE = (300 + 600) / 2 = 450s
        var events = List.of(event(900L, 600L), event(1200L, 1800L));
        PredictionAccuracySummary summary = service.summarize("ORD", events);

        assertThat(summary.backtestableCount()).isEqualTo(2);
        assertThat(summary.meanAbsoluteErrorSeconds()).isCloseTo(450.0, within(0.01));
    }

    @Test
    void excludesEventsWithNullActualDelay() {
        var events = List.of(event(900L, 600L), event(1200L, null));
        PredictionAccuracySummary summary = service.summarize("ORD", events);

        assertThat(summary.totalPredictions()).isEqualTo(2);
        assertThat(summary.backtestableCount()).isEqualTo(1);
        assertThat(summary.meanAbsoluteErrorSeconds()).isCloseTo(300.0, within(0.01));
    }

    @Test
    void returnsZeroMaeForEmptyBacktestSet() {
        var events = List.of(event(900L, null));
        PredictionAccuracySummary summary = service.summarize("ORD", events);

        assertThat(summary.backtestableCount()).isZero();
        assertThat(summary.meanAbsoluteErrorSeconds()).isZero();
    }

    @Test
    void buildsConfusionMatrixCounts() {
        // predicted=960s (16min → MODERATE), actual=600s (10min → MINOR) → MODERATE→MINOR
        // predicted=3000s (50min → MAJOR), actual=5400s (90min → MAJOR) → MAJOR→MAJOR
        var events = List.of(event(960L, 600L), event(3000L, 5400L));
        PredictionAccuracySummary summary = service.summarize("ORD", events);

        assertThat(summary.confusionMatrix())
                .containsKey("MODERATE");
        assertThat(summary.confusionMatrix().get("MODERATE"))
                .containsEntry("MINOR", 1);
        assertThat(summary.confusionMatrix().get("MAJOR"))
                .containsEntry("MAJOR", 1);
    }

    @Test
    void returnsAirportIata() {
        PredictionAccuracySummary summary = service.summarize("ATL", List.of());
        assertThat(summary.airportIata()).isEqualTo("ATL");
        assertThat(summary.totalPredictions()).isZero();
    }
}
