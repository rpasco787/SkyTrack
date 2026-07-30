package skytrack.demo.backtest;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.BtsFlightRecord;
import skytrack.demo.service.TestRepos;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DelayDistributionTest {

    @Test
    void summarisesTheSignedDepartureDelayDistribution() {
        // Delays -300,-240,...,+240 across 10 legs. Median index 5 -> +0s, mean -30s.
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 10; i++) records.add(leg(-300 + 60L * i, false));

        var d = DelayDistribution.of("train", TestRepos.of(records.toArray(BtsFlightRecord[]::new)));

        assertThat(d.label()).isEqualTo("train");
        assertThat(d.count()).isEqualTo(10);
        assertThat(d.medianSeconds()).isZero();
        assertThat(d.meanSeconds()).isCloseTo(-30.0, within(0.01));
    }

    @Test
    void medianAndMeanDivergeOnTheRightSkewedRealDistribution() {
        // 9 flights a little early, one 4 hours late — the shape BTS actually has. A negative
        // median next to a strongly positive mean is the signal that the prior will be negative.
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 9; i++) records.add(leg(-180L, false));
        records.add(leg(14_400L, false));

        var d = DelayDistribution.of("eval", TestRepos.of(records.toArray(BtsFlightRecord[]::new)));

        assertThat(d.medianSeconds()).isEqualTo(-180L);
        assertThat(d.meanSeconds()).isGreaterThan(1200.0);
    }

    @Test
    void excludesCancelledLegsWhichHaveNoDepartureDelay() {
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 5; i++) records.add(leg(600L, false));
        for (int i = 0; i < 20; i++) records.add(leg(null, true));

        assertThat(DelayDistribution.of("x", TestRepos.of(records.toArray(BtsFlightRecord[]::new)))
                .count()).isEqualTo(5);
    }

    @Test
    void emptyWindowIsAllZeroNotNaN() {
        var d = DelayDistribution.of("empty", TestRepos.of());

        assertThat(d.count()).isZero();
        assertThat(d.medianSeconds()).isZero();
        assertThat(d.meanSeconds()).isZero();
    }

    private static BtsFlightRecord leg(Long depDelaySeconds, boolean cancelled) {
        return new BtsFlightRecord("UA", "1", "N1", "ORD", "LAX",
                1_000L, 8_000L, depDelaySeconds, cancelled, null, null);
    }
}
