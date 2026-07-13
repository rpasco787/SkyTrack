package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import skytrack.demo.model.DelayEvent;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.model.WeatherObservation;
import skytrack.demo.parquet.HistoricalDelayWriter;
import skytrack.demo.sqs.SqsAirportEventProducer;

import java.util.Optional;

@Service
public class DelayEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(DelayEventProcessor.class);

    private final DelayComputer delayComputer;
    private final DisruptionScoreService disruptionScoreService;
    private final SqsAirportEventProducer eventProducer;
    private final CascadeDetector cascadeDetector;
    private final WeatherCache weatherCache;
    private final HistoricalDelayWriter historicalDelayWriter;
    private final ScheduleCoverageTracker coverageTracker;
    private final RecentCascadeStore recentCascadeStore;
    private final DelayPredictionService delayPredictionService;

    public DelayEventProcessor(DelayComputer delayComputer,
                               DisruptionScoreService disruptionScoreService,
                               SqsAirportEventProducer eventProducer,
                               CascadeDetector cascadeDetector,
                               WeatherCache weatherCache,
                               HistoricalDelayWriter historicalDelayWriter,
                               ScheduleCoverageTracker coverageTracker,
                               RecentCascadeStore recentCascadeStore,
                               DelayPredictionService delayPredictionService) {
        this.delayComputer = delayComputer;
        this.disruptionScoreService = disruptionScoreService;
        this.eventProducer = eventProducer;
        this.cascadeDetector = cascadeDetector;
        this.weatherCache = weatherCache;
        this.historicalDelayWriter = historicalDelayWriter;
        this.coverageTracker = coverageTracker;
        this.recentCascadeStore = recentCascadeStore;
        this.delayPredictionService = delayPredictionService;
    }

    public void process(ResolvedArrival arrival) {
        Optional<WeatherObservation> weather = weatherCache.get(arrival.arrivalAirportIcao());
        DelayEvent delayEvent = delayComputer.compute(arrival, weather);

        disruptionScoreService.recordDelay(delayEvent);
        eventProducer.send(delayEvent);
        historicalDelayWriter.buffer(delayEvent);
        coverageTracker.record(delayEvent.resolutionMethod());

        cascadeDetector.checkCascade(delayEvent).ifPresent(alert -> {
            recentCascadeStore.add(alert);
            log.info("Cascade risk: {} at {} predicted downstream delay={}min",
                    alert.sourceCallsign(), alert.arrivalAirportIata(),
                    alert.predictedDownstreamDelaySeconds() / 60);
        });

        delayPredictionService.predictNextDeparture(arrival);

        log.debug("Processed delay event: {} {} at {} classification={} delay={}s weather={}",
                delayEvent.carrierCode(), delayEvent.flightNumber(),
                delayEvent.arrivalAirportIata(), delayEvent.classification(),
                delayEvent.delaySeconds(),
                delayEvent.flightCategory());
    }
}
