package skytrack.demo.tooling;

import skytrack.demo.model.LandingEvent;
import skytrack.demo.service.CallsignParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns raw landings into deduped, parseable-only seed rows (first landing per callsign). */
public class SeedBuilder {

    private final CallsignParser parser;

    public SeedBuilder(CallsignParser parser) {
        this.parser = parser;
    }

    public List<SeedRow> build(List<LandingEvent> landings) {
        Map<String, SeedRow> firstByCallsign = new LinkedHashMap<>();
        for (LandingEvent e : landings) {
            var parsed = parser.parse(e.callsign());
            if (parsed.isEmpty()) continue;                           // skip non-carrier / malformed
            if (firstByCallsign.containsKey(e.callsign())) continue; // dedup to first landing
            var p = parsed.get();
            firstByCallsign.put(e.callsign(), new SeedRow(
                    e.callsign(),
                    p.iataCarrierCode() + p.flightNumber(),
                    p.icaoCarrierCode(),
                    e.arrivalTime(),
                    e.arrivalAirportIcao(),
                    e.arrivalAirportIata() == null ? "" : e.arrivalAirportIata()));
        }
        return new ArrayList<>(firstByCallsign.values());
    }
}
