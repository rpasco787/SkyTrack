package skytrack.demo.backtest;

import java.util.List;

public record ErrorMetrics(int count, double maeSeconds, double rmseSeconds, double biasSeconds) {

    public record Pair(long predictedSeconds, long actualSeconds) {
        long error() { return predictedSeconds - actualSeconds; }
    }

    public static ErrorMetrics of(List<Pair> pairs) {
        if (pairs.isEmpty()) return new ErrorMetrics(0, 0.0, 0.0, 0.0);

        double sumAbs = 0.0;
        double sumSigned = 0.0;
        double sumSquared = 0.0;
        for (Pair p : pairs) {
            long error = p.error();
            sumAbs += Math.abs(error);
            sumSigned += error;
            sumSquared += (double) error * error;
        }
        int n = pairs.size();
        return new ErrorMetrics(n, sumAbs / n, Math.sqrt(sumSquared / n), sumSigned / n);
    }
}
