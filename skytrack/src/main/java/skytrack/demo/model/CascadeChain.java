package skytrack.demo.model;

import java.time.Instant;
import java.util.List;

public record CascadeChain(
        String sourceCallsign,
        String originAirportIata,
        long sourceArrivalDelaySeconds,
        List<CascadeHop> hops,
        int flightsAffected,
        long totalPredictedDelaySeconds,
        Instant createdAt) {

    public static CascadeChain of(String sourceCallsign, String originAirportIata,
                                  long sourceArrivalDelaySeconds, List<CascadeHop> hops,
                                  Instant createdAt) {
        long total = hops.stream().mapToLong(CascadeHop::predictedDepDelaySeconds).sum();
        return new CascadeChain(sourceCallsign, originAirportIata, sourceArrivalDelaySeconds,
                List.copyOf(hops), hops.size(), total, createdAt);
    }
}
