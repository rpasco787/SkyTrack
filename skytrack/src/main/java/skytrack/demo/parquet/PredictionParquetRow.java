package skytrack.demo.parquet;

import skytrack.demo.model.PredictedDelayEvent;

public record PredictionParquetRow(
        String inboundCallsign,
        String tailNumber,
        String departureAirportIata,
        String outboundCarrier,
        String outboundFlightNumber,
        long observedInboundArrivalEpoch,
        long outboundScheduledDepEpoch,
        long minTurnaroundSeconds,
        long predictedDelaySeconds,
        Long actualDelaySeconds,
        String predictedClassification,
        String confidence,
        long createdAtEpochMillis
) {
    public static PredictionParquetRow from(PredictedDelayEvent e) {
        return new PredictionParquetRow(
                e.inboundCallsign(),
                e.tailNumber(),
                e.departureAirportIata(),
                e.outboundCarrier(),
                e.outboundFlightNumber(),
                e.observedInboundArrivalEpoch(),
                e.outboundScheduledDepEpoch(),
                e.minTurnaroundSeconds(),
                e.predictedDelaySeconds(),
                e.actualDelaySeconds(),
                e.predictedClassification() != null ? e.predictedClassification().name() : null,
                e.confidence(),
                e.createdAt() != null ? e.createdAt().toEpochMilli() : 0L);
    }
}
