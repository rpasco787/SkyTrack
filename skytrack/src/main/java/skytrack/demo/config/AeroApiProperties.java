package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aeroapi")
public record AeroApiProperties(
        Boolean enabled,
        String baseUrl,
        String apiKey,
        int maxMonthlyCalls,
        int requestTimeoutMs
) {
    public AeroApiProperties {
        if (enabled == null) enabled = false;
        if (baseUrl == null) baseUrl = "https://aeroapi.flightaware.com/aeroapi";
        if (maxMonthlyCalls <= 0) maxMonthlyCalls = 10_000;
        if (requestTimeoutMs <= 0) requestTimeoutMs = 5_000;
    }
}
