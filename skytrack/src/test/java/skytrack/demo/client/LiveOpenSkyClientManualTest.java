package skytrack.demo.client;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import skytrack.demo.config.OpenSkyProperties;
import skytrack.demo.model.FlightPosition;

import java.util.List;

/**
 * Manual integration test — hits the real OpenSky API.
 * Not intended for CI. Run from IDE or via:
 *   mvn test -Dtest="LiveOpenSkyClientManualTest" -DskipManual=false
 *
 * Set OPENSKY_USERNAME / OPENSKY_PASSWORD env vars for authenticated access
 * (5s rate limit vs 10s anonymous).
 */
@Disabled("Manual only — remove @Disabled to run against live API")
class LiveOpenSkyClientManualTest {

    @Test
    void fetchAndPrintLivePositions() {
        String username = System.getenv("OPENSKY_USERNAME");
        String password = System.getenv("OPENSKY_PASSWORD");

        var props = new OpenSkyProperties(
                "live",
                "https://opensky-network.org",
                username,
                password,
                null,
                1
        );

        var client = new LiveOpenSkyClient(props, new ObjectMapper());

        System.out.println("Fetching from OpenSky API...");
        List<FlightPosition> positions = client.fetchPositions();

        System.out.printf("Got %d US positions%n", positions.size());
        positions.stream().limit(10).forEach(fp ->
                System.out.printf("  %-10s %-8s lat=%.4f lon=%.4f alt=%.0fm%n",
                        fp.callsign(), fp.icao24(),
                        fp.latitude(), fp.longitude(),
                        fp.baroAltitude() != null ? fp.baroAltitude() : 0.0)
        );
    }
}
