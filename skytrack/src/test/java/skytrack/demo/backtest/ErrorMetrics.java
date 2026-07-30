package skytrack.demo.backtest;

import java.util.List;

/**
 * @param p50AbsErrorSeconds median absolute error, and
 * @param p90AbsErrorSeconds its 90th percentile. MAE and RMSE are both means, so a handful of
 *        multi-hour rotation mismatches can dominate them and mask a real change in the typical
 *        case. The gap between MAE and p50 is a direct read on how tail-driven the error is.
 */
public record ErrorMetrics(int count, double maeSeconds, double rmseSeconds, double biasSeconds,
                           long p50AbsErrorSeconds, long p90AbsErrorSeconds) {

    public record Pair(long predictedSeconds, long actualSeconds) {
        long error() { return predictedSeconds - actualSeconds; }
    }

    public static ErrorMetrics of(List<Pair> pairs) {
        if (pairs.isEmpty()) return new ErrorMetrics(0, 0.0, 0.0, 0.0, 0L, 0L);

        double sumAbs = 0.0;
        double sumSigned = 0.0;
        double sumSquared = 0.0;
        long[] absErrors = new long[pairs.size()];
        int i = 0;
        for (Pair p : pairs) {
            long error = p.error();
            absErrors[i++] = Math.abs(error);
            sumAbs += Math.abs(error);
            sumSigned += error;
            sumSquared += (double) error * error;
        }
        java.util.Arrays.sort(absErrors);
        int n = pairs.size();
        return new ErrorMetrics(n, sumAbs / n, Math.sqrt(sumSquared / n), sumSigned / n,
                quantile(absErrors, 0.50), quantile(absErrors, 0.90));
    }

    private static long quantile(long[] sorted, double q) {
        return sorted[Math.min((int) (sorted.length * q), sorted.length - 1)];
    }
}
