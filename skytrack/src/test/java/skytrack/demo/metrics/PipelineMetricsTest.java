package skytrack.demo.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import skytrack.demo.model.DelayClassification;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineMetricsTest {

    private SimpleMeterRegistry registry;
    private PipelineMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PipelineMetrics(registry);
    }

    @Test
    void positionsConsumedAccumulatesBatchSizes() {
        metrics.positionsConsumed(7);
        metrics.positionsConsumed(3);
        metrics.positionsConsumed(0);

        assertThat(registry.get(PipelineMetrics.POSITIONS_CONSUMED).counter().count()).isEqualTo(10.0);
    }

    @Test
    void landingsDetectedCountsEachCall() {
        metrics.landingDetected();
        metrics.landingDetected();

        assertThat(registry.get(PipelineMetrics.LANDINGS_DETECTED).counter().count()).isEqualTo(2.0);
    }

    @Test
    void predictionsAreCountedPerClassification() {
        metrics.predictionEmitted(DelayClassification.MAJOR);
        metrics.predictionEmitted(DelayClassification.MAJOR);
        metrics.predictionEmitted(DelayClassification.SEVERE);

        assertThat(registry.get(PipelineMetrics.PREDICTIONS)
                .tag("classification", "MAJOR").counter().count()).isEqualTo(2.0);
        assertThat(registry.get(PipelineMetrics.PREDICTIONS)
                .tag("classification", "SEVERE").counter().count()).isEqualTo(1.0);
    }

    @Test
    void everyClassificationSeriesExistsBeforeFirstPrediction() {
        // Pre-registered so Grafana shows a flat zero, not "No data", before the first event.
        for (DelayClassification c : DelayClassification.values()) {
            assertThat(registry.get(PipelineMetrics.PREDICTIONS)
                    .tag("classification", c.name()).counter().count())
                    .as("series for %s", c).isEqualTo(0.0);
        }
        assertThat(registry.get(PipelineMetrics.POSITIONS_CONSUMED).counter().count()).isEqualTo(0.0);
        assertThat(registry.get(PipelineMetrics.LANDINGS_DETECTED).counter().count()).isEqualTo(0.0);
    }

    @Test
    void scheduleResolutionTimerIsTaggedByOutcomeAndPublishesHistogram() {
        var sample = metrics.startTimer();
        metrics.recordScheduleResolution(sample, "resolved");

        var timer = registry.get(PipelineMetrics.SCHEDULE_RESOLUTION).tag("outcome", "resolved").timer();
        assertThat(timer.count()).isEqualTo(1L);
        // publishPercentileHistogram() is what gives Prometheus the _bucket series histogram_quantile
        // needs, but SimpleMeterRegistry's Timer never populates histogramCounts() regardless of
        // configuration (verified: length 0 even when the option is set) — it has no distribution
        // statistics backend. The real check that _bucket series are exported lives in
        // MetricsExposureTest, which scrapes the actual Prometheus registry Spring wires at runtime.

        // The other two outcomes exist at zero from construction.
        assertThat(registry.get(PipelineMetrics.SCHEDULE_RESOLUTION).tag("outcome", "empty").timer().count()).isZero();
        assertThat(registry.get(PipelineMetrics.SCHEDULE_RESOLUTION).tag("outcome", "error").timer().count()).isZero();
    }
}
