package skytrack.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import skytrack.demo.client.LiveAviationWeatherClient;
import skytrack.demo.client.ReplayAviationWeatherClient;
import skytrack.demo.client.WeatherSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class WeatherSourceConfig {

    @Bean
    public WeatherSource weatherSource(WeatherProperties properties, ObjectMapper mapper) {
        return switch (properties.mode()) {
            case "live" -> new LiveAviationWeatherClient(properties, mapper);
            case "replay" -> new ReplayAviationWeatherClient(properties, mapper);
            default -> throw new IllegalArgumentException(
                    "Unknown weather.mode: " + properties.mode() + ". Expected 'live' or 'replay'.");
        };
    }
}
