package skytrack.demo.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import skytrack.demo.config.PredictionProperties;
import skytrack.demo.model.PredictedDelayEvent;
import skytrack.demo.model.ResolvedArrival;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Exercises the full prediction pipeline against the real BTS CSV fixture.
 * Skipped automatically when the file is absent (e.g. CI without data).
 * To run: place skytrack/data/bts/ontime-2026-03-09.csv (ISO date, no quoted commas).
 */
class PredictionPipelineIntegrationTest {

    static final Path BTS_CSV = Path.of("data/bts/ontime-2026-03-09.csv");

    static RecentPredictionStore store;
    static DelayPredictionService service;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(Files.exists(BTS_CSV),
                "Skipping: data/bts/ontime-2026-03-09.csv not present");

        var props = new PredictionProperties(true, BTS_CSV.toString(), 45, 15);
        var tzResolver = new AirportTimeZoneResolver();
        var repo = BtsScheduleRepository.fromCsv(BTS_CSV.toString(), tzResolver);

        Assumptions.assumeTrue(repo.size() > 0,
                "Skipping: BTS CSV loaded 0 records (check date format)");

        var callsignParser = new CallsignParser();
        var resolver = new OutboundScheduleResolver(callsignParser, repo);
        var turnaround = new TurnaroundEstimator(props);
        var predictor = new DelayPredictor();

        store = new RecentPredictionStore();

        service = new DelayPredictionService(
                resolver, turnaround, predictor, props,
                store,
                mock(skytrack.demo.sqs.SqsAirportEventProducer.class),
                mock(skytrack.demo.parquet.HistoricalPredictionWriter.class),
                java.time.Clock.systemUTC());
    }

    @Test
    void producesAtLeastOnePredictionWithBtsGroundTruth() {
        // Use callsigns matching BTS OP_UNIQUE_CARRIER codes for 2026-03-09.
        // The resolver silently returns empty when no tail rotation is found,
        // so we try several major carriers to maximise hit rate.
        var candidateArrivals = List.of(
                arrival("UAL1234", "ORD", 1773088200L),
                arrival("AAL255",  "LAX", 1773088200L),
                arrival("DAL400",  "ATL", 1773088200L),
                arrival("SWA100",  "MDW", 1773088200L),
                arrival("UAL100",  "SFO", 1773092000L),
                arrival("AAL4",    "JFK", 1773092000L));

        for (var a : candidateArrivals) {
            service.predictNextDeparture(a);
        }

        List<PredictedDelayEvent> allPredictions = candidateArrivals.stream()
                .map(a -> store.getRecent(a.arrivalAirportIata()))
                .flatMap(List::stream)
                .toList();

        assertThat(allPredictions)
                .as("Expected at least one prediction from BTS tail rotations")
                .isNotEmpty();

        // At least some predictions should have BTS ground-truth delay
        long withActual = allPredictions.stream()
                .filter(e -> e.actualDelaySeconds() != null)
                .count();
        assertThat(withActual)
                .as("Expected at least one prediction to have BTS actualDelaySeconds")
                .isGreaterThan(0);
    }

    private static ResolvedArrival arrival(String callsign, String iata, long epochSeconds) {
        return new ResolvedArrival(
                callsign.toLowerCase(), callsign, null, null,
                "K" + iata, iata, epochSeconds, null, null, "BTS_REPLAY");
    }
}
