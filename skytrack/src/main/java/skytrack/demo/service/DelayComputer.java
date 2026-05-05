package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;
import skytrack.demo.model.ResolvedArrival;

import java.time.Instant;

@Service
public class DelayComputer {

    public DelayEvent compute(ResolvedArrival arrival) {
        DelayClassification classification = DelayClassification.fromDelaySeconds(arrival.delaySeconds());
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
                Instant.now());
    }
}
