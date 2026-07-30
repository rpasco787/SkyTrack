package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.config.PredictionProperties;
import skytrack.demo.model.BtsFlightRecord;
import skytrack.demo.model.ResolvedArrival;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundScheduleResolverTest {

    private static final BtsFlightRecord INBOUND =
            new BtsFlightRecord("UA", "1234", "N12345", "DEN", "ORD", 1773080000L, 1773083600L, 600L, false, null, null);
    private static final BtsFlightRecord OUTBOUND =
            new BtsFlightRecord("UA", "5678", "N12345", "ORD", "LAX", 1773090000L, 1773093600L, 900L, false, null, null);

    private final BtsScheduleRepository repo = new BtsScheduleRepository(List.of(INBOUND, OUTBOUND));
    private final OutboundScheduleResolver resolver =
            new OutboundScheduleResolver(new CallsignParser(), repo, props());

    private static PredictionProperties props() {
        return new PredictionProperties(true, "x", 45, 15, 360);
    }

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
        assertThat(result.get().destAirportIata()).isEqualTo("LAX");
        assertThat(result.get().scheduledDepEpoch()).isEqualTo(1773090000L);
    }

    @Test
    void doesNotResolveAnOvernightRotationBeyondTheLookaheadWindow() {
        // Inbound is scheduled into ORD at 1773083600; the tail's next ORD departure is not
        // until 1773170000 (~24h later). That is a different operating day, so its delay is
        // causally unrelated to this arrival and must not be attributed to it.
        var overnight = new BtsFlightRecord(
                "UA", "5678", "N12345", "ORD", "LAX", 1773170000L, 1773173600L, 900L, false, null, null);
        var repo = new BtsScheduleRepository(List.of(INBOUND, overnight));
        var resolver = new OutboundScheduleResolver(new CallsignParser(), repo, props());

        assertThat(resolver.resolve(arrival("UAL1234"))).isEmpty();
    }

    @Test
    void findsOutboundScheduledBeforeALateActualArrival() {
        // The aircraft was scheduled into ORD at 1773083600 and out again at 1773084200,
        // but actually landed at 1773085000 — 800s after its own outbound was due to push.
        // This is precisely the cascade the system exists to catch, so searching the
        // rotation from actual arrival must not skip it.
        var tightOutbound = new BtsFlightRecord(
                "UA", "5678", "N12345", "ORD", "LAX", 1773084200L, 1773087800L, 900L, false, null, null);
        var repo = new BtsScheduleRepository(List.of(INBOUND, tightOutbound));
        var resolver = new OutboundScheduleResolver(new CallsignParser(), repo, props());

        var result = resolver.resolve(arrival("UAL1234"));

        assertThat(result).isPresent();
        assertThat(result.get().scheduledDepEpoch()).isEqualTo(1773084200L);
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
    void emptyWhenRotationEndsAtThisAirport() {
        // Tail N12345 arrives into ORD and has no onward scheduled departure — the rotation
        // terminates here, so there is nothing to predict.
        var repo = new BtsScheduleRepository(List.of(INBOUND));
        var resolver = new OutboundScheduleResolver(new CallsignParser(), repo, props());

        assertThat(resolver.resolve(arrival("UAL1234"))).isEmpty();
    }

    @Test
    void stillResolvesWhenActualArrivalIsAfterTheOutboundWasDueToPush() {
        // Landed 1773099999, ~2.8h after the outbound's 1773090000 scheduled push. The rotation
        // still assigns this aircraft to that leg, and it is exactly the severe cascade worth
        // reporting — anchoring the walk on actual arrival used to discard it.
        var lateArrival = new ResolvedArrival("abc123", "UAL1234", null, null, "KORD", "ORD",
                1773099999L, null, null, null);

        var result = resolver.resolve(lateArrival);

        assertThat(result).isPresent();
        assertThat(result.get().scheduledDepEpoch()).isEqualTo(1773090000L);
    }
}
