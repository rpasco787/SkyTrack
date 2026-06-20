package skytrack.demo.tooling;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.service.CallsignParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeedBuilderTest {

    private final SeedBuilder builder = new SeedBuilder(new CallsignParser());

    private static LandingEvent landing(String callsign, long arrivalTime, String iata) {
        return new LandingEvent("icao-" + callsign, callsign, "K" + iata, iata,
                arrivalTime, 41.9, -87.9);
    }

    @Test
    void keepsOnlyParseableCarrierCallsigns() {
        List<SeedRow> rows = builder.build(List.of(
                landing("AAL103", 1000, "JFK"),   // parseable carrier
                landing("N12345", 1000, "JFK"),   // not LLLNNN
                landing("XYZ999", 1000, "JFK"))); // LLLNNN but unknown carrier
        assertThat(rows).extracting(SeedRow::callsign).containsExactly("AAL103");
    }

    @Test
    void dedupsToFirstLandingPerCallsign() {
        List<SeedRow> rows = builder.build(List.of(
                landing("UAL200", 5000, "ORD"),
                landing("UAL200", 9000, "LAX"))); // later landing ignored
        assertThat(rows).singleElement()
                .satisfies(r -> {
                    assertThat(r.arrivalEpoch()).isEqualTo(5000);
                    assertThat(r.airportIata()).isEqualTo("ORD");
                });
    }

    @Test
    void capturesCarrierAndIataFlightNumber() {
        SeedRow r = builder.build(List.of(landing("DAL567", 1234, "JFK"))).get(0);
        assertThat(r.icaoCarrier()).isEqualTo("DAL");
        assertThat(r.identIata()).isEqualTo("DL567");
        assertThat(r.airportIcao()).isEqualTo("KJFK");
    }
}
