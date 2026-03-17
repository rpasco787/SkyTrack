package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.state-machine")
public record StateMachineProperties(
        double groundAltitudeMeters,
        double approachRadiusKm,
        double groundRadiusKm,
        long staleTimeoutSeconds) {}
