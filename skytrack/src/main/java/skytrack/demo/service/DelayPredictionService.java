package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import skytrack.demo.config.PredictionProperties;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.PredictedDelayEvent;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.parquet.HistoricalPredictionWriter;
import skytrack.demo.sqs.SqsAirportEventProducer;

import java.time.Clock;

@Service
public class DelayPredictionService {

    private static final Logger log = LoggerFactory.getLogger(DelayPredictionService.class);

    private final OutboundScheduleResolver resolver;
    private final TurnaroundEstimator turnaroundEstimator;
    private final DelayPredictor delayPredictor;
    private final PredictionProperties props;
    private final RecentPredictionStore store;
    private final SqsAirportEventProducer eventProducer;
    private final HistoricalPredictionWriter historicalPredictionWriter;
    private final Clock clock;

    public DelayPredictionService(OutboundScheduleResolver resolver,
                                   TurnaroundEstimator turnaroundEstimator,
                                   DelayPredictor delayPredictor,
                                   PredictionProperties props,
                                   RecentPredictionStore store,
                                   SqsAirportEventProducer eventProducer,
                                   HistoricalPredictionWriter historicalPredictionWriter,
                                   Clock clock) {
        this.resolver = resolver;
        this.turnaroundEstimator = turnaroundEstimator;
        this.delayPredictor = delayPredictor;
        this.props = props;
        this.store = store;
        this.eventProducer = eventProducer;
        this.historicalPredictionWriter = historicalPredictionWriter;
        this.clock = clock;
    }

    public void predictNextDeparture(ResolvedArrival arrival) {
        try {
            var outbound = resolver.resolve(arrival);
            if (outbound.isEmpty()) return;
            var out = outbound.get();

            long min = turnaroundEstimator.minTurnaroundSeconds(out.carrierIata());
            long predicted = delayPredictor.predictDelaySeconds(
                    arrival.actualArrivalTime(), out.scheduledDepEpoch(), min);

            if (predicted / 60 < props.delayThresholdMinutes()) return;

            var event = new PredictedDelayEvent(
                    arrival.callsign(),
                    out.tailNumber(),
                    out.departureAirportIata(),
                    out.carrierIata(),
                    out.flightNumber(),
                    arrival.actualArrivalTime(),
                    out.scheduledDepEpoch(),
                    min,
                    predicted,
                    DelayClassification.fromDelaySeconds(predicted),
                    out.actualDepDelaySeconds(),
                    "BTS_REPLAY",
                    clock.instant());

            store.add(event);
            eventProducer.send(event);
            historicalPredictionWriter.buffer(event);

            log.info("Predicted {}s departure delay for {} outbound {} from {} (actual={}s)",
                    predicted, arrival.callsign(), out.flightNumber(),
                    out.departureAirportIata(), out.actualDepDelaySeconds());
        } catch (Exception e) {
            log.error("Prediction failed for {}: {}", arrival.callsign(), e.getMessage(), e);
        }
    }
}
