package skytrack.demo.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.config.PredictionProperties;
import skytrack.demo.controller.CascadeController;
import skytrack.demo.model.CascadeAccuracySummary;
import skytrack.demo.model.CascadeChain;
import skytrack.demo.model.ResolvedArrival;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end BTS-replay test for the cascade chain pipeline.
 * Loads a real BTS CSV, feeds a late arrival through CascadeChainDetector,
 * and asserts that RecentCascadeStore receives a chain with backtestable hops.
 *
 * Skipped automatically when skytrack/data/bts/btsdata.csv is absent (CI without data).
 *
 * Verified tail rotation: N277AK on 3/9/2026
 *   AS368 SFO->MCO  arrDelay=59min (inbound, triggers cascade)
 *   AS397 MCO->SAN  depDelay=55min (hop 1, backtestable)
 *   AS220 SAN->BOI  depDelay=4min  (hop 2 if slack propagates, backtestable)
 */
class CascadeChainPipelineIntegrationTest {

    // Surefire workingDirectory=${project.basedir}/.. so paths resolve from repo root.
    static final Path BTS_CSV = Path.of("skytrack/data/bts/btsdata.csv");

    static RecentCascadeStore store;
    static CascadeChainDetector detector;
    static CascadeAccuracyService accuracyService;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(Files.exists(BTS_CSV),
                "Skipping: skytrack/data/bts/btsdata.csv not present");

        var tzResolver = new AirportTimeZoneResolver();
        var repo = BtsScheduleRepository.fromCsv(BTS_CSV.toString(), tzResolver);

        Assumptions.assumeTrue(repo.size() > 0,
                "Skipping: BTS CSV loaded 0 records (check date format)");

        var props = new DisruptionScoreProperties(60, 1, 15, 30, 0.85, 0.15, 8);
        var predProps = new PredictionProperties(true, BTS_CSV.toString(), 45, 15);
        var turnaround = new TurnaroundEstimator(predProps, Map.of());
        var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

        store = new RecentCascadeStore();
        accuracyService = new CascadeAccuracyService();
        detector = new CascadeChainDetector(new CallsignParser(), repo, turnaround, props, clock, Map.of());
    }

    @Test
    void propagatesDelayThroughRealTailRotationWithBtsGroundTruth() {
        // AS368 SFO->MCO on 3/9/2026: actual arrival = CRS_ARR 15:17 EDT + 59min delay.
        // 3/9/2026 00:00 UTC = 1773014400; 15:17 EDT (UTC-4) = 19:17 UTC = +68400+1020 = sched 1773083820
        // Actual = sched + 3540s = 1773087360. arrivalDelay=3540s > 1800s (30min gate) → chain fires.
        var arrival = new ResolvedArrival(
                "asa368", "ASA368", "AS", "368",
                "KMCO", "MCO", 1773087360L,
                1773083820L, 3540L, "BTS_REPLAY");

        Optional<CascadeChain> result = detector.detect(arrival);

        assertThat(result)
                .as("Expected cascade chain from ASA368 landing at MCO via tail N277AK")
                .isPresent();

        CascadeChain chain = result.get();
        store.add(chain);

        assertThat(chain.originAirportIata()).isEqualTo("MCO");
        assertThat(chain.flightsAffected())
                .as("Expected at least 1 downstream flight (AS397 MCO->SAN)")
                .isGreaterThan(0);
        assertThat(chain.totalPredictedDelaySeconds()).isGreaterThan(0);

        // AS397 has actualDepDelay=55min in BTS — verify at least one hop is backtestable
        long backtestableHops = chain.hops().stream()
                .filter(h -> h.actualDepDelaySeconds() != null)
                .count();
        assertThat(backtestableHops)
                .as("Expected at least one hop with BTS ground-truth actualDepDelaySeconds")
                .isGreaterThan(0);

        // The chain delay must shrink hop-by-hop (slack absorption) — not a fixed multiplier
        if (chain.hops().size() >= 2) {
            long hop0 = chain.hops().get(0).predictedDepDelaySeconds();
            long hop1 = chain.hops().get(1).predictedDepDelaySeconds();
            assertThat(hop1)
                    .as("Each hop's predicted delay should be less than the previous (slack absorbs)")
                    .isLessThan(hop0);
        }
    }

    @Test
    void accuracyServiceAggregatesBacktestableHops() {
        var arrival = new ResolvedArrival(
                "asa368", "ASA368", "AS", "368",
                "KMCO", "MCO", 1773087360L,
                1773083820L, 3540L, "BTS_REPLAY");

        detector.detect(arrival).ifPresent(store::add);

        CascadeAccuracySummary summary = accuracyService.summarize("MCO", store.getRecent("MCO"));

        assertThat(summary.totalChains()).isGreaterThan(0);
        assertThat(summary.backtestableHops()).isGreaterThan(0);
        assertThat(summary.hopLevelMaeSeconds()).isGreaterThanOrEqualTo(0.0);
        assertThat(summary.avgChainLength()).isGreaterThan(0.0);
    }

    @Test
    void cascadeControllerEndpointsServeChainAndAccuracyData() {
        var arrival = new ResolvedArrival(
                "asa368", "ASA368", "AS", "368",
                "KMCO", "MCO", 1773087360L,
                1773083820L, 3540L, "BTS_REPLAY");

        detector.detect(arrival).ifPresent(store::add);

        var controller = new CascadeController(store, accuracyService);

        List<CascadeChain> chains = controller.cascades("MCO");
        assertThat(chains).isNotEmpty();
        assertThat(chains.get(0).hops()).isNotEmpty();
        assertThat(chains.get(0).hops().get(0).originIata()).isEqualTo("MCO");

        CascadeAccuracySummary summary = controller.accuracy("MCO");
        assertThat(summary.totalChains()).isGreaterThan(0);
        assertThat(summary.backtestableHops()).isGreaterThanOrEqualTo(0);
    }
}
