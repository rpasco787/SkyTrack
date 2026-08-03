package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.state-machine")
public record StateMachineProperties(
        double groundAltitudeMeters,
        double approachRadiusKm,
        double groundRadiusKm,
        long staleTimeoutSeconds,
        long persistIntervalSeconds) {

    public StateMachineProperties {
        if (persistIntervalSeconds <= 0) {
            persistIntervalSeconds = 120;
        }
        if (persistIntervalSeconds >= staleTimeoutSeconds) {
            throw new IllegalArgumentException(
                    "persistIntervalSeconds (" + persistIntervalSeconds + ") must be less than "
                    + "staleTimeoutSeconds (" + staleTimeoutSeconds + "): the stale window is "
                    + "widened by the persist interval to absorb write deferral, so an interval at "
                    + "or above the timeout would more than double the window and let genuinely "
                    + "lost tracks keep their state indefinitely");
        }
    }

    /**
     * The stale threshold the state machine actually applies.
     *
     * <p>Conditional persistence means {@code AircraftTrack.lastSeen} is the last <em>persisted</em>
     * contact, which lags the true last-seen by up to {@link #persistIntervalSeconds()}. Comparing a
     * lagging value against the raw timeout shrinks the effective window to
     * {@code staleTimeout - persistInterval} and spuriously resets tracks to {@code UNKNOWN} — and a
     * landing observed from {@code UNKNOWN} is never emitted. Measured over the 370-snapshot replay,
     * the uncompensated form dropped 63 of 4109 landings (1.5%) at a 120s interval.
     *
     * <p>Widening by exactly the interval removes those false resets. It over-tolerates instead:
     * a track genuinely absent between {@code staleTimeout} and this value keeps its state where it
     * previously reset, which detects 17 additional landings (0.4%) over the same replay. Erring
     * toward detection is the correct direction for a delay-detection system, but it is a real
     * semantic change, and {@code TrackPersistenceParityIT} bounds it.
     */
    public long effectiveStaleTimeoutSeconds() {
        return staleTimeoutSeconds + persistIntervalSeconds;
    }
}
