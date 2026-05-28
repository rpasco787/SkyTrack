package skytrack.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WeatherObservation(
        String airportIcao,
        String airportIata,
        Instant observedAt,
        Double visibilityStatuteMiles,
        Integer ceilingFeet,
        Integer windSpeedKnots,
        Integer windGustKnots,
        FlightCategory flightCategory,
        String rawMetar
) {}
