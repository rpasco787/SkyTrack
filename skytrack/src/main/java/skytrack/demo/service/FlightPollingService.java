package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.model.FlightPosition;

import java.util.List;

@Service
public class FlightPollingService {

    private static final Logger log = LoggerFactory.getLogger(FlightPollingService.class);

    private final FlightDataSource flightDataSource;

    public FlightPollingService(FlightDataSource flightDataSource) {
        this.flightDataSource = flightDataSource;
    }

    @Scheduled(fixedRate = 30_000)
    public void pollFlightData() {
        try {
            List<FlightPosition> positions = flightDataSource.fetchPositions();
            log.info("Polled {} aircraft positions", positions.size());

            if (!positions.isEmpty()) {
                FlightPosition sample = positions.getFirst();
                log.debug("Sample: {} ({}) at [{}, {}] alt={}m",
                        sample.callsign(), sample.icao24(),
                        sample.latitude(), sample.longitude(),
                        sample.baroAltitude());
            }
        } catch (Exception e) {
            log.error("Flight data polling failed", e);
        }
    }
}
