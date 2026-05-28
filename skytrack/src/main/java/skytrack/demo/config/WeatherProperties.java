package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "weather")
public record WeatherProperties(
        String mode,
        String apiUrl,
        String replayDir,
        Integer requestTimeoutMs,
        int pollIntervalMinutes,
        int cacheTtlMinutes,
        List<String> targetAirports
) {
    public WeatherProperties {
        if (mode == null) mode = "replay";
        if (apiUrl == null) apiUrl = "https://aviationweather.gov/api/data/metar";
        if (replayDir == null) replayDir = "./data/recorded-weather/";
        if (requestTimeoutMs == null || requestTimeoutMs <= 0) requestTimeoutMs = 5000;
        if (pollIntervalMinutes <= 0) pollIntervalMinutes = 15;
        if (cacheTtlMinutes <= 0) cacheTtlMinutes = 30;
        if (targetAirports == null) targetAirports = List.of();
    }
}
