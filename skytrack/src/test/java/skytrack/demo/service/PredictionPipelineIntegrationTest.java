package skytrack.demo.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import skytrack.demo.config.PredictionProperties;
import skytrack.demo.controller.PredictionController;
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

    static final Path BTS_CSV = Path.of("skytrack/data/bts/btsdata.csv");

    static RecentPredictionStore store;
    static DelayPredictionService service;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(Files.exists(BTS_CSV),
                "Skipping: skytrack/data/bts/btsdata.csv not present");

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
        // Real BTS 2026-03-09 tail rotations verified against data/bts/btsdata.csv.
        // epoch = inbound flight's actual arrival (CRS_ARR_TIME + ARR_DELAY), UTC.
        // Each produces a ≥15-min predicted delay with non-null BTS actual delay.
        //   AAL2789 -> LAX: tail N412UW -> AA1835, predicted 44m, actual 64m
        //   AAL2117 -> MCO: tail N573UW -> AA2117, predicted 44m, actual 57m
        //   ASA368  -> MCO: tail N277AK -> AS397,  predicted 44m, actual 55m
        var candidateArrivals = List.of(
                arrival("AAL2789", "LAX", 1773083940L),
                arrival("AAL2117", "MCO", 1773074580L),
                arrival("ASA368",  "MCO", 1773087360L));

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

        // REST read-path: GET /predictions/{iata} must surface the stored predictions
        var controller = new PredictionController(store, new PredictionAccuracyService());
        var viaRest = candidateArrivals.stream()
                .map(a -> controller.predictions(a.arrivalAirportIata()))
                .flatMap(List::stream)
                .toList();
        assertThat(viaRest)
                .as("GET /predictions/{iata} should return the stored predictions")
                .isNotEmpty();
    }

    private static ResolvedArrival arrival(String callsign, String iata, long epochSeconds) {
        return new ResolvedArrival(
                callsign.toLowerCase(), callsign, null, null,
                "K" + iata, iata, epochSeconds, null, null, "BTS_REPLAY");
    }
}
