package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.BtsFlightRecord;
import skytrack.demo.model.ResolvedArrival;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundScheduleResolverTest {

    private static final BtsFlightRecord INBOUND =
            new BtsFlightRecord("UA", "1234", "N12345", "DEN", "ORD", 1773080000L, 600L, false);
    private static final BtsFlightRecord OUTBOUND =
            new BtsFlightRecord("UA", "5678", "N12345", "ORD", "LAX", 1773090000L, 900L, false);

    private final BtsScheduleRepository repo = new BtsScheduleRepository(List.of(INBOUND, OUTBOUND));
    private final OutboundScheduleResolver resolver =
            new OutboundScheduleResolver(new CallsignParser(), repo);

    private static ResolvedArrival arrival(String callsign) {
        return new ResolvedArrival("abc123", callsign, null, null, "KORD", "ORD",
                1773085000L, null, null, null);
    }

    @Test
    void resolvesOutboundForKnownTailRotation() {
        var result = resolver.resolve(arrival("UAL1234"));
        assertThat(result).isPresent();
        assertThat(result.get().tailNumber()).isEqualTo("N12345");
        assertThat(result.get().departureAirportIata()).isEqualTo("ORD");
        assertThat(result.get().scheduledDepEpoch()).isEqualTo(1773090000L);
    }

    @Test
    void emptyWhenCallsignUnparseable() {
        assertThat(resolver.resolve(arrival("UNKNOWN99"))).isEmpty();
    }

    @Test
    void emptyWhenNoInboundBtsRow() {
        // AAL has no records in the repo
        assertThat(resolver.resolve(arrival("AAL999"))).isEmpty();
    }

    @Test
    void emptyWhenTailHasNoLaterDeparture() {
        // arrivalTime after all outbound scheduled departures
        var lateArrival = new ResolvedArrival("abc123", "UAL1234", null, null, "KORD", "ORD",
                1773099999L, null, null, null);
        assertThat(resolver.resolve(lateArrival)).isEmpty();
    }
}
