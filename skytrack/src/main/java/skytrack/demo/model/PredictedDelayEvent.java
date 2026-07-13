package skytrack.demo.model;

import java.time.Instant;

public record PredictedDelayEvent(
        String inboundCallsign,
        String tailNumber,
        String departureAirportIata,
        String outboundCarrier,
        String outboundFlightNumber,
        long observedInboundArrivalEpoch,
        long outboundScheduledDepEpoch,
        long minTurnaroundSeconds,
        long predictedDelaySeconds,
        DelayClassification predictedClassification,
        Long actualDelaySeconds,
        String confidence,
        Instant createdAt) {}
