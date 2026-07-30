package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.config.PredictionProperties;
import skytrack.demo.model.OutboundFlight;
import skytrack.demo.model.ResolvedArrival;

import java.util.Optional;

@Service
public class OutboundScheduleResolver {

    private final CallsignParser callsignParser;
    private final BtsScheduleRepository repo;
    private final PredictionProperties props;

    public OutboundScheduleResolver(CallsignParser callsignParser, BtsScheduleRepository repo,
                                    PredictionProperties props) {
        this.callsignParser = callsignParser;
        this.repo = repo;
        this.props = props;
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

        // Walk the rotation from *scheduled* arrival, not actual. A late aircraft would
        // otherwise skip past the very leg it delayed — discarding true positives exactly in
        // the cases worth catching.
        long walkAfter = inbound.get().scheduledArrEpoch() != null
                ? inbound.get().scheduledArrEpoch()
                : arrival.actualArrivalTime();

        return repo.findNextDeparture(
                        inbound.get().tailNumber(),
                        arrival.arrivalAirportIata(),
                        walkAfter,
                        props.maxRotationLookaheadSeconds())
                .map(out -> new OutboundFlight(
                        out.carrierIata(), out.flightNumber(), out.tailNumber(),
                        out.origin(), out.dest(), out.scheduledDepEpoch(),
                        out.actualDepDelaySeconds()));
    }
}
