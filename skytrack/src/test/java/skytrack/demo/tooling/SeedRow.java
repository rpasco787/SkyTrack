package skytrack.demo.tooling;

/** One anchored flight: the real replay arrival used to back a synthetic schedule. */
public record SeedRow(
        String callsign,      // e.g. "AAL103" (AeroAPI ident)
        String identIata,     // e.g. "AA103"
        String icaoCarrier,   // e.g. "AAL" (operator)
        long arrivalEpoch,    // real replay arrival time (epoch seconds)
        String airportIcao,   // e.g. "KJFK"
        String airportIata) { // e.g. "JFK" (may be empty)
}
