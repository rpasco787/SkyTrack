package skytrack.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DelayEvent(
        String icao24,
        String callsign,
        String carrierCode,
        String flightNumber,
        String arrivalAirportIcao,
        String arrivalAirportIata,
        long actualArrivalTime,
        Long scheduledArrivalTime,
        Long delaySeconds,
        DelayClassification classification,
        String resolutionMethod,
        Instant createdAt,
        FlightCategory flightCategory,
        Double visibilityStatuteMiles,
        Integer ceilingFeet,
        Integer windSpeedKnots
) {}
