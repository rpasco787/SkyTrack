package skytrack.demo.model;

import java.time.Instant;

public record CascadeAlert(
        String sourceCallsign,
        String arrivalAirportIata,
        long currentDelaySeconds,
        long predictedDownstreamDelaySeconds,
        double propagationFactor,
        Instant createdAt) {}
