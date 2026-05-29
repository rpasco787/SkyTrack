package skytrack.demo.parquet;

import skytrack.demo.model.DelayEvent;

public record DelayParquetRow(
        String icao24,
        String callsign,
        String carrierCode,
        String flightNumber,
        String arrivalAirportIcao,
        String arrivalAirportIata,
        long actualArrivalTime,
        Long scheduledArrivalTime,
        Long delaySeconds,
        String classification,
        String resolutionMethod,
        long createdAtEpochMillis,
        String flightCategory,
        Double visibilityStatuteMiles,
        Integer ceilingFeet,
        Integer windSpeedKnots
) {
    public static DelayParquetRow from(DelayEvent e) {
        return new DelayParquetRow(
                e.icao24(),
                e.callsign(),
                e.carrierCode(),
                e.flightNumber(),
                e.arrivalAirportIcao(),
                e.arrivalAirportIata(),
                e.actualArrivalTime(),
                e.scheduledArrivalTime(),
                e.delaySeconds(),
                e.classification() != null ? e.classification().name() : null,
                e.resolutionMethod(),
                e.createdAt() != null ? e.createdAt().toEpochMilli() : 0L,
                e.flightCategory() != null ? e.flightCategory().name() : null,
                e.visibilityStatuteMiles(),
                e.ceilingFeet(),
                e.windSpeedKnots());
    }
}
