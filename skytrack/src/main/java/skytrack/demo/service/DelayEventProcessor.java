package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.sqs.SqsAirportEventProducer;

@Service
public class DelayEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(DelayEventProcessor.class);

    private final DelayComputer delayComputer;
    private final DisruptionScoreService disruptionScoreService;
    private final SqsAirportEventProducer eventProducer;
    private final CascadeDetector cascadeDetector;

    public DelayEventProcessor(DelayComputer delayComputer,
                               DisruptionScoreService disruptionScoreService,
                               SqsAirportEventProducer eventProducer,
                               CascadeDetector cascadeDetector) {
        this.delayComputer = delayComputer;
        this.disruptionScoreService = disruptionScoreService;
        this.eventProducer = eventProducer;
        this.cascadeDetector = cascadeDetector;
    }

    public void process(ResolvedArrival arrival) {
        var delayEvent = delayComputer.compute(arrival);

        disruptionScoreService.recordDelay(delayEvent);
        eventProducer.send(delayEvent);

        cascadeDetector.checkCascade(delayEvent).ifPresent(alert ->
                log.info("Cascade risk: {} at {} predicted downstream delay={}min",
                        alert.sourceCallsign(), alert.arrivalAirportIata(),
                        alert.predictedDownstreamDelaySeconds() / 60));

        log.debug("Processed delay event: {} {} at {} classification={} delay={}s",
                delayEvent.carrierCode(), delayEvent.flightNumber(),
                delayEvent.arrivalAirportIata(), delayEvent.classification(),
                delayEvent.delaySeconds());
    }
}
