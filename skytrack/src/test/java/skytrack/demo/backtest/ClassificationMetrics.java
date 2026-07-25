package skytrack.demo.backtest;

import java.util.List;

public record ClassificationMetrics(long thresholdSeconds, int truePositives, int falsePositives,
                                    int falseNegatives, int trueNegatives,
                                    double precision, double recall, double f1) {

    public static ClassificationMetrics at(long thresholdSeconds, List<ErrorMetrics.Pair> pairs) {
        int tp = 0, fp = 0, fn = 0, tn = 0;
        for (ErrorMetrics.Pair p : pairs) {
            boolean predictedPositive = p.predictedSeconds() >= thresholdSeconds;
            boolean actualPositive = p.actualSeconds() >= thresholdSeconds;
            if (predictedPositive && actualPositive) tp++;
            else if (predictedPositive) fp++;
            else if (actualPositive) fn++;
            else tn++;
        }
        double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
        double f1 = (precision + recall) == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
        return new ClassificationMetrics(thresholdSeconds, tp, fp, fn, tn, precision, recall, f1);
    }
}
