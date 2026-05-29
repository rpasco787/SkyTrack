package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.model.WeatherObservation;

import java.time.Instant;
import java.util.Optional;

@Service
public class DelayComputer {

    public DelayEvent compute(ResolvedArrival arrival) {
        return compute(arrival, Optional.empty());
    }

    public DelayEvent compute(ResolvedArrival arrival, Optional<WeatherObservation> weather) {
        DelayClassification classification = DelayClassification.fromDelaySeconds(arrival.delaySeconds());
        FlightCategory category = weather.map(WeatherObservation::flightCategory).orElse(null);
        Double visibility = weather.map(WeatherObservation::visibilityStatuteMiles).orElse(null);
        Integer ceiling = weather.map(WeatherObservation::ceilingFeet).orElse(null);
        Integer windSpeed = weather.map(WeatherObservation::windSpeedKnots).orElse(null);

        return new DelayEvent(
                arrival.icao24(),
                arrival.callsign(),
                arrival.carrierCode(),
                arrival.flightNumber(),
                arrival.arrivalAirportIcao(),
                arrival.arrivalAirportIata(),
                arrival.actualArrivalTime(),
                arrival.scheduledArrivalTime(),
                arrival.delaySeconds(),
                classification,
                arrival.resolutionMethod(),
                Instant.now(),
                category,
                visibility,
                ceiling,
                windSpeed);
    }
}
