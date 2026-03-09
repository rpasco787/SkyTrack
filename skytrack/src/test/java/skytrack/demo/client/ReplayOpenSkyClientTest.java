package skytrack.demo.client;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import skytrack.demo.config.OpenSkyProperties;
import skytrack.demo.model.FlightPosition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayOpenSkyClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldReplayFilesInTimestampOrder(@TempDir Path tempDir) throws Exception {
        // Write files out of order to verify sorting
        Files.writeString(tempDir.resolve("1709312430.json"), """
                {"time": 1709312430, "states": [
                    ["def789", "DAL567  ", "United States", 1709312430, 1709312430,
                     -73.7781, 40.6413, 0.0, true, 0.0, 0.0,
                     null, null, null, null, true, 0]
                ]}
                """);
        Files.writeString(tempDir.resolve("1709312400.json"), """
                {"time": 1709312400, "states": [
                    ["abc123", "UAL1234 ", "United States", 1709312400, 1709312400,
                     -87.9073, 41.9742, 10668.0, false, 230.5, 270.0,
                     null, null, null, null, false, 0]
                ]}
                """);

        var props = new OpenSkyProperties("replay", null, null, null, tempDir.toString(), 1);
        var client = new ReplayOpenSkyClient(props, mapper);

        // First call returns first file's data
        List<FlightPosition> first = client.fetchPositions();
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().icao24()).isEqualTo("abc123");

        // Second call returns second file's data
        List<FlightPosition> second = client.fetchPositions();
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().icao24()).isEqualTo("def789");
    }

    @Test
    void shouldReturnEmptyWhenAllFilesReplayed(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("1709312400.json"), """
                {"time": 1709312400, "states": [
                    ["abc123", "UAL1234 ", "United States", 1709312400, 1709312400,
                     -87.9073, 41.9742, 10668.0, false, 230.5, 270.0,
                     null, null, null, null, false, 0]
                ]}
                """);

        var props = new OpenSkyProperties("replay", null, null, null, tempDir.toString(), 1);
        var client = new ReplayOpenSkyClient(props, mapper);

        client.fetchPositions(); // consume the only file
        List<FlightPosition> empty = client.fetchPositions();

        assertThat(empty).isEmpty();
    }

    @Test
    void shouldHandleEmptyDirectory(@TempDir Path tempDir) {
        var props = new OpenSkyProperties("replay", null, null, null, tempDir.toString(), 1);
        var client = new ReplayOpenSkyClient(props, mapper);

        List<FlightPosition> result = client.fetchPositions();

        assertThat(result).isEmpty();
    }
}
