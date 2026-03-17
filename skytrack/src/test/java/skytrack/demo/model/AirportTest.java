package skytrack.demo.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AirportTest {

    @Test
    void shouldConstructAirportRecord() {
        var airport = new Airport("KLAX", "KLAX", "LAX", "Los Angeles International Airport",
                33.9425, -118.4081, "large_airport");
        assertThat(airport.ident()).isEqualTo("KLAX");
        assertThat(airport.iataCode()).isEqualTo("LAX");
        assertThat(airport.latitude()).isEqualTo(33.9425);
        assertThat(airport.longitude()).isEqualTo(-118.4081);
    }

    @Test
    void shouldSupportEquality() {
        var a1 = new Airport("KLAX", "KLAX", "LAX", "LAX", 33.9425, -118.4081, "large_airport");
        var a2 = new Airport("KLAX", "KLAX", "LAX", "LAX", 33.9425, -118.4081, "large_airport");
        assertThat(a1).isEqualTo(a2);
    }
}
