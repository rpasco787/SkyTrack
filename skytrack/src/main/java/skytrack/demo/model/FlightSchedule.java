package skytrack.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightSchedule(
        String callsign,
        String flightNumber,
        String airline,
        String origin,
        String destination,
        Instant scheduledDeparture,
        Instant scheduledArrival,
        Instant actualDeparture,
        Instant actualArrival,
        String gateOrigin,
        String gateDestination,
        String aircraftType
) {}
