package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherObservationTest {

    @Test
    void shouldConstructWeatherObservation() {
        Instant observed = Instant.parse("2026-05-05T14:30:00Z");
        var obs = new WeatherObservation(
                "KORD", "ORD", observed,
                4.0, 1500, 18, 25, FlightCategory.MVFR,
                "METAR KORD 051430Z 27018G25KT 4SM BR OVC015");
        assertThat(obs.airportIcao()).isEqualTo("KORD");
        assertThat(obs.flightCategory()).isEqualTo(FlightCategory.MVFR);
        assertThat(obs.windGustKnots()).isEqualTo(25);
    }

    @Test
    void shouldAllowNullOptionalFields() {
        var obs = new WeatherObservation(
                "KORD", "ORD", Instant.now(),
                null, null, null, null, FlightCategory.UNKNOWN, null);
        assertThat(obs.visibilityStatuteMiles()).isNull();
        assertThat(obs.rawMetar()).isNull();
    }
}
