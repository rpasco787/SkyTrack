package skytrack.demo.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import skytrack.demo.model.FlightPosition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that parses a real recorded OpenSky file and verifies
 * field mapping against known state vector values.
 */
class ReplayDataIntegrationTest {

    private static final Path RECORDED_DIR = Path.of("./data/recorded-opensky/");
    private static final String FIRST_FILE = "1773078640.json";

    private final ObjectMapper mapper = new ObjectMapper();

    static boolean recordedDataExists() {
        return Files.exists(RECORDED_DIR.resolve(FIRST_FILE));
    }

    @Test
    @EnabledIf("recordedDataExists")
    void shouldParseRealRecordedFileWithCorrectFieldMapping() throws Exception {
        String content = Files.readString(RECORDED_DIR.resolve(FIRST_FILE));
        JsonNode root = mapper.readTree(content);

        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        // File has 11534 total state vectors, 7484 US, minus 50 with null lat/lon = 7434
        assertThat(positions).hasSize(7434);

        // Verify all positions are US flights with valid coordinates
        for (FlightPosition fp : positions) {
            assertThat(fp.icao24()).isNotBlank();
            assertThat(fp.latitude()).isNotNull();
            assertThat(fp.longitude()).isNotNull();
            // No trailing/leading whitespace on callsigns
            if (fp.callsign() != null && !fp.callsign().isEmpty()) {
                assertThat(fp.callsign()).isEqualTo(fp.callsign().trim());
            }
        }
    }

    @Test
    @EnabledIf("recordedDataExists")
    void shouldCorrectlyMapKnownFlightUAL430() throws Exception {
        // Known state vector from file 1773078640.json:
        // ["aa56da","UAL430  ","United States",1773078421,1773078421,
        //  -107.1335,39.4023,8542.02,false,194.5,247.78,
        //  0,null,8717.28,"3764",false,0]
        String content = Files.readString(RECORDED_DIR.resolve(FIRST_FILE));
        JsonNode root = mapper.readTree(content);

        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        FlightPosition ual430 = positions.stream()
                .filter(fp -> "UAL430".equals(fp.callsign()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("UAL430 not found in parsed positions"));

        // Verify every field maps to the correct array index
        assertThat(ual430.icao24()).isEqualTo("aa56da");           // index 0
        assertThat(ual430.callsign()).isEqualTo("UAL430");         // index 1 (trimmed)
        assertThat(ual430.latitude()).isEqualTo(39.4023);          // index 6 (NOT 5!)
        assertThat(ual430.longitude()).isEqualTo(-107.1335);       // index 5 (NOT 6!)
        assertThat(ual430.baroAltitude()).isEqualTo(8542.02);      // index 7
        assertThat(ual430.velocity()).isEqualTo(194.5);            // index 9
        assertThat(ual430.heading()).isEqualTo(247.78);            // index 10
        assertThat(ual430.onGround()).isFalse();                   // index 8
        assertThat(ual430.lastContact()).isEqualTo(1773078421L);   // index 4
        assertThat(ual430.timePosition()).isEqualTo(1773078421L);  // index 3
        assertThat(ual430.parsedAt()).isNotNull();
    }

    @Test
    @EnabledIf("recordedDataExists")
    void shouldNotContainNonUSFlights() throws Exception {
        String content = Files.readString(RECORDED_DIR.resolve(FIRST_FILE));
        JsonNode root = mapper.readTree(content);

        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        // The raw file contains French flights like TVF60KN (icao 39de4e).
        // Verify these were filtered out.
        assertThat(positions.stream()
                .filter(fp -> "39de4e".equals(fp.icao24()))
                .findAny()).isEmpty();
    }

    @Test
    @EnabledIf("recordedDataExists")
    void shouldSkipUSFlightsWithNullCoordinates() throws Exception {
        String content = Files.readString(RECORDED_DIR.resolve(FIRST_FILE));
        JsonNode root = mapper.readTree(content);

        List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);

        // JIA5260 (icao a76d5a) is a US flight with null lat/lon — should be filtered out
        assertThat(positions.stream()
                .filter(fp -> "a76d5a".equals(fp.icao24()))
                .findAny()).isEmpty();
    }

    @Test
    @EnabledIf("recordedDataExists")
    void shouldParseAllRecordedFilesWithoutErrors() throws Exception {
        // Verify every recorded file can be parsed without throwing
        List<Path> allFiles = Files.list(RECORDED_DIR)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted()
                .toList();

        assertThat(allFiles).isNotEmpty();

        int totalPositions = 0;
        for (Path file : allFiles) {
            String content = Files.readString(file);
            JsonNode root = mapper.readTree(content);
            List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);
            assertThat(positions).as("File %s should parse without errors", file.getFileName()).isNotNull();
            totalPositions += positions.size();
        }

        // Sanity: with 370 files averaging ~7400 US positions each, expect millions
        assertThat(totalPositions).isGreaterThan(1_000_000);
    }
}
