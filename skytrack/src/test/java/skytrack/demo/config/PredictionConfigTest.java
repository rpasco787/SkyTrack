package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.BtsFlightRecord;
import skytrack.demo.service.AirportTimeZoneResolver;
import skytrack.demo.service.BaselineDelayPrior;
import skytrack.demo.service.BtsScheduleRepository;
import skytrack.demo.service.TestRepos;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PredictionConfigTest {

    private static final long DEP_EPOCH = 1773090000L; // 2026-03-09 21:00 UTC / 15:00 at ORD

    @Test
    void baselineDelayPriorIsFittedFromTheLoadedBtsRepository() {
        var records = new BtsFlightRecord[BaselineDelayPrior.MIN_SAMPLES];
        for (int i = 0; i < records.length; i++) {
            records[i] = new BtsFlightRecord("UA", String.valueOf(i), "N" + i, "ORD", "LAX",
                    DEP_EPOCH, DEP_EPOCH + 7200, 540L, false, null, null);
        }
        BtsScheduleRepository repo = TestRepos.of(records);

        BaselineDelayPrior prior =
                new PredictionConfig().baselineDelayPrior(repo, new AirportTimeZoneResolver());

        assertThat(prior.priorSeconds("UA", "ORD", DEP_EPOCH)).isEqualTo(540L);
    }

    @Test
    void carrierTurnaroundsAreFittedFromPressuredActualGroundTime() {
        // 30 rotations at ORD: inbound lands 20 min late (at 101_200), and the aircraft pushes
        // back on schedule at 103_000 — 1800s of real ground time inside a 3000s scheduled gap.
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < BaselineDelayPrior.MIN_SAMPLES; i++) {
            records.add(new BtsFlightRecord("UA", "IN", "N" + i, "DEN", "ORD",
                    96_000L, 100_000L, 0L, false, 1200L, null));
            records.add(new BtsFlightRecord("UA", "OUT", "N" + i, "ORD", "LAX",
                    103_000L, 110_000L, 0L, false, null, null));
        }
        var repo = TestRepos.of(records.toArray(BtsFlightRecord[]::new));

        Map<String, Long> turnarounds = new PredictionConfig().carrierTurnarounds(repo);

        assertThat(turnarounds).containsEntry("UA|ORD", 1800L);
    }

    @Test
    void expectedTurnaroundsAreTheMedianRatherThanTheFloorOfTheSamePopulation() {
        // 30 rotations at ORD spanning 600s..3500s of real ground time. The slack floor takes
        // the p15 (1000s); the prediction path must take the p50 (2100s).
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 30; i++) {
            long turnaround = 600 + 100L * i;
            records.add(new BtsFlightRecord("UA", "IN", "N" + i, "DEN", "ORD",
                    96_000L, 100_000L, 0L, false, 1200L, null));
            records.add(new BtsFlightRecord("UA", "OUT", "N" + i, "ORD", "LAX",
                    101_200L + turnaround, 110_000L, 0L, false, null, null));
        }
        var repo = TestRepos.of(records.toArray(BtsFlightRecord[]::new));
        var config = new PredictionConfig();

        assertThat(config.carrierTurnarounds(repo)).containsEntry("UA|ORD", 1000L);
        assertThat(config.expectedTurnarounds(repo)).containsEntry("UA|ORD", 2100L);
    }

    @Test
    void baselineDelayPriorIsInertWhenNoBtsDataIsLoaded() {
        BaselineDelayPrior prior = new PredictionConfig()
                .baselineDelayPrior(BtsScheduleRepository.empty(), new AirportTimeZoneResolver());

        assertThat(prior.priorSeconds("UA", "ORD", DEP_EPOCH)).isZero();
    }
}
