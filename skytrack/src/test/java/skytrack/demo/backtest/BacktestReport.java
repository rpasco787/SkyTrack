package skytrack.demo.backtest;

import skytrack.demo.model.CascadeAccuracySummary;
import skytrack.demo.model.PredictionAccuracySummary;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Markdown rendering for the {@link AccuracyBacktestIT} report. */
public final class BacktestReport {

    private BacktestReport() {}

    public record WorstCase(String callsign, String route, long scheduledDepEpoch,
                            long predictedSeconds, long actualSeconds) {
        long absError() { return Math.abs(predictedSeconds - actualSeconds); }
    }

    public static String render(
            Instant generatedAt,
            CoverageFunnel funnel,
            Map<String, ErrorMetrics> predictionErrorByArm,
            Map<String, ClassificationMetrics> predictionClassByArm,
            PredictionAccuracySummary predictionConfusion,
            CascadeAccuracySummary cascadeSummary,
            ErrorMetrics cascadeErrorMetrics,
            ErrorMetrics cascadeTotalDelayErrorMetrics,
            double cascadeRecall,
            double cascadeF1,
            List<WorstCase> worstCases,
            List<DelayDistribution> delayDistributions) {

        StringBuilder sb = new StringBuilder();
        sb.append("# Accuracy Backtest Results\n\n");
        sb.append("Generated: ").append(generatedAt).append("\n\n");

        sb.append("## Coverage Funnel\n\n");
        sb.append(funnel.asTable()).append("\n");

        sb.append("## Prediction Accuracy (by arm)\n\n");
        sb.append("| Arm | n | MAE (s) | RMSE (s) | Bias (s) | p50 | p90 | Precision | Recall | F1 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        // "prior" is the baseline term with no turnaround physics. If it matches "model", the
        // pressure term is not earning its complexity.
        for (String arm : List.of("model", "prior", "zero", "flat")) {
            ErrorMetrics em = predictionErrorByArm.get(arm);
            ClassificationMetrics cm = predictionClassByArm.get(arm);
            if (em == null || cm == null) continue;
            sb.append("| %s | %d | %.1f | %.1f | %.1f | %d | %d | %.3f | %.3f | %.3f |%n".formatted(
                    arm, em.count(), em.maeSeconds(), em.rmseSeconds(), em.biasSeconds(),
                    em.p50AbsErrorSeconds(), em.p90AbsErrorSeconds(),
                    cm.precision(), cm.recall(), cm.f1()));
        }
        sb.append("\n");

        if (!delayDistributions.isEmpty()) {
            sb.append("## Distribution Sanity Check\n\n");
            sb.append("Signed BTS `DEP_DELAY` per window. Priors are fitted on the training window\n");
            sb.append("and scored on the eval window, so a large shift here means the priors\n");
            sb.append("underfit — a data difference, not a model failure.\n\n");
            sb.append("| Window | n | Median (s) | Mean (s) | p90 (s) |\n|---|---|---|---|---|\n");
            for (DelayDistribution d : delayDistributions) {
                sb.append("| %s | %d | %d | %.1f | %d |%n".formatted(
                        d.label(), d.count(), d.medianSeconds(), d.meanSeconds(), d.p90Seconds()));
            }
            sb.append("\n");
        }

        sb.append("## Prediction Confusion Matrix (model arm, DelayClassification)\n\n");
        sb.append("Predictions: ").append(predictionConfusion.totalPredictions())
                .append(", backtestable: ").append(predictionConfusion.backtestableCount())
                .append(", MAE: ").append("%.1f".formatted(predictionConfusion.meanAbsoluteErrorSeconds()))
                .append("s\n\n");
        predictionConfusion.confusionMatrix().forEach((predicted, actuals) ->
                actuals.forEach((actual, count) ->
                        sb.append("- predicted=").append(predicted).append(" actual=").append(actual)
                                .append(": ").append(count).append("\n")));
        sb.append("\n");

        sb.append("## Cascade Accuracy\n\n");
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| Total chains | ").append(cascadeSummary.totalChains()).append(" |\n");
        sb.append("| Total hops | ").append(cascadeSummary.totalHops()).append(" |\n");
        sb.append("| Backtestable hops | ").append(cascadeSummary.backtestableHops()).append(" |\n");
        sb.append("| Scored hops (late-aircraft) | ").append(cascadeErrorMetrics.count()).append(" |\n");
        sb.append("| Hop MAE vs late-aircraft (s) | ").append("%.1f".formatted(cascadeErrorMetrics.maeSeconds())).append(" |\n");
        sb.append("| Hop RMSE vs late-aircraft (s) | ").append("%.1f".formatted(cascadeErrorMetrics.rmseSeconds())).append(" |\n");
        sb.append("| Hop bias vs late-aircraft (s) | ").append("%.1f".formatted(cascadeErrorMetrics.biasSeconds())).append(" |\n");
        sb.append("| Hop p50/p90 vs late-aircraft (s) | ")
                .append(cascadeErrorMetrics.p50AbsErrorSeconds()).append(" / ")
                .append(cascadeErrorMetrics.p90AbsErrorSeconds()).append(" |\n");
        sb.append("| Hop MAE vs total dep delay (s) | ").append("%.1f".formatted(cascadeTotalDelayErrorMetrics.maeSeconds())).append(" |\n");
        sb.append("| Hop bias vs total dep delay (s) | ").append("%.1f".formatted(cascadeTotalDelayErrorMetrics.biasSeconds())).append(" |\n");
        sb.append("| Avg chain length | ").append("%.2f".formatted(cascadeSummary.avgChainLength())).append(" |\n");
        sb.append("| Precision (>=15min) | ").append("%.3f".formatted(cascadeSummary.precision())).append(" |\n");
        sb.append("| Recall (>=15min) | ").append("%.3f".formatted(cascadeRecall)).append(" |\n");
        sb.append("| F1 | ").append("%.3f".formatted(cascadeF1)).append(" |\n\n");

        sb.append("## Top 10 Worst-Error Predictions (model arm)\n\n");
        sb.append("| Callsign | Route | Scheduled Dep Epoch | Predicted (s) | Actual (s) | Abs Error (s) |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (WorstCase wc : worstCases) {
            sb.append("| %s | %s | %d | %d | %d | %d |%n".formatted(
                    wc.callsign(), wc.route(), wc.scheduledDepEpoch(),
                    wc.predictedSeconds(), wc.actualSeconds(), wc.absError()));
        }

        return sb.toString();
    }
}
