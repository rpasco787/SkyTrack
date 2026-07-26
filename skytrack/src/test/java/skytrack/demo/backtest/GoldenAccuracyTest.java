package skytrack.demo.backtest;

import org.junit.jupiter.api.Test;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.config.PredictionProperties;
import skytrack.demo.model.CascadeChain;
import skytrack.demo.model.PredictedDelayEvent;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.parquet.HistoricalPredictionWriter;
import skytrack.demo.service.AirportTimeZoneResolver;
import skytrack.demo.service.BtsScheduleRepository;
import skytrack.demo.service.CallsignParser;
import skytrack.demo.service.CascadeAccuracyService;
import skytrack.demo.service.CascadeChainDetector;
import skytrack.demo.service.DelayPredictionService;
import skytrack.demo.service.DelayPredictor;
import skytrack.demo.service.OutboundScheduleResolver;
import skytrack.demo.service.RecentPredictionStore;
import skytrack.demo.service.TurnaroundEstimator;
import skytrack.demo.sqs.SqsAirportEventProducer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Golden-metrics regression test — runs unconditionally in {@code mvn test} against a committed
 * BTS fixture (subset of {@code skytrack/data/bts/btsdata.csv} for tails seen in the 2026-03-09
 * replay) and a pre-resolved arrivals fixture, so it needs neither the 370-file replay nor the
 * full 196MB BTS CSV. Bands are empirical (measured, not designed) — see the constants below for
 * how to regenerate them.
 */
class GoldenAccuracyTest {

    private static final Path FIXTURE_CSV =
            Path.of("skytrack/src/test/resources/backtest/bts-fixture-2026-03-09.csv");
    private static final Path FIXTURE_ARRIVALS =
            Path.of("skytrack/src/test/resources/backtest/resolved-arrivals-2026-03-09.jsonl");

    // Measured 2026-07-25 on the committed fixture (1910 backtestable predictions). Regenerate
    // the fixture with:
    //   cd skytrack && mvn test -Dtest=AccuracyBacktestIT#generateGoldenFixture -Dskytrack.tooling=true
    // model MAE = 590.4s, bias = -153.5s (under-predicting) -> +/-20% band around MAE; bias
    // ceiling is 20% closer to zero than measured, since upward (over-prediction) drift is the
    // specific failure mode a fixed-floor turnaround model risks.
    private static final double MAE_LOWER = 472.3;
    private static final double MAE_UPPER = 708.5;
    private static final double BIAS_CEILING = -122.8;

    // Cascade: precision = 0.947 on 270 backtestable hops, 115 chains, hop-level MAE = 1538.0s.
    // Precision alone is insensitive to en-route-recovery-factor (it only checks whether BTS
    // confirmed *any* late-aircraft delay, not whether our predicted magnitude was close) — the
    // hop-level MAE band is what actually catches recovery/turnaround drift. Verified empirically:
    // en-route-recovery-factor 0.15 -> 0.9 only moves hop MAE ~15% (chains are short and
    // threshold-gated), so this band uses +/-10%, not +/-20% like the prediction MAE band above.
    private static final double CASCADE_PRECISION_LOWER = 0.76;
    private static final double CASCADE_HOP_MAE_LOWER = 1384.2;
    private static final double CASCADE_HOP_MAE_UPPER = 1691.8;

    @Test
    void predictionMaeStaysWithinBand() throws Exception {
        var repo = BtsScheduleRepository.fromCsv(FIXTURE_CSV.toString(), new AirportTimeZoneResolver());
        List<ResolvedArrival> arrivals = readArrivals();

        var callsignParser = new CallsignParser();
        var predProps = new PredictionProperties(true, FIXTURE_CSV.toString(), 45, 0);
        var turnaround = new TurnaroundEstimator(predProps, repo.medianTurnaroundSecondsByCarrier());
        var outboundResolver = new OutboundScheduleResolver(callsignParser, repo);
        var predictor = new DelayPredictor();
        var store = new CapturingStore();
        var service = new DelayPredictionService(
                outboundResolver, turnaround, predictor, predProps,
                store, mock(SqsAirportEventProducer.class), mock(HistoricalPredictionWriter.class),
                Clock.systemUTC());

        for (ResolvedArrival arrival : arrivals) {
            service.predictNextDeparture(arrival);
        }

        List<ErrorMetrics.Pair> pairs = store.events().stream()
                .filter(e -> e.actualDelaySeconds() != null)
                .map(e -> new ErrorMetrics.Pair(e.predictedDelaySeconds(), e.actualDelaySeconds()))
                .toList();
        var metrics = ErrorMetrics.of(pairs);

        assertThat(metrics.count()).isGreaterThan(20);
        assertThat(metrics.maeSeconds()).isBetween(MAE_LOWER, MAE_UPPER);
        assertThat(metrics.biasSeconds()).isLessThan(BIAS_CEILING);
    }

    @Test
    void cascadePrecisionStaysWithinBand() throws Exception {
        var repo = BtsScheduleRepository.fromCsv(FIXTURE_CSV.toString(), new AirportTimeZoneResolver());
        List<ResolvedArrival> arrivals = readArrivals();

        var callsignParser = new CallsignParser();
        var predProps = new PredictionProperties(true, FIXTURE_CSV.toString(), 45, 0);
        var turnaround = new TurnaroundEstimator(predProps, repo.medianTurnaroundSecondsByCarrier());
        var disruptionProps = new DisruptionScoreProperties(60, 1, 15, 30, 0.85, 0.15, 8);
        var detector = new CascadeChainDetector(
                callsignParser, repo, turnaround, disruptionProps,
                Clock.systemUTC(), repo.medianRecoveryFactorByRoute());

        List<CascadeChain> chains = new ArrayList<>();
        for (ResolvedArrival arrival : arrivals) {
            detector.detect(arrival).ifPresent(chains::add);
        }

        var summary = new CascadeAccuracyService().summarize("FIXTURE", chains);
        List<ErrorMetrics.Pair> hopPairs = chains.stream()
                .flatMap(c -> c.hops().stream())
                .filter(h -> h.actualDepDelaySeconds() != null)
                .map(h -> new ErrorMetrics.Pair(h.predictedDepDelaySeconds(), h.actualDepDelaySeconds()))
                .toList();
        var hopError = ErrorMetrics.of(hopPairs);

        assertThat(summary.backtestableHops()).isGreaterThan(20);
        assertThat(summary.precision()).isGreaterThanOrEqualTo(CASCADE_PRECISION_LOWER);
        assertThat(hopError.maeSeconds()).isBetween(CASCADE_HOP_MAE_LOWER, CASCADE_HOP_MAE_UPPER);
    }

    private static List<ResolvedArrival> readArrivals() throws IOException {
        var mapper = new ObjectMapper();
        try (Stream<String> lines = Files.lines(FIXTURE_ARRIVALS)) {
            return lines.filter(l -> !l.isBlank())
                    .map(l -> mapper.readValue(l, ResolvedArrival.class))
                    .toList();
        }
    }

    /** Local unbounded collector — the production RecentPredictionStore caps at 50/airport. */
    private static class CapturingStore extends RecentPredictionStore {
        private final List<PredictedDelayEvent> events = new ArrayList<>();

        @Override
        public void add(PredictedDelayEvent event) {
            events.add(event);
        }

        @Override
        public List<PredictedDelayEvent> getRecent(String airportIata) {
            return events.stream().filter(e -> airportIata.equals(e.departureAirportIata())).toList();
        }

        List<PredictedDelayEvent> events() {
            return events;
        }
    }
}
