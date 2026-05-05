package skytrack.demo.model;

import java.time.Instant;

public record AirportDisruptionScore(
        String airportIata,
        double score,
        int activeDelayCount,
        int totalFlightsInWindow,
        double averageDelayMinutes,
        double trendDirection,
        Instant computedAt) {}
