package skytrack.demo.backtest;

import org.junit.jupiter.api.Test;
import skytrack.demo.client.ReplayOpenSkyClient;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.config.OpenSkyProperties;
import skytrack.demo.config.PredictionProperties;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.AircraftTrack;
import skytrack.demo.model.BtsFlightRecord;
import skytrack.demo.model.CascadeChain;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.model.OutboundFlight;
import skytrack.demo.model.PredictedDelayEvent;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.parquet.HistoricalPredictionWriter;
import skytrack.demo.service.AircraftStateMachine;
import skytrack.demo.service.AirportLookupService;
import skytrack.demo.service.AirportTimeZoneResolver;
import skytrack.demo.service.BtsScheduleRepository;
import skytrack.demo.service.CallsignParser;
import skytrack.demo.service.CascadeAccuracyService;
import skytrack.demo.service.CascadeChainDetector;
import skytrack.demo.service.DelayPredictionService;
import skytrack.demo.service.DelayPredictor;
import skytrack.demo.service.OutboundScheduleResolver;
import skytrack.demo.service.PredictionAccuracyService;
import skytrack.demo.service.RecentPredictionStore;
import skytrack.demo.service.TurnaroundEstimator;
import skytrack.demo.sqs.SqsAirportEventProducer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Full replay + BTS accuracy backtest. Enumerates every landing in the 370-file OpenSky replay,
 * resolves each to BTS ground truth via {@link BtsArrivalResolver}, and scores three prediction
 * arms (model / zero / flat-propagation) plus the cascade chain detector.
 *
 * Gated — never runs in {@code mvn test}. Run with:
 *   cd skytrack && mvn test -Dtest=AccuracyBacktestIT -Dskytrack.backtest=true
 */
class AccuracyBacktestIT {

    private static final long REPLAY_DAY_START = 1773014400L; // 2026-03-09 00:00 UTC
    private static final long REPLAY_DAY_END   = 1773187200L; // 2026-03-11 00:00 UTC (2-day window
                                                              // so evening rotations resolve)
    private static final long CLASSIFICATION_THRESHOLD_SECONDS = 900L; // 15 min
    private static final double FLAT_PROPAGATION_FACTOR = 0.85;
    private static final double MAX_PREDICTION_ERROR_RATE = 0.05;

    private record PredictionSample(String callsign, String route, long scheduledDepEpoch,
                                     long inboundDelaySeconds, long modelPredictedSeconds,
                                     long actualSeconds) {}

