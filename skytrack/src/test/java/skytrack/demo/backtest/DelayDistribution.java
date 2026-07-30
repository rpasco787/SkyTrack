package skytrack.demo.backtest;

import skytrack.demo.model.BtsFlightRecord;
import skytrack.demo.service.BtsScheduleRepository;

import java.util.Arrays;

/**
 * Signed departure-delay distribution for one BTS window.
 *
 * <p>Exists to keep the training window honest. Priors fitted on 3/1–3/8 assume 3/9 is
 * structurally typical; if the replay day were a weather event, the priors would underfit and the
 * report would read as model failure rather than as a distribution shift. Reporting both windows
 * side by side makes that visible instead of leaving it to be misread.</p>
 *
 * <p>Median and mean are both shown deliberately: departure delay is heavily right-skewed, and
 * the gap between them is what explains the sign of {@code BaselineDelayPrior}.</p>
 */
public record DelayDistribution(String label, int count, long medianSeconds, double meanSeconds,
                                long p90Seconds) {

    public static DelayDistribution of(String label, BtsScheduleRepository repo) {
        long[] delays = repo.all().stream()
                .filter(r -> !r.cancelled() && r.actualDepDelaySeconds() != null)
                .mapToLong(BtsFlightRecord::actualDepDelaySeconds)
                .sorted()
                .toArray();
        if (delays.length == 0) return new DelayDistribution(label, 0, 0L, 0.0, 0L);
        return new DelayDistribution(label, delays.length,
                delays[delays.length / 2],
                Arrays.stream(delays).average().orElse(0.0),
                delays[Math.min((int) (delays.length * 0.9), delays.length - 1)]);
    }
}
