package skytrack.demo.config;

import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.client.LiveOpenSkyClient;
import skytrack.demo.client.ReplayOpenSkyClient;

@Configuration
public class FlightDataSourceConfig {

    @Bean
    public FlightDataSource flightDataSource(OpenSkyProperties properties, ObjectMapper mapper) {
        return switch (properties.mode()) {
            case "live" -> new LiveOpenSkyClient(properties, mapper);
            case "replay" -> new ReplayOpenSkyClient(properties, mapper);
            default -> throw new IllegalArgumentException(
                    "Unknown opensky.mode: " + properties.mode() + ". Expected 'live' or 'replay'.");
        };
    }
}
