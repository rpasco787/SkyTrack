package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skytrack.demo.model.FlightPosition;

import java.util.List;

public class LoggingFlightPositionHandler implements FlightPositionHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingFlightPositionHandler.class);

    @Override
    public void handle(List<FlightPosition> positions) {
        log.info("Received {} flight positions", positions.size());
        for (FlightPosition fp : positions) {
            log.debug("  {} ({}) at [{}, {}] alt={}m onGround={}",
                    fp.callsign(), fp.icao24(),
                    fp.latitude(), fp.longitude(),
                    fp.baroAltitude(), fp.onGround());
        }
    }
}
