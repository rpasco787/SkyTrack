package skytrack.demo.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.WeatherObservation;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class ReplayAviationWeatherClient implements WeatherSource {

    private static final Logger log = LoggerFactory.getLogger(ReplayAviationWeatherClient.class);

    private final WeatherProperties properties;
    private final LiveAviationWeatherClient parser;

    public ReplayAviationWeatherClient(WeatherProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.parser = new LiveAviationWeatherClient(properties, mapper);
    }

    @Override
    public List<WeatherObservation> fetchObservations(List<String> airportIcaoCodes) {
        Optional<String> body = readLatestFixture();
        if (body.isEmpty()) {
            log.warn("No replay weather fixture found in {}", properties.replayDir());
            return List.of();
        }
        Set<String> requested = Set.copyOf(airportIcaoCodes);
        return parser.parse(body.get()).stream()
                .filter(obs -> requested.isEmpty() || requested.contains(obs.airportIcao()))
                .toList();
    }

    private Optional<String> readLatestFixture() {
        Path dir = Path.of(properties.replayDir());
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            log.error("Failed to read fixture {}: {}", p, e.getMessage());
                            return null;
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to list weather replay directory {}: {}",
                    properties.replayDir(), e.getMessage());
            return Optional.empty();
        }
    }
}