    @Test
    void backtestPredictionAndCascadeAgainstBtsGroundTruth() throws Exception {
        assumeTrue(Boolean.getBoolean("skytrack.backtest"),
                "Backtest harness — run with -Dskytrack.backtest=true");
        assumeTrue(Files.exists(Path.of("skytrack/data/bts/btsdata.csv")));
        assumeTrue(Files.exists(Path.of("skytrack/data/recorded-opensky/")));

        // Phase 1: replay to landings
        List<LandingEvent> landings = replayLandings("./skytrack/data/recorded-opensky/");

        // Phase 2: load BTS, windowed
        var tzResolver = new AirportTimeZoneResolver();
        var repo = BtsScheduleRepository.fromCsv(
                "skytrack/data/bts/btsdata.csv", tzResolver, REPLAY_DAY_START, REPLAY_DAY_END);
        assumeTrue(repo.size() > 0);
        var carrierTurnarounds = repo.medianTurnaroundSecondsByCarrier();
        var routeRecovery = repo.medianRecoveryFactorByRoute();

        // Phase 3: resolve arrivals and run the arms
        var callsignParser = new CallsignParser();
        var arrivalResolver = new BtsArrivalResolver(callsignParser, repo);
        var outboundResolver = new OutboundScheduleResolver(callsignParser, repo);
        var predProps = new PredictionProperties(true, "skytrack/data/bts/btsdata.csv", 45, 0);
        var turnaroundEstimator = new TurnaroundEstimator(predProps, carrierTurnarounds);
        var delayPredictor = new DelayPredictor();
        var predictionStore = new UnboundedPredictionStore();
        var predictionService = new DelayPredictionService(
                outboundResolver, turnaroundEstimator, delayPredictor, predProps,
                predictionStore, mock(SqsAirportEventProducer.class),
                mock(HistoricalPredictionWriter.class), Clock.systemUTC());

        var disruptionProps = new DisruptionScoreProperties(60, 1, 15, 30, 0.85, 0.15, 8);
        var cascadeDetector = new CascadeChainDetector(
                callsignParser, repo, turnaroundEstimator, disruptionProps,
                Clock.systemUTC(), routeRecovery);

        List<ResolvedArrival> resolvedArrivals = new ArrayList<>();
        List<CascadeChain> chains = new ArrayList<>();
        List<PredictionSample> samples = new ArrayList<>();
        int nextDepartureFound = 0;
        int predictionErrors = 0;

        for (LandingEvent landing : landings) {
            var arrivalOpt = arrivalResolver.resolve(landing);
            if (arrivalOpt.isEmpty()) continue;
            ResolvedArrival arrival = arrivalOpt.get();
            resolvedArrivals.add(arrival);

            var outboundOpt = outboundResolver.resolve(arrival);
            if (outboundOpt.isPresent()) {
                nextDepartureFound++;
                // predictNextDeparture swallows exceptions (catch-and-log); with threshold=0 every
                // resolved outbound must yield exactly one stored event, so a resolved-but-silent
                // gap can only mean an internal exception was eaten. Detect it by lockstep diff.
                int before = predictionStore.allEvents().size();
                predictionService.predictNextDeparture(arrival);
                List<PredictedDelayEvent> added =
                        predictionStore.allEvents().subList(before, predictionStore.allEvents().size());
                if (added.isEmpty()) {
                    predictionErrors++;
                } else {
                    PredictedDelayEvent event = added.get(0);
                    if (event.actualDelaySeconds() != null && arrival.delaySeconds() != null) {
                        OutboundFlight out = outboundOpt.get();
                        samples.add(new PredictionSample(
                                arrival.callsign(),
                                arrival.arrivalAirportIata() + "-" + out.departureAirportIata(),
                                out.scheduledDepEpoch(),
                                arrival.delaySeconds(),
                                event.predictedDelaySeconds(),
                                event.actualDelaySeconds()));
                    }
                }
            }

            cascadeDetector.detect(arrival).ifPresent(chains::add);
        }

        double predictionErrorRate = nextDepartureFound == 0 ? 0.0
                : (double) predictionErrors / nextDepartureFound;
        assertThat(predictionErrorRate)
                .as("Silent prediction failures (swallowed exceptions in DelayPredictionService) should be rare")
                .isLessThan(MAX_PREDICTION_ERROR_RATE);

        var funnel = new CoverageFunnel(
                landings.size(),
                arrivalResolver.callsignParsed(),
                arrivalResolver.inboundLegFound(),
                nextDepartureFound,
                samples.size());

        // Phase 4/5: score
        List<ErrorMetrics.Pair> modelPairs = samples.stream()
                .map(s -> new ErrorMetrics.Pair(s.modelPredictedSeconds(), s.actualSeconds()))
                .toList();
        List<ErrorMetrics.Pair> zeroPairs = samples.stream()
                .map(s -> new ErrorMetrics.Pair(0L, s.actualSeconds()))
                .toList();
        List<ErrorMetrics.Pair> flatPairs = samples.stream()
                .map(s -> new ErrorMetrics.Pair(
                        Math.round(s.inboundDelaySeconds() * FLAT_PROPAGATION_FACTOR), s.actualSeconds()))
                .toList();

        ErrorMetrics modelError = ErrorMetrics.of(modelPairs);
        ErrorMetrics zeroError = ErrorMetrics.of(zeroPairs);
        ErrorMetrics flatError = ErrorMetrics.of(flatPairs);
        ClassificationMetrics modelClass = ClassificationMetrics.at(CLASSIFICATION_THRESHOLD_SECONDS, modelPairs);
        ClassificationMetrics zeroClass = ClassificationMetrics.at(CLASSIFICATION_THRESHOLD_SECONDS, zeroPairs);
        ClassificationMetrics flatClass = ClassificationMetrics.at(CLASSIFICATION_THRESHOLD_SECONDS, flatPairs);

        var predictionAccuracySummary = new PredictionAccuracyService()
                .summarize("ALL", predictionStore.allEvents());

        List<ErrorMetrics.Pair> cascadeHopPairs = chains.stream()
                .flatMap(c -> c.hops().stream())
                .filter(h -> h.actualDepDelaySeconds() != null)
                .map(h -> new ErrorMetrics.Pair(h.predictedDepDelaySeconds(), h.actualDepDelaySeconds()))
                .toList();
        ErrorMetrics cascadeError = ErrorMetrics.of(cascadeHopPairs);
        var cascadeAccuracySummary = new CascadeAccuracyService().summarize("ALL", chains);

        // Cascade recall: BTS legs departing an airport we observed a landing at, with a real
        // late-aircraft delay above threshold, that never showed up as an emitted hop.
        Set<String> observedAirports = resolvedArrivals.stream()
                .map(ResolvedArrival::arrivalAirportIata)
                .collect(Collectors.toSet());
        List<BtsFlightRecord> candidateLateLegs = repo.all().stream()
                .filter(r -> observedAirports.contains(r.origin()))
                .filter(r -> r.lateAircraftDelaySeconds() != null
                        && r.lateAircraftDelaySeconds() > CLASSIFICATION_THRESHOLD_SECONDS)
                .toList();
        Set<String> emittedHopKeys = chains.stream()
                .flatMap(c -> c.hops().stream())
                .map(h -> hopKey(h.carrierIata(), h.flightNumber(), h.scheduledDepEpoch()))
                .collect(Collectors.toSet());
        long cascadeTruePositivesForRecall = candidateLateLegs.stream()
                .filter(r -> emittedHopKeys.contains(hopKey(r.carrierIata(), r.flightNumber(), r.scheduledDepEpoch())))
                .count();
        double cascadeRecall = candidateLateLegs.isEmpty() ? 0.0
                : (double) cascadeTruePositivesForRecall / candidateLateLegs.size();
        double cascadeF1 = (cascadeAccuracySummary.precision() + cascadeRecall) == 0 ? 0.0
                : 2 * cascadeAccuracySummary.precision() * cascadeRecall
                        / (cascadeAccuracySummary.precision() + cascadeRecall);

        List<BacktestReport.WorstCase> worstCases = samples.stream()
                .sorted(Comparator.comparingLong(
                        (PredictionSample s) -> Math.abs(s.modelPredictedSeconds() - s.actualSeconds())).reversed())
                .limit(10)
                .map(s -> new BacktestReport.WorstCase(
                        s.callsign(), s.route(), s.scheduledDepEpoch(), s.modelPredictedSeconds(), s.actualSeconds()))
                .toList();

        // Phase 6: report and assert
        String report = BacktestReport.render(
                Instant.now(),
                funnel,
                Map.of("model", modelError, "zero", zeroError, "flat", flatError),
                Map.of("model", modelClass, "zero", zeroClass, "flat", flatClass),
                predictionAccuracySummary,
                cascadeAccuracySummary,
                cascadeError,
                cascadeRecall,
                cascadeF1,
                worstCases);

        Path out = Path.of("docs/backtest-results-" + LocalDate.now() + ".md");
        Files.writeString(out, report);
        System.out.println(report);

        assertThat(funnel.groundTruthPresent()).isGreaterThan(100);
        assertThat(modelError.maeSeconds()).isLessThan(zeroError.maeSeconds());
        assertThat(modelError.maeSeconds()).isLessThan(flatError.maeSeconds());
        assertThat(cascadeAccuracySummary.precision()).isGreaterThan(0.5);
    }

