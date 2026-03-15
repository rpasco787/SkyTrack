package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.sqs.SqsPositionProducer;

import java.util.List;

@Service
public class FlightPollingService {

    private static final Logger log = LoggerFactory.getLogger(FlightPollingService.class);

    private final FlightDataSource flightDataSource;
    private final SqsPositionProducer sqsPositionProducer;

    public FlightPollingService(FlightDataSource flightDataSource, SqsPositionProducer sqsPositionProducer) {
        this.flightDataSource = flightDataSource;
        this.sqsPositionProducer = sqsPositionProducer;
    }

    @Scheduled(fixedRate = 30_000)
    public void pollFlightData() {
        try {
            List<FlightPosition> positions = flightDataSource.fetchPositions();
            log.info("Polled {} aircraft positions", positions.size());

            if (!positions.isEmpty()) {
                sqsPositionProducer.send(positions);
                log.info("Published {} positions to SQS", positions.size());
            }
        } catch (Exception e) {
            log.error("Flight data polling failed", e);
        }
    }
}
