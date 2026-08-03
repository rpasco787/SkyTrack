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
import skytrack.demo.service.BaselineDelayPrior;
import skytrack.demo.service.BtsScheduleRepository;
import skytrack.demo.service.CallsignParser;
import skytrack.demo.service.BtsRowParser;
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
import java.util.HashSet;
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

    private static final long TRAIN_START      = 1772323200L; // 2026-03-01 00:00 UTC
    private static final long TRAIN_END        = 1773014400L; // 2026-03-09 00:00 UTC (exclusive)
    private static final long REPLAY_DAY_START = 1773014400L; // 2026-03-09 00:00 UTC
    private static final long REPLAY_DAY_END   = 1773187200L; // 2026-03-11 00:00 UTC (2-day window
                                                              // so evening rotations resolve)
    private static final long CLASSIFICATION_THRESHOLD_SECONDS = 900L; // 15 min
    private static final double FLAT_PROPAGATION_FACTOR = 0.85;
    private static final double MAX_PREDICTION_ERROR_RATE = 0.05;

    private record PredictionSample(String callsign, String route, long scheduledDepEpoch,
                                     long inboundDelaySeconds, long modelPredictedSeconds,
                                     long priorOnlySeconds, long actualSeconds) {}

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
        var routeRecovery = repo.medianRecoveryFactorByRoute();

        // Everything fitted rather than observed comes from days strictly before the replay day.
        // Fitting the prior or the turnaround percentiles on 3/9 and then scoring 3/9 would leak
        // the answer into the prediction and make the whole comparison meaningless.
        var trainRepo = BtsScheduleRepository.fromCsv(
                "skytrack/data/bts/btsdata.csv", tzResolver, TRAIN_START, TRAIN_END);
        assumeTrue(trainRepo.size() > 0);
        var carrierTurnarounds = trainRepo.pressuredTurnaroundP15ByCarrierAirport();
        var expectedTurnarounds = trainRepo.pressuredTurnaroundP50ByCarrierAirport();
        var baselinePrior = BaselineDelayPrior.from(trainRepo, tzResolver);

        // Phase 3: resolve arrivals and run the arms
        var callsignParser = new CallsignParser();
        var arrivalResolver = new BtsArrivalResolver(callsignParser, repo);
        var predProps = new PredictionProperties(true, "skytrack/data/bts/btsdata.csv", 45, 0, 360);
        var outboundResolver = new OutboundScheduleResolver(callsignParser, repo, predProps);
        var turnaroundEstimator = new TurnaroundEstimator(
                predProps, carrierTurnarounds, expectedTurnarounds);
        var delayPredictor = new DelayPredictor();
        var predictionStore = new UnboundedPredictionStore();
        var predictionService = new DelayPredictionService(
                outboundResolver, turnaroundEstimator, delayPredictor, baselinePrior, predProps,
                predictionStore, mock(SqsAirportEventProducer.class),
                mock(HistoricalPredictionWriter.class), Clock.systemUTC());

        var disruptionProps = new DisruptionScoreProperties(60, 1, 10, 5, 0.85, 0.15, 8);
        var cascadeDetector = new CascadeChainDetector(
                callsignParser, repo, turnaroundEstimator, disruptionProps, predProps,
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
                                out.departureAirportIata() + "-" + out.destAirportIata(),
                                out.scheduledDepEpoch(),
                                arrival.delaySeconds(),
                                event.predictedDelaySeconds(),
                                baselinePrior.priorSeconds(out.carrierIata(),
                                        out.departureAirportIata(), out.scheduledDepEpoch()),
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

        // Baseline term with no turnaround physics. If this matches the model arm, the pressure
        // term is not earning its complexity.
        List<ErrorMetrics.Pair> priorPairs = samples.stream()
                .map(s -> new ErrorMetrics.Pair(s.priorOnlySeconds(), s.actualSeconds()))
                .toList();

        ErrorMetrics modelError = ErrorMetrics.of(modelPairs);
        ErrorMetrics zeroError = ErrorMetrics.of(zeroPairs);
        ErrorMetrics flatError = ErrorMetrics.of(flatPairs);
        ErrorMetrics priorError = ErrorMetrics.of(priorPairs);
        ClassificationMetrics modelClass = ClassificationMetrics.at(CLASSIFICATION_THRESHOLD_SECONDS, modelPairs);
        ClassificationMetrics zeroClass = ClassificationMetrics.at(CLASSIFICATION_THRESHOLD_SECONDS, zeroPairs);
        ClassificationMetrics flatClass = ClassificationMetrics.at(CLASSIFICATION_THRESHOLD_SECONDS, flatPairs);
        ClassificationMetrics priorClass = ClassificationMetrics.at(CLASSIFICATION_THRESHOLD_SECONDS, priorPairs);

        var predictionAccuracySummary = new PredictionAccuracyService()
                .summarize("ALL", predictionStore.allEvents());

        // Primary hop target is the late-aircraft component. A turnaround model should be judged
        // on the delay it claims to explain; total departure delay also contains carrier, NAS and
        // weather causes it has no way to predict, so scoring against it charges the model for
        // other people's problems.
        List<ErrorMetrics.Pair> cascadeHopPairs = chains.stream()
                .flatMap(c -> c.hops().stream())
                .filter(h -> h.lateAircraftDelaySeconds() != null)
                .map(h -> new ErrorMetrics.Pair(h.predictedDepDelaySeconds(), h.lateAircraftDelaySeconds()))
                .toList();
        List<ErrorMetrics.Pair> cascadeHopTotalDelayPairs = chains.stream()
                .flatMap(c -> c.hops().stream())
                .filter(h -> h.actualDepDelaySeconds() != null)
                .map(h -> new ErrorMetrics.Pair(h.predictedDepDelaySeconds(), h.actualDepDelaySeconds()))
                .toList();
        ErrorMetrics cascadeError = ErrorMetrics.of(cascadeHopPairs);
        ErrorMetrics cascadeTotalDelayError = ErrorMetrics.of(cascadeHopTotalDelayPairs);
        var cascadeAccuracySummary = new CascadeAccuracyService().summarize("ALL", chains);

        // Cascade recall denominator. Counting every late-aircraft leg out of every observed
        // airport across the whole 2-day BTS window measures data collection, not the detector:
        // the replay covers roughly three hours, so most such legs have no causing arrival
        // anywhere in the observation window and are structurally unreachable. Restrict to legs
        // whose causing inbound rotation actually landed at an observed airport inside it.
        Set<String> observedAirports = resolvedArrivals.stream()
                .map(ResolvedArrival::arrivalAirportIata)
                .collect(Collectors.toSet());
        long observedFrom = resolvedArrivals.stream()
                .mapToLong(ResolvedArrival::actualArrivalTime).min().orElse(0L);
        long observedTo = resolvedArrivals.stream()
                .mapToLong(ResolvedArrival::actualArrivalTime).max().orElse(0L);
        List<BtsFlightRecord> candidateLateLegs = repo.all().stream()
                .filter(r -> observedAirports.contains(r.origin()))
                .filter(r -> r.lateAircraftDelaySeconds() != null
                        && r.lateAircraftDelaySeconds() > CLASSIFICATION_THRESHOLD_SECONDS)
                .filter(r -> causingRotationWasObservable(
                        repo, r, observedAirports, observedFrom, observedTo))
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
                Map.of("model", modelError, "prior", priorError,
                        "zero", zeroError, "flat", flatError),
                Map.of("model", modelClass, "prior", priorClass,
                        "zero", zeroClass, "flat", flatClass),
                predictionAccuracySummary,
                cascadeAccuracySummary,
                cascadeError,

                cascadeTotalDelayError,
                cascadeRecall,
                cascadeF1,
                worstCases,
                List.of(DelayDistribution.of("train 03-01..03-08", trainRepo),
                        DelayDistribution.of("eval 03-09..03-10", repo)));

        Path out = Path.of("docs/backtest-results-" + LocalDate.now() + ".md");
        Files.writeString(out, report);
        System.out.println(report);

        assertThat(funnel.groundTruthPresent()).isGreaterThan(100);
        assertThat(modelError.maeSeconds()).isLessThan(zeroError.maeSeconds());
        assertThat(modelError.maeSeconds()).isLessThan(flatError.maeSeconds());
        assertThat(cascadeAccuracySummary.precision()).isGreaterThan(0.5);
    }

    /**
     * Tooling: writes a small, committable BTS fixture (header + rows for tails that actually
     * appear in the resolved landings) plus a JSONL dump of the resolved arrivals, so
     * {@code GoldenAccuracyTest} can run the model arm without the 370-file replay or the 196MB
     * BTS CSV. Gated — run with:
     *   cd skytrack && mvn test -Dtest=AccuracyBacktestIT -Dskytrack.tooling=true
     */
    @Test
    void generateGoldenFixture() throws Exception {
        assumeTrue(Boolean.getBoolean("skytrack.tooling"),
                "Tooling test — run with -Dskytrack.tooling=true");
        assumeTrue(Files.exists(Path.of("skytrack/data/bts/btsdata.csv")));
        assumeTrue(Files.exists(Path.of("skytrack/data/recorded-opensky/")));

        List<LandingEvent> landings = replayLandings("./skytrack/data/recorded-opensky/");

        var tzResolver = new AirportTimeZoneResolver();
        var repo = BtsScheduleRepository.fromCsv(
                "skytrack/data/bts/btsdata.csv", tzResolver, REPLAY_DAY_START, REPLAY_DAY_END);
        var callsignParser = new CallsignParser();
        var arrivalResolver = new BtsArrivalResolver(callsignParser, repo);

        List<ResolvedArrival> resolvedArrivals = new ArrayList<>();
        Set<String> tailNumbers = new HashSet<>();
        for (LandingEvent landing : landings) {
            arrivalResolver.resolve(landing).ifPresent(arrival -> {
                resolvedArrivals.add(arrival);
                callsignParser.parse(arrival.callsign())
                        .flatMap(p -> repo.findInboundLeg(p.iataCarrierCode(), p.flightNumber(),
                                arrival.arrivalAirportIata(), arrival.actualArrivalTime()))
                        .map(BtsFlightRecord::tailNumber)
                        .ifPresent(tailNumbers::add);
            });
        }

        Path fixtureCsv = Path.of("skytrack/src/test/resources/backtest/bts-fixture-2026-03-09.csv");
        Files.createDirectories(fixtureCsv.getParent());
        int written = writeFixtureCsv(
                Path.of("skytrack/data/bts/btsdata.csv"), fixtureCsv, tzResolver, tailNumbers);

        Path fixtureArrivals =
                Path.of("skytrack/src/test/resources/backtest/resolved-arrivals-2026-03-09.jsonl");
        var mapper = new ObjectMapper();
        try (var writer = Files.newBufferedWriter(fixtureArrivals)) {
            for (ResolvedArrival arrival : resolvedArrivals) {
                writer.write(mapper.writeValueAsString(arrival));
                writer.newLine();
            }
        }

        System.out.printf("Wrote %d BTS rows (%d distinct tails) and %d resolved arrivals%n",
                written, tailNumbers.size(), resolvedArrivals.size());
    }

    private static int writeFixtureCsv(Path source, Path dest, AirportTimeZoneResolver tzResolver,
                                        Set<String> tailNumbers) throws IOException {
        var parser = new BtsRowParser(tzResolver::zoneFor);
        int written = 0;
        try (var reader = Files.newBufferedReader(source);
             var writer = Files.newBufferedWriter(dest)) {
            String header = reader.readLine();
            writer.write(header);
            writer.newLine();
            String[] cols = BtsScheduleRepository.splitCsvLine(header);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < cols.length; i++) {
                idx.put(cols[i].replace("\"", "").trim(), i);
            }

            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = BtsScheduleRepository.splitCsvLine(line);
                var recordOpt = parser.parse(fields, idx);
                if (recordOpt.isEmpty()) continue;
                var record = recordOpt.get();
                if (record.scheduledDepEpoch() < REPLAY_DAY_START
                        || record.scheduledDepEpoch() >= REPLAY_DAY_END) continue;
                if (record.tailNumber() == null || !tailNumbers.contains(record.tailNumber())) continue;
                writer.write(line);
                writer.newLine();
                written++;
            }
        }
        return written;
    }

    /**
     * True when the rotation that caused {@code leg}'s late-aircraft delay is one the replay could
     * have seen: the same tail's previous leg, arriving at an observed airport inside the observed
     * window, at the airport this leg departs from.
     */
    private static boolean causingRotationWasObservable(BtsScheduleRepository repo,
                                                        BtsFlightRecord leg,
                                                        Set<String> observedAirports,
                                                        long observedFrom, long observedTo) {
        return repo.findPreviousLeg(leg.tailNumber(), leg.scheduledDepEpoch())
                .filter(prev -> prev.dest().equals(leg.origin()))
                .filter(prev -> observedAirports.contains(prev.dest()))
                .filter(prev -> prev.scheduledArrEpoch() != null)
                .filter(prev -> prev.scheduledArrEpoch() >= observedFrom
                        && prev.scheduledArrEpoch() <= observedTo)
                .isPresent();
    }

    private static String hopKey(String carrierIata, String flightNumber, long scheduledDepEpoch) {
        return carrierIata + "|" + flightNumber + "|" + scheduledDepEpoch;
    }

    private List<LandingEvent> replayLandings(String replayDir) throws IOException {
        var airportLookup = new AirportLookupService("data/airports/airports.csv");
        airportLookup.loadAirports();
        var stateMachine = new AircraftStateMachine(
                airportLookup, new StateMachineProperties(150, 50, 5, 300, 120));
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
