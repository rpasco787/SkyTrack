package skytrack.demo.backtest;

import skytrack.demo.model.BtsFlightRecord;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.service.BtsScheduleRepository;
import skytrack.demo.service.CallsignParser;

import java.util.Optional;

/**
 * Resolves a replayed {@link LandingEvent} to a {@link ResolvedArrival} using BTS ground truth
 * for the inbound leg, in place of the production {@code ScheduleResolver}'s AeroAPI call.
 *
 * <p>This is not ground-truth leakage: the inbound arrival delay is an observed input available
 * at detection time in production ({@code DelayComputer} derives it from the schedule). The thing
 * being predicted is the downstream departure delay. Using BTS {@code ARR_DELAY} for the inbound
 * leg is the offline equivalent of what the live pipeline observes.
 */
public class BtsArrivalResolver {

    private final CallsignParser callsignParser;
    private final BtsScheduleRepository repo;

    private int landings;
    private int callsignParsed;
    private int inboundLegFound;

    public BtsArrivalResolver(CallsignParser callsignParser, BtsScheduleRepository repo) {
        this.callsignParser = callsignParser;
        this.repo = repo;
    }

    public Optional<ResolvedArrival> resolve(LandingEvent event) {
        landings++;
        var parsed = callsignParser.parse(event.callsign());
        if (parsed.isEmpty()) return Optional.empty();
        callsignParsed++;

        Optional<BtsFlightRecord> inbound = repo.findInboundLeg(
                parsed.get().iataCarrierCode(), parsed.get().flightNumber(),
                event.arrivalAirportIata(), event.arrivalTime());
        if (inbound.isEmpty()) return Optional.empty();
        inboundLegFound++;

        BtsFlightRecord leg = inbound.get();
        return Optional.of(new ResolvedArrival(
                event.icao24(), event.callsign(), parsed.get().iataCarrierCode(),
                parsed.get().flightNumber(), event.arrivalAirportIcao(), event.arrivalAirportIata(),
                event.arrivalTime(), leg.scheduledArrEpoch(), leg.arrDelaySeconds(), "BTS_BACKTEST"));
    }

    public int landings() { return landings; }
    public int callsignParsed() { return callsignParsed; }
    public int inboundLegFound() { return inboundLegFound; }
}
