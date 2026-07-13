package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundFlightTest {

    @Test
    void shouldConstructWithAllFields() {
        var flight = new OutboundFlight("UA", "1234", "N12345", "ORD", 1773090000L, 900L);
        assertThat(flight.carrierIata()).isEqualTo("UA");
        assertThat(flight.flightNumber()).isEqualTo("1234");
        assertThat(flight.tailNumber()).isEqualTo("N12345");
        assertThat(flight.departureAirportIata()).isEqualTo("ORD");
        assertThat(flight.scheduledDepEpoch()).isEqualTo(1773090000L);
        assertThat(flight.actualDepDelaySeconds()).isEqualTo(900L);
    }

    @Test
    void shouldAllowNullActualDelay() {
        var flight = new OutboundFlight("UA", "1234", "N12345", "ORD", 1773090000L, null);
        assertThat(flight.actualDepDelaySeconds()).isNull();
    }
}
