package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param replayLoop whether the replay client wraps back to the first snapshot once exhausted.
 *                   Soak testing only — timestamps repeat, so FIFO content-based dedup suppresses
 *                   some replayed positions and landing detection re-fires for aircraft that
 *                   already landed. Must stay false for the backtest harnesses, which terminate on
 *                   the exhaustion sentinel and would otherwise loop forever.
 */
@ConfigurationProperties(prefix = "opensky")
public record OpenSkyProperties(
        String mode,
        String apiUrl,
        String clientId,
        String clientSecret,
        String replayDir,
        int replaySpeedMultiplier,
        boolean replayLoop
) {
    public OpenSkyProperties {
        if (mode == null) mode = "replay";
        if (apiUrl == null) apiUrl = "https://opensky-network.org";
        if (replayDir == null) replayDir = "./data/recorded-opensky/";
        if (replaySpeedMultiplier <= 0) replaySpeedMultiplier = 1;
    }
}
