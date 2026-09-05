package skytrack.demo.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import skytrack.demo.model.DelayClassification;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The single owner of SkyTrack's custom metric names. Pipeline classes call the one-line methods
 * here rather than touching {@link MeterRegistry} themselves, so the names, tags and histogram
 * settings live in one place and tests can pass {@code new PipelineMetrics(new SimpleMeterRegistry())}.
 *
 * <p>Every series is registered eagerly in the constructor. Micrometer would otherwise create a
 * counter on first increment, and a series that does not exist until the first landing renders as
 * "No data" in Grafana for the opening minutes of every run.</p>
 *
 * <p>Prometheus names (Micrometer appends the unit/suffix): {@code skytrack_positions_consumed_total},
 * {@code skytrack_landings_detected_total}, {@code skytrack_predictions_total{classification=..}},
 * {@code skytrack_schedule_resolution_seconds_{count,sum,max,bucket}{outcome=..}}.</p>
 */
@Component
public class PipelineMetrics {

    public static final String POSITIONS_CONSUMED = "skytrack.positions.consumed";
    public static final String LANDINGS_DETECTED = "skytrack.landings.detected";
    public static final String PREDICTIONS = "skytrack.predictions";
    public static final String SCHEDULE_RESOLUTION = "skytrack.schedule.resolution";

    /** Fixed tag values; never tag with anything user-derived (callsign, airport) — cardinality. */
    public static final String OUTCOME_RESOLVED = "resolved";
    public static final String OUTCOME_EMPTY = "empty";
    public static final String OUTCOME_ERROR = "error";

    private final MeterRegistry registry;
    private final Counter positionsConsumed;
    private final Counter landingsDetected;
    private final Map<DelayClassification, Counter> predictions = new EnumMap<>(DelayClassification.class);
    private final Map<String, Timer> resolutionTimers;

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.positionsConsumed = Counter.builder(POSITIONS_CONSUMED)
                .description("FlightPosition messages received from the positions queue")
                .register(registry);
        this.landingsDetected = Counter.builder(LANDINGS_DETECTED)
                .description("LandingEvents emitted by the aircraft state machine")
                .register(registry);
        for (DelayClassification c : DelayClassification.values()) {
            predictions.put(c, Counter.builder(PREDICTIONS)
                    .description("PredictedDelayEvents emitted, by predicted classification")
                    .tag("classification", c.name())
                    .register(registry));
        }
        this.resolutionTimers = Map.of(
                OUTCOME_RESOLVED, resolutionTimer(OUTCOME_RESOLVED),
                OUTCOME_EMPTY, resolutionTimer(OUTCOME_EMPTY),
                OUTCOME_ERROR, resolutionTimer(OUTCOME_ERROR));
    }

    private Timer resolutionTimer(String outcome) {
        return Timer.builder(SCHEDULE_RESOLUTION)
                .description("Latency of the schedule API lookup performed for each landing")
                .tag("outcome", outcome)
                // Emits _bucket series so Prometheus can compute p50/p99 with histogram_quantile.
                // The bounds cap the bucket count; the AeroAPI client times out at 5s.
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);
    }

    public void positionsConsumed(int count) {
        if (count > 0) positionsConsumed.increment(count);
    }

    public void landingDetected() {
        landingsDetected.increment();
    }

    public void predictionEmitted(DelayClassification classification) {
        predictions.get(classification).increment();
    }

    /** Pair with {@link #recordScheduleResolution}. Uses the registry clock so tests can control time if needed. */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /** @param outcome one of {@link #OUTCOME_RESOLVED}, {@link #OUTCOME_EMPTY}, {@link #OUTCOME_ERROR}. */
    public void recordScheduleResolution(Timer.Sample sample, String outcome) {
        Timer timer = resolutionTimers.get(outcome);
        if (timer == null) {
            throw new IllegalArgumentException("Unknown resolution outcome '" + outcome
                    + "'; expected one of " + List.copyOf(resolutionTimers.keySet()));
        }
        sample.stop(timer);
    }
}
