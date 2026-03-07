package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opensky")
public record OpenSkyProperties(
        String mode,
        String apiUrl,
        String username,
        String password,
        String replayDir,
        int replaySpeedMultiplier
) {
    public OpenSkyProperties {
        if (mode == null) mode = "replay";
        if (apiUrl == null) apiUrl = "https://opensky-network.org";
        if (replayDir == null) replayDir = "./data/recorded-opensky/";
        if (replaySpeedMultiplier <= 0) replaySpeedMultiplier = 1;
    }
}
