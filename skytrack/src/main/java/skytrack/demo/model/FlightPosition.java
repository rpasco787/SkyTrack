package skytrack.demo.model;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightPosition(
        String icao24,
        String callsign,
        Double latitude,
        Double longitude,
        Double baroAltitude,
        Double velocity,
        Double heading,
        boolean onGround,
        long lastContact,
        long timePosition,
        Instant parsedAt
) {
    public FlightPosition {
        if (callsign != null) {
            callsign = callsign.trim();
        }
    }
}