    private static String hopKey(String carrierIata, String flightNumber, long scheduledDepEpoch) {
        return carrierIata + "|" + flightNumber + "|" + scheduledDepEpoch;
    }

    private List<LandingEvent> replayLandings(String replayDir) throws IOException {
        var airportLookup = new AirportLookupService("data/airports/airports.csv");
        airportLookup.loadAirports();
        var stateMachine = new AircraftStateMachine(
                airportLookup, new StateMachineProperties(150, 50, 5, 300));
        var replay = new ReplayOpenSkyClient(
                new OpenSkyProperties("replay", null, null, null, replayDir, 1),
                new ObjectMapper());

        Map<String, AircraftTrack> tracks = new HashMap<>();
        List<LandingEvent> landings = new ArrayList<>();

        for (List<FlightPosition> batch = replay.fetchPositions();
             !batch.isEmpty();
             batch = replay.fetchPositions()) {
            for (FlightPosition pos : batch) {
                AircraftTrack track = tracks.computeIfAbsent(pos.icao24(), AircraftTrack::initial);
                var result = stateMachine.process(track, pos);
                tracks.put(pos.icao24(), result.updatedTrack());
                result.landingEvent().ifPresent(landings::add);
            }
        }
        return landings;
    }

    /** Local unbounded collector — the production RecentPredictionStore caps at 50/airport. */
    private static class UnboundedPredictionStore extends RecentPredictionStore {
        private final List<PredictedDelayEvent> events = new ArrayList<>();

        @Override
        public void add(PredictedDelayEvent event) {
            events.add(event);
        }

        @Override
        public List<PredictedDelayEvent> getRecent(String airportIata) {
            return events.stream().filter(e -> airportIata.equals(e.departureAirportIata())).toList();
        }

        List<PredictedDelayEvent> allEvents() {
            return events;
        }
    }
}
