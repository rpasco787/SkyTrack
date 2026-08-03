package skytrack.demo.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skytrack.demo.config.OpenSkyProperties;
import skytrack.demo.model.FlightPosition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class ReplayOpenSkyClient implements FlightDataSource {

    private static final Logger log = LoggerFactory.getLogger(ReplayOpenSkyClient.class);

    private final List<Path> replayFiles;
    private final ObjectMapper mapper;
    private final boolean loop;
    private final AtomicInteger index = new AtomicInteger(0);

    public ReplayOpenSkyClient(OpenSkyProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.replayFiles = loadSortedFiles(Path.of(properties.replayDir()));
        // Held as a field rather than the whole record: fetchPositions needs it on every call.
        this.loop = properties.replayLoop();
        log.info("Replay client loaded {} files from {} (loop={})",
                replayFiles.size(), properties.replayDir(), loop);
    }

    private static List<Path> loadSortedFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LoggerFactory.getLogger(ReplayOpenSkyClient.class)
                    .error("Failed to list replay directory: {}", dir, e);
            return List.of();
        }
    }

    @Override
    public List<FlightPosition> fetchPositions() {
        if (replayFiles.isEmpty()) {
            return List.of();
        }

        int i = index.getAndIncrement();
        if (i >= replayFiles.size()) {
            if (!loop) {
                log.info("Replay complete — no more files");
                return List.of();
            }
            // Wrap for soak testing. Timestamps repeat, so FIFO content-based dedup suppresses some
            // replayed positions and landing detection re-fires; this mode is for load testing the
            // pipeline, not for accuracy measurement.
            i = i % replayFiles.size();
            if (i == 0) {
                log.info("Replay wrapped to the start ({} files)", replayFiles.size());
            }
        }

        Path file = replayFiles.get(i);
        try {
            String content = Files.readString(file);
            JsonNode root = mapper.readTree(content);
            List<FlightPosition> positions = LiveOpenSkyClient.parseStateVectors(root);
            log.info("Replayed file {}/{}: {} — {} positions",
                    i + 1, replayFiles.size(), file.getFileName(), positions.size());
            return positions;
        } catch (IOException e) {
            log.error("Failed to read replay file: {}", file, e);
            return List.of();
        }
    }

    public int remainingFiles() {
        return Math.max(0, replayFiles.size() - index.get());
    }
}
