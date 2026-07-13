package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.OutboundFlight;
import skytrack.demo.model.ResolvedArrival;

import java.util.Optional;

@Service
public class OutboundScheduleResolver {

    private final CallsignParser callsignParser;
    private final BtsScheduleRepository repo;

    public OutboundScheduleResolver(CallsignParser callsignParser, BtsScheduleRepository repo) {
        this.callsignParser = callsignParser;
        this.repo = repo;
    }

    public Optional<OutboundFlight> resolve(ResolvedArrival arrival) {
        var parsed = callsignParser.parse(arrival.callsign());
        if (parsed.isEmpty()) return Optional.empty();

        var inbound = repo.findInboundLeg(
                parsed.get().iataCarrierCode(),
                parsed.get().flightNumber(),
                arrival.arrivalAirportIata(),
                arrival.actualArrivalTime());
        if (inbound.isEmpty()) return Optional.empty();

        return repo.findNextDeparture(
                        inbound.get().tailNumber(),
                        arrival.arrivalAirportIata(),
                        arrival.actualArrivalTime())
                .map(out -> new OutboundFlight(
                        out.carrierIata(), out.flightNumber(), out.tailNumber(),
                        out.origin(), out.scheduledDepEpoch(), out.actualDepDelaySeconds()));
    }
}
