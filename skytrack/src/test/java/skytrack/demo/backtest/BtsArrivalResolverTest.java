package skytrack.demo.backtest;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.BtsFlightRecord;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.service.CallsignParser;
import skytrack.demo.service.TestRepos;

import static org.assertj.core.api.Assertions.assertThat;

class BtsArrivalResolverTest {

    @Test
    void resolvesLandingToArrivalWithBtsInboundDelay() {
        var repo = TestRepos.of(rec("UA", "100", "N1", "IAH", "ORD", 1000L, 5000L,
                /*depDelay*/1800L, /*arrDelay*/2100L));
        var resolver = new BtsArrivalResolver(new CallsignParser(), repo);
        var landing = new LandingEvent("abc123", "UAL100", "KORD", "ORD", 5100L, 41.9, -87.9);

        var arrival = resolver.resolve(landing).orElseThrow();
        assertThat(arrival.delaySeconds()).isEqualTo(2100L);
        assertThat(arrival.resolutionMethod()).isEqualTo("BTS_BACKTEST");
    }

    @Test
    void returnsEmptyForUnmappedCarrier() {
        assertThat(new BtsArrivalResolver(new CallsignParser(), TestRepos.of())
                .resolve(new LandingEvent("x", "XYZ999", "KORD", "ORD", 5100L, 0, 0)))
                .isEmpty();
    }

    private static BtsFlightRecord rec(String carrierIata, String flightNumber, String tailNumber,
                                        String origin, String dest,
                                        long scheduledDepEpoch, long scheduledArrEpoch,
                                        long actualDepDelaySeconds, long arrDelaySeconds) {
        return new BtsFlightRecord(carrierIata, flightNumber, tailNumber, origin, dest,
                scheduledDepEpoch, scheduledArrEpoch, actualDepDelaySeconds, false,
                arrDelaySeconds, null);
    }
}
