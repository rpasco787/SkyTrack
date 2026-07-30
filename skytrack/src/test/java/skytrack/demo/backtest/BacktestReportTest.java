package skytrack.demo.backtest;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.CascadeAccuracySummary;
import skytrack.demo.model.PredictionAccuracySummary;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestReportTest {

    @Test
    void rendersEveryArmIncludingThePriorOnlyControl() {
        // The prior arm is the honesty check: if the baseline term alone matches the full model,
        // the turnaround physics is not earning its complexity and the report must show that.
        String md = render();

        assertThat(md).contains("| model |").contains("| zero |").contains("| flat |")
                .contains("| prior |");
    }

    @Test
    void reportsMedianAndTailErrorAlongsideTheMeans() {
        String md = render();

        assertThat(md).contains("p50 |").contains("p90 |");
    }

    @Test
    void reportsTrainAndEvalDelayDistributionsSideBySide() {
        String md = render();

        assertThat(md).contains("Distribution Sanity Check");
        assertThat(md).contains("train").contains("eval");
    }

    @Test
    void reportsHopErrorAgainstLateAircraftDelayAndTotalDelaySeparately() {
        // A turnaround model can only explain the late-aircraft component; total departure delay
        // also carries carrier/NAS/weather causes. Both are shown so the primary target is
        // unambiguous and the gap between them stays visible.
        String md = render();

        assertThat(md).contains("late-aircraft").contains("total dep delay");
    }

    @Test
    void omitsAnArmThatWasNotScored() {
        // Not every caller runs every arm; a missing arm must not blow up the renderer.
        String md = BacktestReport.render(
                Instant.EPOCH, funnel(),
                Map.of("model", metrics()), Map.of("model", classification()),
                confusion(), cascade(), metrics(), metrics(), 0.5, 0.5, List.of(),
                List.of());

        assertThat(md).contains("| model |").doesNotContain("| prior |");
    }

    private static String render() {
        return BacktestReport.render(
                Instant.EPOCH, funnel(),
                Map.of("model", metrics(), "zero", metrics(), "flat", metrics(), "prior", metrics()),
                Map.of("model", classification(), "zero", classification(),
                        "flat", classification(), "prior", classification()),
                confusion(), cascade(), metrics(), metrics(), 0.5, 0.5, List.of(),
                List.of(new DelayDistribution("train", 100, -120L, 900.0, 3000L),
                        new DelayDistribution("eval", 40, -180L, 640.0, 2200L)));
    }

    private static CoverageFunnel funnel() {
        return new CoverageFunnel(10, 8, 7, 6, 6);
    }

    private static ErrorMetrics metrics() {
        return new ErrorMetrics(6, 500.0, 900.0, -100.0, 300L, 1200L);
    }

    private static ClassificationMetrics classification() {
        return ClassificationMetrics.at(900L, List.of(new ErrorMetrics.Pair(1000L, 1000L)));
    }

    private static PredictionAccuracySummary confusion() {
        return new PredictionAccuracySummary("ALL", 6, 6, 500.0, Map.of());
    }

    private static CascadeAccuracySummary cascade() {
        return new CascadeAccuracySummary("ALL", 2, 4, 4, 1500.0, 1.5, 3, 1, 0.75);
    }
}
