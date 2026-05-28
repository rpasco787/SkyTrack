package skytrack.demo.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.WeatherObservation;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayAviationWeatherClientTest {

    @Test
    void shouldReadObservationsFromFixture(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("metar.json");
        Files.writeString(file, """
                [
                  {
                    "icaoId": "KORD",
                    "obsTime": 1714915200,
                    "visib": "10+",
                    "wdir": 270,
                    "wspd": 12,
                    "rawOb": "METAR KORD 051430Z 27012KT 10SM CLR",
                    "clouds": [{"cover": "CLR"}]
                  },
                  {
                    "icaoId": "KATL",
                    "obsTime": 1714915200,
                    "visib": "3",
                    "wdir": 90,
                    "wspd": 8,
                    "rawOb": "METAR KATL 051430Z 09008KT 3SM BR OVC012",
                    "clouds": [{"cover": "OVC", "base": 1200}]
                  }
                ]
                """);

        var props = new WeatherProperties("replay", null,
                tempDir.toString(), 5000, 15, 30, List.of("KORD", "KATL"));
        var client = new ReplayAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD", "KATL"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).airportIcao()).isEqualTo("KORD");
        assertThat(result.get(0).flightCategory()).isEqualTo(FlightCategory.VFR);
        assertThat(result.get(1).airportIcao()).isEqualTo("KATL");
        assertThat(result.get(1).flightCategory()).isEqualTo(FlightCategory.MVFR);
    }

    @Test
    void shouldFilterToRequestedAirports(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("metar.json");
        Files.writeString(file, """
                [
                  {"icaoId": "KORD", "obsTime": 1714915200, "visib": "10", "rawOb": "x", "clouds": []},
                  {"icaoId": "KJFK", "obsTime": 1714915200, "visib": "10", "rawOb": "x", "clouds": []},
                  {"icaoId": "KLAX", "obsTime": 1714915200, "visib": "10", "rawOb": "x", "clouds": []}
                ]
                """);

        var props = new WeatherProperties("replay", null,
                tempDir.toString(), 5000, 15, 30, List.of("KORD", "KLAX"));
        var client = new ReplayAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD", "KLAX"));

        assertThat(result).extracting(WeatherObservation::airportIcao)
                .containsExactlyInAnyOrder("KORD", "KLAX");
    }

    @Test
    void shouldReturnEmptyWhenDirectoryMissing() {
        var props = new WeatherProperties("replay", null,
                "/nonexistent/path/", 5000, 15, 30, List.of("KORD"));
        var client = new ReplayAviationWeatherClient(props, new ObjectMapper());

        assertThat(client.fetchObservations(List.of("KORD"))).isEmpty();
    }
}
